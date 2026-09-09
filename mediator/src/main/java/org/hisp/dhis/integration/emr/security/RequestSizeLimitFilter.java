package org.hisp.dhis.integration.emr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of inbound requests before anything else runs. Two limits: single-record
 * endpoints get the tight default (an ANC record is a few kilobytes; default 1 MiB,
 * {@code MEDIATOR_MAX_REQUEST_BYTES}); the batch endpoint ({@code .../anc-records}) gets the
 * batch cap (default 20 MiB, {@code MEDIATOR_BATCH_MAX_REQUEST_BYTES}). Declared Content-Length
 * above the limit → 413 immediately; requests without a declared length (chunked) are
 * additionally swallowed-capped by the servlet container.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

  static final String BATCH_PATH_SUFFIX = "/anc-records";

  private final long maxRequestBytes;
  private final long batchMaxRequestBytes;

  public RequestSizeLimitFilter(long maxRequestBytes, long batchMaxRequestBytes) {
    this.maxRequestBytes = maxRequestBytes;
    this.batchMaxRequestBytes = batchMaxRequestBytes;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long limit =
        request.getRequestURI().endsWith(BATCH_PATH_SUFFIX) ? batchMaxRequestBytes : maxRequestBytes;
    long declaredLength = request.getContentLengthLong();
    if (declaredLength > limit) {
      response.setStatus(413); // Payload Too Large
      response.setContentType("application/json");
      response
          .getWriter()
          .write(
              "{\"status\":\"ERROR\",\"error\":\"Request too large\",\"maxRequestBytes\":"
                  + limit
                  + "}");
      return;
    }
    filterChain.doFilter(request, response);
  }
}
