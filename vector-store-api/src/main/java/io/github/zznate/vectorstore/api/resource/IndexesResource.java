package io.github.zznate.vectorstore.api.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zznate.vectorstore.api.dto.CreateIndexRequest;
import io.github.zznate.vectorstore.api.dto.IndexResponse;
import io.github.zznate.vectorstore.api.error.BucketNotFoundException;
import io.github.zznate.vectorstore.api.error.IndexAlreadyActiveException;
import io.github.zznate.vectorstore.api.error.IndexAlreadyExistsException;
import io.github.zznate.vectorstore.api.error.IndexInRetentionException;
import io.github.zznate.vectorstore.api.error.IndexNotFoundException;
import io.github.zznate.vectorstore.core.cache.CachePolicyResolver;
import io.github.zznate.vectorstore.core.catalog.manifest.ManifestCache;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParams;
import io.github.zznate.vectorstore.core.catalog.model.IndexBuildParamsDefaults;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.BucketRepository;
import io.github.zznate.vectorstore.core.catalog.repository.StagedTombstoneRepository;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.engine.buffer.WriteBuffer;
import io.github.zznate.vectorstore.engine.commit.CommitCoordinator;
import io.github.zznate.vectorstore.engine.search.CachePolicyEnforcer;
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
  private final StagedTombstoneRepository stagedTombstones;
  private final ManifestCache manifests;
  private final CachePolicyResolver cachePolicyResolver;
  private final CachePolicyEnforcer cachePolicyEnforcer;
  private final WriteBuffer writeBuffer;
  private final CommitCoordinator commitCoordinator;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final IndexBuildParamsDefaults paramsDefaults;

  @SuppressWarnings("PMD.ExcessiveParameterList")
  @Inject
  public IndexesResource(
      BucketRepository buckets,
      VectorIndexRepository indexes,
      StagedTombstoneRepository stagedTombstones,
      ManifestCache manifests,
      CachePolicyResolver cachePolicyResolver,
      CachePolicyEnforcer cachePolicyEnforcer,
      WriteBuffer writeBuffer,
      CommitCoordinator commitCoordinator,
      Clock clock,
      ObjectMapper objectMapper,
      IndexBuildParamsDefaults paramsDefaults) {
    this.buckets = buckets;
    this.indexes = indexes;
    this.stagedTombstones = stagedTombstones;
    this.manifests = manifests;
    this.cachePolicyResolver = cachePolicyResolver;
    this.cachePolicyEnforcer = cachePolicyEnforcer;
    this.writeBuffer = writeBuffer;
    this.commitCoordinator = commitCoordinator;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.paramsDefaults = paramsDefaults;
  }

  @POST
  @Operation(summary = "Create an index in a bucket")
  public Response create(
      @PathParam("bucket") String bucketId, @Valid CreateIndexRequest request) {
    requireBucket(bucketId);
    String qualifiedId = qualify(bucketId, request.indexId());
    indexes
        .findIncludingDeleted(qualifiedId)
        .ifPresent(
            existing -> {
              if (existing.isDeleted()) {
                throw new IndexInRetentionException(qualifiedId, existing.deletedAt());
              }
              throw new IndexAlreadyExistsException(qualifiedId);
            });
    VectorIndex index =
        indexes.create(
            VectorIndex.active(
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

  /**
   * Soft-deletes an index. The catalog row stays with {@code deleted_at}
   * set; segments, manifest_versions, and object-store data remain
   * untouched until the retention sweep hard-deletes the index after the
   * configured window has elapsed.
   *
   * <p>All index-keyed caches (manifest, cache-policy resolver / enforcer,
   * write buffer, commit coordinator) are invalidated immediately so the
   * soft-deleted index is unreachable through any cached path. Pending
   * writes in the buffer and any in-flight commit state for this index
   * are dropped.
   */
  @DELETE
  @Path("/{index}")
  @Operation(summary = "Soft-delete an index")
  public Response delete(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    requireBucket(bucketId);
    String qualifiedId = qualify(bucketId, indexId);
    if (indexes.findById(qualifiedId).isEmpty()) {
      throw new IndexNotFoundException(qualifiedId);
    }
    indexes.softDelete(qualifiedId, clock.instant());
    stagedTombstones.clearForIndex(qualifiedId);
    manifests.invalidateIndex(qualifiedId);
    cachePolicyResolver.invalidate(qualifiedId);
    cachePolicyEnforcer.invalidateIndex(qualifiedId);
    writeBuffer.invalidateIndex(qualifiedId);
    commitCoordinator.invalidateIndex(qualifiedId);
    return Response.noContent().build();
  }

  /**
   * Restores a soft-deleted index by clearing its {@code deleted_at}.
   * Cascades upward: if the parent bucket is also soft-deleted, its
   * {@code deleted_at} is cleared too — otherwise the restored index
   * would point at a tombstoned bucket and {@code requireBucket} on
   * subsequent operations would 404.
   *
   * <p>Bucket restore alone never cascades downward; index restore
   * always cascades upward. This asymmetry mirrors the soft-delete
   * cascade rules on the way down (each child is hidden independently)
   * and on the way up (you cannot have a live child of a tombstoned
   * parent).
   *
   * <p>404 if the index id never existed or has been hard-deleted past
   * retention. 409 if the index is already active.
   */
  @POST
  @Path("/{index}:restore")
  @Consumes(MediaType.WILDCARD)
  @Operation(summary = "Restore a soft-deleted index (cascades upward to bucket)")
  public IndexResponse restore(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    String qualifiedId = qualify(bucketId, indexId);
    VectorIndex existing =
        indexes
            .findIncludingDeleted(qualifiedId)
            .orElseThrow(() -> new IndexNotFoundException(qualifiedId));
    if (!existing.isDeleted()) {
      throw new IndexAlreadyActiveException(qualifiedId);
    }
    // Cascade upward if the parent bucket is also soft-deleted.
    buckets
        .findIncludingDeleted(bucketId)
        .ifPresent(
            parent -> {
              if (parent.isDeleted()) {
                buckets.restore(bucketId);
              }
            });
    indexes.restore(qualifiedId);
    return indexes
        .findById(qualifiedId)
        .map(this::toResponse)
        .orElseThrow(() -> new IndexNotFoundException(qualifiedId));
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

  private String writeEngineParams(Map<String, Object> userOverrides) {
    // Merge user input over the per-process config defaults and persist
    // the canonical typed form. Storing the merged result means every
    // later read gets the full parameter set without re-applying defaults
    // — and changing process-level defaults later does not retroactively
    // shift already-created indexes.
    return IndexBuildParams.fromOverrides(userOverrides, IndexBuildParams.defaults(paramsDefaults))
        .toJson();
  }

  private Map<String, Object> readEngineParams(String json) {
    return IndexBuildParams.fromJson(json).toMap();
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
