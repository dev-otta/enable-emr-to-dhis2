package org.hisp.dhis.integration.emr;

import org.hisp.dhis.integration.sdk.Dhis2ClientBuilder;
import org.hisp.dhis.integration.sdk.api.Dhis2Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared DHIS2 client used by every dhis2: endpoint in the Camel routes (referenced as
 * {@code client: "#dhis2Client"}).
 */
@Configuration
public class Dhis2ClientConfig {

  @Bean
  public Dhis2Client dhis2Client(
      @Value("${dhis2.api-url}") String apiUrl,
      @Value("${dhis2.username}") String username,
      @Value("${dhis2.password}") String password) {
    return Dhis2ClientBuilder.newClient(apiUrl, username, password).build();
  }
}
