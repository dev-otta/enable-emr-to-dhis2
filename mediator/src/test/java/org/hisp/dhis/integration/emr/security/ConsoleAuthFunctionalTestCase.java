package org.hisp.dhis.integration.emr.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.hisp.dhis.integration.emr.support.AbstractIngestFunctionalTestCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "mediator.console.user=ops",
      "mediator.console.password={noop}console-test-password"
    })
public class ConsoleAuthFunctionalTestCase extends AbstractIngestFunctionalTestCase {

  @LocalManagementPort private int managementPort;

  @Autowired private TestRestTemplate template;

  private ResponseEntity<String> getJolokiaVersion(TestRestTemplate client) {
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    return client.exchange(
        "http://localhost:" + managementPort + "/management/jolokia/version",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);
  }

  @Test
  void testTheConsoleRequiresALogin() {
    ResponseEntity<String> response = getJolokiaVersion(template);
    assertEquals(401, response.getStatusCode().value(), String.valueOf(response.getBody()));
  }

  @Test
  void testWrongCredentialsAreRejected() {
    ResponseEntity<String> response =
        getJolokiaVersion(template.withBasicAuth("ops", "not-the-password"));
    assertEquals(401, response.getStatusCode().value(), String.valueOf(response.getBody()));
  }

  @Test
  void testCorrectCredentialsReachTheConsole() {
    ResponseEntity<String> response =
        getJolokiaVersion(template.withBasicAuth("ops", "console-test-password"));
    assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
    assertTrue(response.getBody().contains("value"), response.getBody());
  }

  @Test
  void testTheEmrApiOnTheMainPortIsUntouchedByTheConsoleChain() {
    assertEquals(200, restTemplate.getForEntity("/api/health", String.class).getStatusCode().value());
    assertEquals(
        403,
        restTemplate
            .postForEntity("/api/pulsetech/anc-record", new HttpEntity<>("{}"), String.class)
            .getStatusCode()
            .value());
  }
}
