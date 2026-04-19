package io.github.zznate.vectorstore.api.resource;

import io.github.zznate.vectorstore.api.dto.CommitResponse;
import io.github.zznate.vectorstore.api.error.CommitFailedHttpException;
import io.github.zznate.vectorstore.api.error.EmptyCommitHttpException;
import io.github.zznate.vectorstore.api.error.IndexNotFoundException;
import io.github.zznate.vectorstore.core.catalog.model.VectorIndex;
import io.github.zznate.vectorstore.core.catalog.repository.VectorIndexRepository;
import io.github.zznate.vectorstore.engine.commit.CommitCoordinator;
import io.github.zznate.vectorstore.engine.commit.CommitFailedException;
import io.github.zznate.vectorstore.engine.commit.CommitOutcome;
import io.github.zznate.vectorstore.engine.commit.EmptyCommitException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Hosts the {@code :commit} action. See {@link VectorsResource} for the
 * routing reason this resource exists as its own class.
 */
@Path("/v1/indexes/{bucket}/{index}:commit")
@Tag(name = "vectors", description = "Commit action")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class CommitResource {

  private final VectorIndexRepository indexes;
  private final CommitCoordinator commitCoordinator;

  @Inject
  public CommitResource(
      VectorIndexRepository indexes, CommitCoordinator commitCoordinator) {
    this.indexes = indexes;
    this.commitCoordinator = commitCoordinator;
  }

  @POST
  @Operation(summary = "Flush the write buffer to a new segment and append a manifest version")
  public CommitResponse commit(
      @PathParam("bucket") String bucketId, @PathParam("index") String indexId) {
    String qualified = bucketId + "/" + indexId;
    VectorIndex index =
        indexes.findById(qualified).orElseThrow(() -> new IndexNotFoundException(qualified));
    try {
      CommitOutcome outcome = commitCoordinator.commit(index);
      return new CommitResponse(
          outcome.segmentId(),
          outcome.vectorCount(),
          outcome.bytes(),
          outcome.manifestVersion(),
          outcome.committedAt());
    } catch (EmptyCommitException e) {
      throw new EmptyCommitHttpException(qualified);
    } catch (CommitFailedException e) {
      throw new CommitFailedHttpException(qualified, e.phase(), e.getCause());
    }
  }
}
