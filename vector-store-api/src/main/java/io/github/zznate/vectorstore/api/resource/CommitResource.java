package io.github.zznate.vectorstore.api.resource;

import io.github.zznate.vectorstore.api.dto.CommitResponse;
import io.github.zznate.vectorstore.api.error.NotImplementedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Hosts the single {@code :commit} action against an index. Kept out of
 * {@link VectorsResource} because the {@code :commit} suffix is attached
 * directly to the {@code {index}} path parameter — {@code
 * /v1/indexes/{bucket}/{index}:commit} — and mixing that template with
 * sibling templates that share the same stem has caused route-matching
 * surprises in Quarkus REST. Isolating it keeps the routing deterministic.
 *
 * <p>501 in prompt 01; real implementation lands in prompt 02.
 */
@Path("/v1/indexes/{bucket}/{index}:commit")
@Tag(name = "vectors", description = "Commit action (stubbed in prompt 01)")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class CommitResource {

  @POST
  @Operation(summary = "Flush the write buffer to a new segment (deferred)")
  public CommitResponse commit(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    throw new NotImplementedException("commit", 2);
  }
}
