package org.hisp.dhis.integration.emr.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    properties = {"management.server.port=0"})
public class ConsoleOpenFunctionalTestCase extends AbstractIngestFunctionalTestCase {

  @LocalManagementPort private int managementPort;

  @Autowired private TestRestTemplate template;

  @Test
  void testWithoutAConfiguredPasswordTheConsoleAnswersWithoutLogin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
    ResponseEntity<String> response =
        template.exchange(
            "http://localhost:" + managementPort + "/management/jolokia/version",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
  }
}
