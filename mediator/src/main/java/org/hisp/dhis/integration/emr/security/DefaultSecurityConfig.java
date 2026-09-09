package org.hisp.dhis.integration.emr.security;

import jakarta.servlet.DispatcherType;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Locks down the mediator. Adapted from dhis2/reference-civil-registry-lookup's
 * DefaultSecurityConfig, extended to one API key per EMR site (the IG's transport rule) — and the
 * authorization rules are BUILT FROM CONFIGURATION: for every enabled source in {@link
 * ApiKeysProperties}, {@code /api/<source>/**} requires that source's role. No source names are
 * hardcoded here; enabling or disabling a pipeline is purely an environment change.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>requests above the size cap are rejected first (413)
 *   <li>/api/health is public (so health checks work without a key)
 *   <li>/api/&lt;source&gt;/** requires that source's site key — for enabled sources only; a
 *       disabled or unknown source's path falls through to denyAll
 *   <li>everything else is denied
 * </ul>
 *
 * <p>Fail-fast at startup — the application refuses to run when: no source is enabled at all; any
 * configured key is the placeholder or shorter than {@value #MIN_KEY_LENGTH} characters; or two
 * sources share a key (a key must identify exactly one site so it can be rotated and revoked
 * independently). A copied-as-is deployment can therefore never run wide open.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiKeysProperties.class)
public class DefaultSecurityConfig {

  static final String PLACEHOLDER_KEY = "CHANGE_ME";
  static final int MIN_KEY_LENGTH = 16;

  private final ApiKeysProperties apiKeysProperties;

  @Value("${mediator.max-request-bytes:1048576}")
  private long maxRequestBytes;

  @Value("${mediator.batch.max-request-bytes:20971520}")
  private long batchMaxRequestBytes;

  public DefaultSecurityConfig(ApiKeysProperties apiKeysProperties) {
    this.apiKeysProperties = apiKeysProperties;
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    Map<String, String> enabledKeys = apiKeysProperties.enabledKeys();
    requireSaneKeys(enabledKeys);

    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> {
              // Camel's platform-http processes the request asynchronously and re-dispatches
              // it. The original REQUEST dispatch already enforced the API key; the ASYNC
              // re-dispatch must be permitted or Spring Security re-evaluates it as anonymous
              // and returns 403.
              a.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll();
              // The ERROR dispatch renders Boot's /error page for anything that escaped
              // Camel's handlers. Denying it turns every internal failure into an opaque
              // 403 for the caller — permit it so a real 500 (with its JSON body) surfaces.
              a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
              a.requestMatchers("/api/health").permitAll();
              // One rule per ENABLED source, straight from configuration.
              enabledKeys
                  .keySet()
                  .forEach(
                      source ->
                          a.requestMatchers("/api/" + source + "/**")
                              .hasRole(source.toUpperCase(Locale.ROOT)));
              a.anyRequest().denyAll();
            })
        .addFilterBefore(
            new ApiKeyAuthenticationFilter(enabledKeys), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new RequestSizeLimitFilter(maxRequestBytes, batchMaxRequestBytes),
            ApiKeyAuthenticationFilter.class);

    return http.build();
  }

  private static void requireSaneKeys(Map<String, String> enabledKeys) {
    if (enabledKeys.isEmpty()) {
      throw new IllegalStateException(
          "Refusing to start: no EMR source is enabled. Configure at least one site API key "
              + "(e.g. MEDIATOR_API_KEY_PULSETECH in .env; generate with `openssl rand -hex 32`). "
              + "This guardrail prevents the mediator from running with nothing to protect "
              + "— or, worse, with endpoints someone later opens by accident.");
    }
    enabledKeys.forEach(
        (source, key) -> {
          if (key.equals(PLACEHOLDER_KEY) || key.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                "Refusing to start: the API key for source '"
                    + source
                    + "' is the placeholder or shorter than "
                    + MIN_KEY_LENGTH
                    + " characters. Set a strong secret (`openssl rand -hex 32`).");
          }
        });
    Set<String> distinct = new HashSet<>(enabledKeys.values());
    if (distinct.size() != enabledKeys.size()) {
      throw new IllegalStateException(
          "Refusing to start: two EMR sources share the same API key. Each site must have its "
              + "own key (one key per site) so a key identifies exactly one source and can be "
              + "rotated/revoked independently.");
    }
  }
}
