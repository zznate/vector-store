package io.github.zznate.vectorstore.api.resource;

import io.github.zznate.vectorstore.api.dto.DeleteVectorsRequest;
import io.github.zznate.vectorstore.api.dto.PutVectorsRequest;
import io.github.zznate.vectorstore.api.dto.PutVectorsResponse;
import io.github.zznate.vectorstore.api.dto.QueryHit;
import io.github.zznate.vectorstore.api.dto.QueryRequest;
import io.github.zznate.vectorstore.api.dto.QueryResponse;
import io.github.zznate.vectorstore.api.dto.StatsResponse;
import io.github.zznate.vectorstore.api.dto.VectorInput;
import io.github.zznate.vectorstore.api.dto.VectorLookupResponse;
import io.github.zznate.vectorstore.api.error.DimensionMismatchException;
import io.github.zznate.vectorstore.api.error.IndexNotFoundException;
import io.github.zznate.vectorstore.api.error.UnsupportedFilterOperatorHttpException;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestResolver;
import io.github.zznate.vectorstore.core.catalog.model.Segment;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.engine.buffer.BufferEntry;
import io.github.zznate.vectorstore.engine.buffer.WriteBuffer;
import io.github.zznate.vectorstore.engine.search.QueryCoordinator;
import io.github.zznate.vectorstore.engine.search.ScoredHit;
import io.github.zznate.vectorstore.engine.search.Searcher;
import io.github.zznate.vectorstore.engine.tombstone.CatalogStagedTombstones;
import io.github.zznate.vectorstore.metadata.filter.FilterExpr;
import io.github.zznate.vectorstore.metadata.filter.FilterParser;
import io.github.zznate.vectorstore.metadata.filter.UnsupportedFilterOperatorException;
import io.github.zznate.vectorstore.metadata.sidecar.AttributeSidecar;
import io.github.zznate.vectorstore.metadata.sidecar.SidecarLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/v1/indexes/{bucket}/{index}")
@Tag(name = "vectors", description = "Vector-level operations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VectorsResource {

  private final VectorIndexRepository indexes;
  private final WriteBuffer writeBuffer;
  private final QueryCoordinator queryCoordinator;
  private final Searcher searcher;
  private final CatalogStagedTombstones tombstones;
  private final ManifestResolver manifestResolver;
  private final SidecarLoader sidecarLoader;

  @Inject
  public VectorsResource(
      VectorIndexRepository indexes,
      WriteBuffer writeBuffer,
      QueryCoordinator queryCoordinator,
      Searcher searcher,
      CatalogStagedTombstones tombstones,
      ManifestResolver manifestResolver,
      SidecarLoader sidecarLoader) {
    this.indexes = indexes;
    this.writeBuffer = writeBuffer;
    this.queryCoordinator = queryCoordinator;
    this.searcher = searcher;
    this.tombstones = tombstones;
    this.manifestResolver = manifestResolver;
    this.sidecarLoader = sidecarLoader;
  }

  @POST
  @Path("/vectors:put")
  @Operation(summary = "Upsert vectors into the write buffer")
  public Response putVectors(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @Valid PutVectorsRequest request) {
    VectorIndex index = requireIndex(bucketId, indexId);
    List<VectorInput> incoming = request.vectors();
    List<BufferEntry> batch = new ArrayList<>(incoming.size());
    for (VectorInput input : incoming) {
      if (input.vector().length != index.dimension()) {
        throw new DimensionMismatchException(
            index.indexId(), index.dimension(), input.vector().length);
      }
      batch.add(new BufferEntry(input.id(), input.vector(), input.attributes()));
    }
    writeBuffer.append(index.indexId(), batch);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new PutVectorsResponse(batch.size(), writeBuffer.size(index.indexId())))
        .build();
  }

  @POST
  @Path("/vectors:query")
  @Operation(summary = "kNN query over the active manifest")
  public QueryResponse queryVectors(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @Valid QueryRequest request) {
    VectorIndex index = requireIndex(bucketId, indexId);
    if (request.vector().length != index.dimension()) {
      throw new DimensionMismatchException(
          index.indexId(), index.dimension(), request.vector().length);
    }
    FilterExpr filter;
    try {
      filter = FilterParser.parse(request.filter());
    } catch (UnsupportedFilterOperatorException e) {
      throw new UnsupportedFilterOperatorHttpException(e.key(), e.operator());
    }

    List<ScoredHit> hits =
        queryCoordinator.query(index.indexId(), request.vector(), request.topK(), filter);
    List<QueryHit> mapped = new ArrayList<>(hits.size());
    for (ScoredHit hit : hits) {
      mapped.add(new QueryHit(hit.userId(), hit.score(), hit.attributes()));
    }
    return new QueryResponse(mapped);
  }

  @POST
  @Path("/vectors:delete")
  @Operation(summary = "Tombstone vectors by user id")
  public Response deleteVectors(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @Valid DeleteVectorsRequest request) {
    VectorIndex index = requireIndex(bucketId, indexId);
    tombstones.tombstone(index.indexId(), request.ids());
    return Response.noContent().build();
  }

  @GET
  @Path("/vectors/{id}")
  @Operation(summary = "Report whether a vector id is visible in the active manifest")
  public VectorLookupResponse getVector(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @PathParam("id") String id) {
    VectorIndex index = requireIndex(bucketId, indexId);
    if (tombstones.isTombstoned(index.indexId(), id)) {
      return new VectorLookupResponse(id, false, Map.of());
    }
    for (Segment segment : manifestResolver.activeSegments(index.indexId())) {
      int ordinal = searcher.findOrdinal(segment, id);
      if (ordinal < 0) {
        continue;
      }
      // Skip segments where this ordinal has been persistently tombstoned;
      // GET /vectors/{id} must respect committed deletes.
      if (sidecarLoader.tombstones(segment).bitmap().contains(ordinal)) {
        return new VectorLookupResponse(id, false, Map.of());
      }
      AttributeSidecar attributes = sidecarLoader.attributes(segment);
      Map<String, String> attrs =
          ordinal < attributes.size() ? attributes.attributesOf(ordinal) : Map.of();
      return new VectorLookupResponse(id, true, attrs);
    }
    return new VectorLookupResponse(id, false, Map.of());
  }

  @GET
  @Path("/stats")
  @Operation(summary = "Current active manifest stats + pending buffer size")
  public StatsResponse stats(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    VectorIndex index = requireIndex(bucketId, indexId);
    List<Segment> active = manifestResolver.activeSegments(index.indexId());
    long vectorCount = active.stream().mapToLong(Segment::vectorCount).sum();
    long totalBytes = active.stream().mapToLong(Segment::bytes).sum();
    int pending = writeBuffer.size(index.indexId());
    return new StatsResponse(active.size(), vectorCount, totalBytes, pending);
  }

  private VectorIndex requireIndex(String bucketId, String indexId) {
    String qualified = bucketId + "/" + indexId;
    return indexes
        .findById(qualified)
        .orElseThrow(() -> new IndexNotFoundException(qualified));
  }
}
