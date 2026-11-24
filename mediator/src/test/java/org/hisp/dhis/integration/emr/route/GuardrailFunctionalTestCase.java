package org.hisp.dhis.integration.emr.route;

import static org.hisp.dhis.integration.emr.support.TestPayloads.BAHMNI_KEY;
import static org.hisp.dhis.integration.emr.support.TestPayloads.PULSETECH_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.hisp.dhis.integration.emr.support.AbstractIngestFunctionalTestCase;
import org.hisp.dhis.integration.emr.support.TestPayloads;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for the ingest guardrails, with the limits configured LOW so they trip:
 * request-size cap (413, before anything else runs, also for chunked bodies that declare no
 * length), the batch entry cap (400) and the throttle (429). Own application context because of the tightened limits; DHIS2 is the same shared stub.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mediator.max-request-bytes=2048",
      "mediator.throttle.max-requests=2",
      "mediator.throttle.period-millis=60000",
      "mediator.batch.max-records=2"
    })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GuardrailFunctionalTestCase extends AbstractIngestFunctionalTestCase {

  @LocalServerPort private int port;

  @Test
  @Order(1)
  void testOversizedRequestIsRejectedWith413BeforeAnythingRuns() {
    // Rejected by the size filter — servlet-level, so it consumes no throttle
    // budget and never reaches parsing, validation, or DHIS2.
    String oversized = "{\"pnt_mrn\":\"" + "9".repeat(4096) + "\"}";
    ResponseEntity<String> response = push("/api/pulsetech/anc-record", PULSETECH_KEY, oversized);
    assertEquals(413, response.getStatusCode().value());
    assertTrue(response.getBody().contains("Request too large"));
  }

  @Test
  @Order(2)
  void testOversizedChunkedRequestIsRejectedWith413() throws Exception {
    // A chunked body declares no Content-Length, so only reading it can enforce the cap.
    String oversized = "{\"pnt_mrn\":\"" + "9".repeat(4096) + "\"}";
    HttpResponse<String> response = pushChunked("/api/pulsetech/anc-record", PULSETECH_KEY, oversized);
    assertEquals(413, response.statusCode());
    assertTrue(response.body().contains("Request too large"));
  }

  @Test
  @Order(3)
  void testChunkedRequestWithinTheLimitIsProcessed() throws Exception {
    // The buffered body must reach the routes intact: the envelope gate parses it and
    // applies the entry cap (2), which proves the JSON arrived whole. The gate runs
    // before the batch throttle, so this consumes no throttle budget.
    String batch = TestPayloads.read("emr-mocks/bahmni/anc-records-batch.json");
    HttpResponse<String> response = pushChunked("/api/bahmni/anc-records", BAHMNI_KEY, batch);
    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("maximum of 2"));
  }

  @Test
  @Order(4)
  void testChunkedRequestWithoutKeyIsDeniedWithoutBuffering() throws Exception {
    String oversized = "{\"pnt_mrn\":\"" + "9".repeat(4096) + "\"}";
    HttpResponse<String> response = pushChunked("/api/pulsetech/anc-record", null, oversized);
    assertEquals(403, response.statusCode());
  }

  @Test
  @Order(5)
  void testBatchBeyondTheEntryCapIsRejectedWith400() {
    // The envelope gate runs before the batch throttle, before the envelope
    // expression, and before any entry is processed — an oversized file is
    // refused whole (counting RAW input entries), with the cap in the message.
    String batch = TestPayloads.read("emr-mocks/bahmni/anc-records-batch.json"); // 8 encounters, cap is 2
    ResponseEntity<String> response = push("/api/bahmni/anc-records", BAHMNI_KEY, batch);
    assertEquals(400, response.getStatusCode().value());
    assertTrue(response.getBody().contains("maximum of 2"));
  }

  @Test
  @Order(6)
  void testBurstBeyondTheThrottleIsRejectedWith429() {
    String record = TestPayloads.read("emr-mocks/pulsetech/anc-record.json");
    assertEquals(200, push("/api/pulsetech/anc-record", PULSETECH_KEY, record).getStatusCode().value());
    assertEquals(200, push("/api/pulsetech/anc-record", PULSETECH_KEY, record).getStatusCode().value());

    ResponseEntity<String> third = push("/api/pulsetech/anc-record", PULSETECH_KEY, record);
    assertEquals(429, third.getStatusCode().value());
    assertTrue(third.getBody().contains("Too many requests"));
  }

  /** POSTs with chunked transfer encoding: the body comes from a stream of unknown length. */
  private HttpResponse<String> pushChunked(String path, String apiKey, String body)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    if (apiKey != null) {
      request.header("X-API-KEY", apiKey);
    }
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build()
        .send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
