package org.hisp.dhis.integration.emr;

import org.hisp.dhis.integration.sdk.Dhis2ClientBuilder;
import org.hisp.dhis.integration.sdk.api.Dhis2Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared DHIS2 client used by every dhis2: endpoint in the Camel routes (referenced as
 * {@code client: "#dhis2Client"}).
 *
 * <p>The credentials have no defaults: without DHIS2_USERNAME and DHIS2_PASSWORD the mediator
 * refuses to start, rather than silently connecting as admin. Running as admin is allowed (the
 * demo stack does) but logged as a warning.
 */
@Configuration
public class Dhis2ClientConfig {

  private static final Logger LOG = LoggerFactory.getLogger(Dhis2ClientConfig.class);

  @Bean
  public Dhis2Client dhis2Client(
      @Value("${dhis2.api-url}") String apiUrl,
      @Value("${dhis2.username:}") String username,
      @Value("${dhis2.password:}") String password) {
    requireCredentials(username, password);
    if ("admin".equalsIgnoreCase(username.trim())) {
      LOG.warn(
          "DHIS2 client configured with the 'admin' account. Use a dedicated least-privilege "
              + "account outside local development.");
    }
    return Dhis2ClientBuilder.newClient(apiUrl, username, password).build();
  }

  static void requireCredentials(String username, String password) {
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalStateException(
          "Refusing to start: DHIS2 credentials not configured. Set DHIS2_USERNAME and "
              + "DHIS2_PASSWORD.");
    }
  }
}
