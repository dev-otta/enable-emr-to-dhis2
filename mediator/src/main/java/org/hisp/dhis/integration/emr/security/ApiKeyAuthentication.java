package org.hisp.dhis.integration.emr.security;

import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * Authentication token representing an EMR that presented a valid API key. The principal is the
 * EMR source name ("pulsetech" or "bahmni"), never the key itself.
 *
 * <p>Adapted from dhis2/reference-civil-registry-lookup.
 */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {
  private final String source;

  public ApiKeyAuthentication(String source, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.source = source;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Object getPrincipal() {
    return source;
  }
}
