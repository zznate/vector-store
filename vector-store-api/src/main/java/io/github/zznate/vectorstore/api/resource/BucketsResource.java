package io.github.zznate.vectorstore.api.resource;

import io.github.zznate.vectorstore.api.dto.BucketResponse;
import io.github.zznate.vectorstore.api.dto.CreateBucketRequest;
import io.github.zznate.vectorstore.api.error.BucketAlreadyExistsException;
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
@Tag(name = "buckets", description = "Bucket lifecycle")
@ApplicationScoped
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
    if (buckets.findById(request.bucketId()).isPresent()) {
      throw new BucketAlreadyExistsException(request.bucketId());
    }
    Bucket created =
        buckets.create(new Bucket(request.bucketId(), request.displayName(), clock.instant()));
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

  @DELETE
  @Path("/{bucket}")
  @Operation(summary = "Delete a bucket (must be empty)")
  public Response delete(@PathParam("bucket") String bucketId) {
    if (buckets.findById(bucketId).isEmpty()) {
      throw new BucketNotFoundException(bucketId);
    }
    if (!indexes.listByBucket(bucketId).isEmpty()) {
      throw new BucketNotEmptyException(bucketId);
    }
    buckets.delete(bucketId);
    return Response.noContent().build();
  }
}
