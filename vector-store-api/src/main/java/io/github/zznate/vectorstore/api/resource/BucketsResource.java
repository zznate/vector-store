package io.github.zznate.vectorstore.api.resource;

import io.github.zznate.vectorstore.api.auth.AdminOnly;
import io.github.zznate.vectorstore.api.dto.BucketResponse;
import io.github.zznate.vectorstore.api.dto.CreateBucketRequest;
import io.github.zznate.vectorstore.api.error.BucketAlreadyActiveException;
import io.github.zznate.vectorstore.api.error.BucketAlreadyExistsException;
import io.github.zznate.vectorstore.api.error.BucketInRetentionException;
import io.github.zznate.vectorstore.api.error.BucketNotEmptyException;
import io.github.zznate.vectorstore.api.error.BucketNotFoundException;
import io.github.zznate.vectorstore.core.catalog.model.Bucket;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/v1/buckets")
@Tag(name = "buckets", description = "Bucket lifecycle (admin-only)")
@ApplicationScoped
@AdminOnly
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BucketsResource {

  private final BucketRepository buckets;
  private final VectorIndexRepository indexes;
  private final Clock clock;

  @Inject
  public BucketsResource(BucketRepository buckets, VectorIndexRepository indexes, Clock clock) {
    this.buckets = buckets;
    this.indexes = indexes;
    this.clock = clock;
  }

  @POST
  @Operation(summary = "Create a bucket")
  public Response create(@Valid CreateBucketRequest request) {
    buckets
        .findIncludingDeleted(request.bucketId())
        .ifPresent(
            existing -> {
              if (existing.isDeleted()) {
                throw new BucketInRetentionException(request.bucketId(), existing.deletedAt());
              }
              throw new BucketAlreadyExistsException(request.bucketId());
            });
    Bucket created =
        buckets.create(Bucket.active(request.bucketId(), request.displayName(), clock.instant()));
    return Response.status(Response.Status.CREATED).entity(BucketResponse.from(created)).build();
  }

  @GET
  @Operation(summary = "List buckets")
  public List<BucketResponse> list() {
    return buckets.list().stream().map(BucketResponse::from).toList();
  }

  @GET
  @Path("/{bucket}")
  @Operation(summary = "Get a bucket by id")
  public BucketResponse get(@PathParam("bucket") String bucketId) {
    return buckets
        .findById(bucketId)
        .map(BucketResponse::from)
        .orElseThrow(() -> new BucketNotFoundException(bucketId));
  }

  /**
   * Soft-deletes a bucket. The row stays in the catalog with {@code
   * deleted_at} set; the retention sweep hard-deletes it once the configured
   * window has elapsed and no child indexes remain.
   *
   * <p>Refuses if the bucket has active indexes — children must be
   * soft-deleted first. The empty-check is deliberately scoped to
   * <em>active</em> indexes (via {@link
   * io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository#listByBucket
   * listByBucket}, which filters {@code deleted_at IS NULL}). Already
   * soft-deleted indexes do not block bucket soft-delete; the sweep
   * coordinates the eventual hard-delete ordering separately.
   */
  @DELETE
  @Path("/{bucket}")
  @Operation(summary = "Soft-delete a bucket (must have no active indexes)")
  public Response delete(@PathParam("bucket") String bucketId) {
    if (buckets.findById(bucketId).isEmpty()) {
      throw new BucketNotFoundException(bucketId);
    }
    if (!indexes.listByBucket(bucketId).isEmpty()) {
      throw new BucketNotEmptyException(bucketId);
    }
    buckets.softDelete(bucketId, clock.instant());
    return Response.noContent().build();
  }

  /**
   * Restores a soft-deleted bucket by clearing its {@code deleted_at}.
   * Returns the restored bucket with 200 OK.
   *
   * <p>Bucket restore does <em>not</em> cascade down to child indexes —
   * each soft-deleted index must be restored explicitly. Restoring a
   * bucket whose indexes are still soft-deleted is fine; the bucket
   * becomes addressable again, and the indexes remain hidden until
   * their own restore endpoint is invoked.
   *
   * <p>404 if the bucket id never existed or has been hard-deleted past
   * retention (deliberately conflated so retention windows do not leak
   * to clients). 409 if the bucket is already active.
   */
  @POST
  @Path("/{bucket}:restore")
  @Consumes(MediaType.WILDCARD)
  @Operation(summary = "Restore a soft-deleted bucket")
  public BucketResponse restore(@PathParam("bucket") String bucketId) {
    Bucket existing =
        buckets.findIncludingDeleted(bucketId).orElseThrow(() -> new BucketNotFoundException(bucketId));
    if (!existing.isDeleted()) {
      throw new BucketAlreadyActiveException(bucketId);
    }
    buckets.restore(bucketId);
    return buckets
        .findById(bucketId)
        .map(BucketResponse::from)
        .orElseThrow(() -> new BucketNotFoundException(bucketId));
  }
}
