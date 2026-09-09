package org.hisp.dhis.integration.emr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Checks the X-API-KEY header on incoming requests. One key per EMR site (the IG's transport
 * rule): the key identifies WHICH EMR is pushing, and grants only that EMR's role — so the
 * PulseTech key cannot post to the Bahmni endpoint and vice versa.
 *
 * <p>A request with no key is left unauthenticated (rejected by the authorization rules unless the
 * path is public, e.g. /api/health). A request with an unknown key is rejected outright.
 *
 * <p>Adapted from dhis2/reference-civil-registry-lookup (extended from one key to one key per
 * source).
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
  private static final String API_KEY_HEADER_NAME = "X-API-KEY";

  /** source name (e.g. "pulsetech") -> that site's API key. */
  private final Map<String, String> keysBySource;

  public ApiKeyAuthenticationFilter(Map<String, String> keysBySource) {
    this.keysBySource = keysBySource;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String apiKey = request.getHeader(API_KEY_HEADER_NAME);
    if (apiKey != null) {
      String source =
          keysBySource.entrySet().stream()
              .filter(e -> constantTimeEquals(e.getValue(), apiKey))
              .map(Map.Entry::getKey)
              .findFirst()
              .orElse(null);

      if (source == null) {
        // Unknown key: reject outright with a clean 401 (never a stack trace).
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"ERROR\",\"error\":\"Bad API key\"}");
        return;
      }

      Authentication authentication =
          new ApiKeyAuthentication(
              source,
              AuthorityUtils.createAuthorityList("ROLE_" + source.toUpperCase(java.util.Locale.ROOT)));
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    filterChain.doFilter(request, response);
  }

  private static boolean constantTimeEquals(String expected, String presented) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }
}
