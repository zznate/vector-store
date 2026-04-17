package io.github.zznate.vectorstore.api.resource;

import io.github.zznate.vectorstore.api.dto.DeleteVectorsRequest;
import io.github.zznate.vectorstore.api.dto.PutVectorsRequest;
import io.github.zznate.vectorstore.api.dto.QueryRequest;
import io.github.zznate.vectorstore.api.dto.QueryResponse;
import io.github.zznate.vectorstore.api.dto.StatsResponse;
import io.github.zznate.vectorstore.api.dto.VectorResponse;
import io.github.zznate.vectorstore.api.error.NotImplementedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * All vector-level operations for an index. Every endpoint in this resource
 * is a 501 stub in prompt 01; the real implementations land in later prompts.
 * The URL template carries the fully-qualified index reference as two path
 * segments: {@code /v1/indexes/{bucket}/{index}}.
 */
@Path("/v1/indexes/{bucket}/{index}")
@Tag(name = "vectors", description = "Vector-level operations (stubbed in prompt 01)")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VectorsResource {

  @POST
  @Path("/vectors:put")
  @Operation(summary = "Upsert vectors into the write buffer (deferred)")
  public VectorResponse putVectors(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @Valid PutVectorsRequest request) {
    throw new NotImplementedException("vectors:put", 2);
  }

  @POST
  @Path("/vectors:query")
  @Operation(summary = "kNN query with optional filter (deferred)")
  public QueryResponse queryVectors(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @Valid QueryRequest request) {
    throw new NotImplementedException("vectors:query", 2);
  }

  @POST
  @Path("/vectors:delete")
  @Operation(summary = "Tombstone vectors (deferred)")
  public VectorResponse deleteVectors(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @Valid DeleteVectorsRequest request) {
    throw new NotImplementedException("vectors:delete", 4);
  }

  @GET
  @Path("/vectors/{id}")
  @Operation(summary = "Get a specific vector with attributes (deferred)")
  public VectorResponse getVector(
      @PathParam("bucket") String bucketId,
      @PathParam("index") String indexId,
      @PathParam("id") String id) {
    throw new NotImplementedException("vectors:get", 2);
  }

  @GET
  @Path("/stats")
  @Operation(summary = "Stats for an index (deferred)")
  public StatsResponse stats(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    throw new NotImplementedException("stats", 2);
  }
}
