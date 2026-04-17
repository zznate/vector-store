package io.github.zznate.vectorstore.api.error;

import io.github.zznate.vectorstore.api.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class VectorStoreExceptionMapper implements ExceptionMapper<VectorStoreException> {

  @Override
  public Response toResponse(VectorStoreException exception) {
    return Response.status(exception.status())
        .type(MediaType.APPLICATION_JSON)
        .entity(new ErrorResponse(exception.errorCode(), exception.getMessage()))
        .build();
  }
}
