package org.hisp.dhis.integration.emr.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for every test that talks HTTP to the booted mediator: the real Spring Boot app (security
 * filters, Camel routes, processors) on a random port, test profile, and the one {@code push}
 * helper all suites share. What DHIS2 is — the shared WireMock or a real Testcontainers instance —
 * is the subclass hierarchy's decision ({@code AbstractIngestFunctionalTestCase} vs {@code
 * AbstractDhis2IT}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractMediatorHttpTestCase {

  protected static final ObjectMapper OBJECT_MAPPER = TestPayloads.OBJECT_MAPPER;

  @Autowired protected TestRestTemplate restTemplate;

  protected ResponseEntity<String> push(String path, String apiKey, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (apiKey != null) {
      headers.set("X-API-KEY", apiKey);
    }
    return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
  }
}
