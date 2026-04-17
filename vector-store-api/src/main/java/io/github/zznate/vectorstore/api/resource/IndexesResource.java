package io.github.zznate.vectorstore.api.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zznate.vectorstore.api.dto.CreateIndexRequest;
import io.github.zznate.vectorstore.api.dto.IndexResponse;
import io.github.zznate.vectorstore.api.error.BucketNotFoundException;
import io.github.zznate.vectorstore.api.error.IndexAlreadyExistsException;
import io.github.zznate.vectorstore.api.error.IndexNotFoundException;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/v1/buckets/{bucket}/indexes")
@Tag(name = "indexes", description = "Index lifecycle within a bucket")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IndexesResource {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final BucketRepository buckets;
  private final VectorIndexRepository indexes;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  @Inject
  public IndexesResource(
      BucketRepository buckets,
      VectorIndexRepository indexes,
      Clock clock,
      ObjectMapper objectMapper) {
    this.buckets = buckets;
    this.indexes = indexes;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  @POST
  @Operation(summary = "Create an index in a bucket")
  public Response create(
      @PathParam("bucket") String bucketId, @Valid CreateIndexRequest request) {
    requireBucket(bucketId);
    String qualifiedId = qualify(bucketId, request.indexId());
    if (indexes.findById(qualifiedId).isPresent()) {
      throw new IndexAlreadyExistsException(qualifiedId);
    }
    VectorIndex index =
        indexes.create(
            new VectorIndex(
                qualifiedId,
                bucketId,
                request.displayName(),
                request.dimension(),
                request.metric(),
                writeEngineParams(request.engineParams()),
                clock.instant()));
    return Response.status(Response.Status.CREATED).entity(toResponse(index)).build();
  }

  @GET
  @Operation(summary = "List indexes in a bucket")
  public List<IndexResponse> list(@PathParam("bucket") String bucketId) {
    requireBucket(bucketId);
    return indexes.listByBucket(bucketId).stream().map(this::toResponse).toList();
  }

  @GET
  @Path("/{index}")
  @Operation(summary = "Get a bucket's index by id")
  public IndexResponse get(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    requireBucket(bucketId);
    String qualifiedId = qualify(bucketId, indexId);
    return indexes
        .findById(qualifiedId)
        .map(this::toResponse)
        .orElseThrow(() -> new IndexNotFoundException(qualifiedId));
  }

  @DELETE
  @Path("/{index}")
  @Operation(summary = "Delete an index")
  public Response delete(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    requireBucket(bucketId);
    String qualifiedId = qualify(bucketId, indexId);
    if (indexes.findById(qualifiedId).isEmpty()) {
      throw new IndexNotFoundException(qualifiedId);
    }
    indexes.delete(qualifiedId);
    return Response.noContent().build();
  }

  private void requireBucket(String bucketId) {
    if (buckets.findById(bucketId).isEmpty()) {
      throw new BucketNotFoundException(bucketId);
    }
  }

  private IndexResponse toResponse(VectorIndex index) {
    return new IndexResponse(
        unqualify(index.bucketId(), index.indexId()),
        index.bucketId(),
        index.displayName(),
        index.dimension(),
        index.metric(),
        readEngineParams(index.engineParams()),
        index.createdAt());
  }

  private String writeEngineParams(Map<String, Object> params) {
    Map<String, Object> source = params == null ? Map.of() : params;
    try {
      return objectMapper.writeValueAsString(source);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("engineParams could not be serialized", e);
    }
  }

  private Map<String, Object> readEngineParams(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, MAP_TYPE);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored engineParams could not be parsed", e);
    }
  }

  private static String qualify(String bucketId, String indexId) {
    return bucketId + "/" + indexId;
  }

  private static String unqualify(String bucketId, String qualifiedIndexId) {
    String prefix = bucketId + "/";
    return qualifiedIndexId.startsWith(prefix)
        ? qualifiedIndexId.substring(prefix.length())
        : qualifiedIndexId;
  }
}
