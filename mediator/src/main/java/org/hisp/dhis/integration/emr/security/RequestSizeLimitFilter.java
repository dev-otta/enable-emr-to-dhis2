package org.hisp.dhis.integration.emr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of inbound requests. Two limits: single-record endpoints get the tight default (an
 * ANC record is a few kilobytes; default 1 MiB, {@code MEDIATOR_MAX_REQUEST_BYTES}); the batch
 * endpoint ({@code .../anc-records}) gets the batch cap (default 20 MiB, {@code
 * MEDIATOR_BATCH_MAX_REQUEST_BYTES}).
 *
 * <ul>
 *   <li>Declared Content-Length above the limit → 413 immediately, body never read.
 *   <li>No declared length (chunked transfer encoding) → the body is read here, at most limit + 1
 *       bytes. Above the limit → 413; otherwise the request continues with the buffered body. The
 *       mediator parses the whole body into memory anyway, so buffering adds no extra peak.
 * </ul>
 *
 * <p>Runs after {@link ApiKeyAuthenticationFilter}: only a request with a valid site key is ever
 * buffered (an unknown key is already rejected with 401). A chunked request WITHOUT a key is not
 * buffered; Spring Security denies it, and as a safety net its stream fails once it passes the
 * limit, so nothing downstream can read an unbounded body.
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
      rejectTooLarge(response, limit);
      return;
    }
    if (declaredLength >= 0) {
      // A declared length is enforced by the container: it never delivers more than that.
      filterChain.doFilter(request, response);
      return;
    }

    if (!isAuthenticated()) {
      filterChain.doFilter(new BufferedBodyRequest(request, null, limit), response);
      return;
    }
    byte[] body = readAtMost(request.getInputStream(), limit + 1);
    if (body.length > limit) {
      rejectTooLarge(response, limit);
      return;
    }
    filterChain.doFilter(new BufferedBodyRequest(request, body, limit), response);
  }

  private static boolean isAuthenticated() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication instanceof ApiKeyAuthentication && authentication.isAuthenticated();
  }

  private static byte[] readAtMost(InputStream in, long max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while (total < max
        && (read = in.read(buffer, 0, (int) Math.min(buffer.length, max - total))) != -1) {
      out.write(buffer, 0, read);
      total += read;
    }
    return out.toByteArray();
  }

  private static void rejectTooLarge(HttpServletResponse response, long limit) throws IOException {
    response.setStatus(413); // Payload Too Large
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            "{\"status\":\"ERROR\",\"error\":\"Request too large\",\"maxRequestBytes\":"
                + limit
                + "}");
  }

  /**
   * Serves a body that was buffered here, or (body == null) the original stream capped at the
   * limit: reading past it throws instead of returning more data.
   */
  private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final long limit;

    BufferedBodyRequest(HttpServletRequest request, byte[] body, long limit) {
      super(request);
      this.body = body;
      this.limit = limit;
    }

    @Override
    public int getContentLength() {
      return body != null ? body.length : super.getContentLength();
    }

    @Override
    public long getContentLengthLong() {
      return body != null ? body.length : super.getContentLengthLong();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      InputStream source =
          body != null ? new ByteArrayInputStream(body) : super.getInputStream();
      return new LimitedServletInputStream(source, body != null ? Long.MAX_VALUE : limit);
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class LimitedServletInputStream extends ServletInputStream {

    private final InputStream source;
    private final long limit;
    private long count;
    private boolean finished;

    LimitedServletInputStream(InputStream source, long limit) {
      this.source = source;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int b = source.read();
      if (b == -1) {
        finished = true;
      } else {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = source.read(b, off, len);
      if (n == -1) {
        finished = true;
      } else {
        count(n);
      }
      return n;
    }

    private void count(int n) throws IOException {
      count += n;
      if (count > limit) {
        throw new IOException("Request body exceeds the limit of " + limit + " bytes");
      }
    }

    @Override
    public boolean isFinished() {
      return finished;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      throw new UnsupportedOperationException("Asynchronous reads are not supported");
    }
  }
}
