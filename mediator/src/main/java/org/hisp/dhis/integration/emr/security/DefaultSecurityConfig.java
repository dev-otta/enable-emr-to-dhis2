package org.hisp.dhis.integration.emr.security;

import static org.springframework.security.config.Customizer.withDefaults;

import jakarta.servlet.DispatcherType;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two filter chains: per-source API keys on the app port, an optional login on the management
 * port.
 *
 * <p>Console contract: without mediator.console.password the management port has NO login — safe
 * only because the compose files publish that port to loopback. With a password, the whole port
 * (Hawtio, Jolokia, actuator) requires a form/basic login. hawtio.authenticationEnabled stays
 * false either way: hawtio's own login would cover its UI pages but not Jolokia, which is where
 * the data comes from. Logout: /logout on the management port.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(ApiKeysProperties.class)
public class DefaultSecurityConfig {

  static final String PLACEHOLDER_KEY = "CHANGE_ME";
  static final int MIN_KEY_LENGTH = 16;
  static final int MIN_CONSOLE_PASSWORD_LENGTH = 12;

  private final ApiKeysProperties apiKeysProperties;

  @Value("${mediator.max-request-bytes:1048576}")
  private long maxRequestBytes;

  @Value("${mediator.batch.max-request-bytes:20971520}")
  private long batchMaxRequestBytes;

  // Captured at startup. The configured port can't be used in the securityMatcher:
  // it is 0 (random) in tests.
  private final AtomicInteger managementPort = new AtomicInteger(-1);

  @Value("${mediator.console.user:operator}")
  private String consoleUser;

  @Value("${mediator.console.password:}")
  private String consolePassword;

  public DefaultSecurityConfig(ApiKeysProperties apiKeysProperties) {
    this.apiKeysProperties = apiKeysProperties;
  }

  private boolean consoleLoginEnabled() {
    return !consolePassword.isBlank();
  }

  @Bean
  ApplicationListener<WebServerInitializedEvent> managementPortTracker() {
    return event -> {
      if ("management"
          .equals(
              ((WebServerApplicationContext) event.getApplicationContext()).getServerNamespace())) {
        managementPort.set(event.getWebServer().getPort());
      }
    };
  }

  @Bean
  @Order(1)
  SecurityFilterChain consoleFilterChain(HttpSecurity http) throws Exception {
    http.securityMatcher(
        request -> {
          int port = managementPort.get();
          return port > 0 && request.getLocalPort() == port;
        });
    // CSRF is covered by the same-site=strict session cookie (application.yaml).
    http.csrf(csrf -> csrf.disable());

    if (!consoleLoginEnabled()) {
      http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
      return http.build();
    }

    requireSaneConsolePassword(consoleUser, consolePassword);
    http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .formLogin(withDefaults())
        .httpBasic(withDefaults());
    return http.build();
  }

  @Bean
  UserDetailsService consoleUserDetailsService() {
    if (!consoleLoginEnabled()) {
      return new InMemoryUserDetailsManager();
    }
    String password = consolePassword.startsWith("{") ? consolePassword : "{noop}" + consolePassword;
    return new InMemoryUserDetailsManager(
        User.withUsername(consoleUser).password(password).roles("ADMIN").build());
  }

  @Bean
  @Order(2)
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    Map<String, String> enabledKeys = apiKeysProperties.enabledKeys();
    requireSaneKeys(enabledKeys);

    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> {
              // Camel platform-http re-dispatches requests asynchronously. API key
              // was already enforced on the original REQUEST dispatch.
              a.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll();
              a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
              a.requestMatchers("/api/health").permitAll();
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

  private static void requireSaneConsolePassword(String user, String password) {
    if (user.isBlank()) {
      throw new IllegalStateException(
          "Refusing to start: MEDIATOR_CONSOLE_PASSWORD is set but MEDIATOR_CONSOLE_USER is blank.");
    }
    boolean plaintext = password.startsWith("{noop}") || !password.startsWith("{");
    String effective = password.startsWith("{noop}") ? password.substring("{noop}".length()) : password;
    if (password.contains(PLACEHOLDER_KEY)
        || (plaintext && effective.length() < MIN_CONSOLE_PASSWORD_LENGTH)) {
      throw new IllegalStateException(
          "Refusing to start: the console password is set as the placeholder or a plaintext shorter "
              + "than "
              + MIN_CONSOLE_PASSWORD_LENGTH
              + " characters. Set a long password, or preferably a bcrypt hash: "
              + "htpasswd -bnBC 10 \"\" 'password' | tr -d ':\\n' "
              + "(prefix the result with {bcrypt}; escape every $ as $$ in .env).");
    }
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
