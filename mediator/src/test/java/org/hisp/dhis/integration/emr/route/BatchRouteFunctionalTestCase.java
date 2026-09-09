package org.hisp.dhis.integration.emr.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hisp.dhis.integration.emr.support.Dhis2ApiStub.lookupRequests;
import static org.hisp.dhis.integration.emr.support.Dhis2ApiStub.trackerRequests;
import static org.hisp.dhis.integration.emr.support.TestPayloads.BAHMNI_KEY;
import static org.hisp.dhis.integration.emr.support.TestPayloads.PULSETECH_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.hisp.dhis.integration.emr.support.AbstractIngestFunctionalTestCase;
import org.hisp.dhis.integration.emr.support.Dhis2ApiStub;
import org.hisp.dhis.integration.emr.support.TestPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The FAST functional suite for the batch endpoint, driving the REAL Bahmni reference export (a
 * flat array of 8 encounters, 5 women) against the shared {@link Dhis2ApiStub}. The envelope
 * expression folds encounters into per-woman records; each woman runs the core independently —
 * one bad record never sinks the file, and every woman gets an addressable outcome.
 */
public class BatchRouteFunctionalTestCase extends AbstractIngestFunctionalTestCase {

  private static final List<String> EXPECTED_MRNS =
      List.of("668462", "668465", "668464", "668463", "668466");

  @Test
  @SuppressWarnings("unchecked")
  void testRealExportImportsEveryWomanOnceWithPerWomanResults() throws IOException {
    ResponseEntity<String> response = pushBatch(TestPayloads.read("emr-mocks/bahmni/anc-records-batch.json"));

    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = OBJECT_MAPPER.readValue(response.getBody(), Map.class);
    Map<String, Object> summary = (Map<String, Object>) body.get("summary");
    // 8 encounters, but 5 WOMEN — the envelope folded the flat export per patientId.
    assertEquals(5, summary.get("received"));
    assertEquals(5, summary.get("imported"));

    List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
    assertEquals(List.of(0, 1, 2, 3, 4), results.stream().map(r -> r.get("index")).toList());
    assertEquals(EXPECTED_MRNS, results.stream().map(r -> r.get("mrn")).toList());
    results.forEach(r -> assertEquals("IMPORTED", r.get("status")));

    // Expressions fetched ONCE for the whole file; the core ran once per woman.
    assertEquals(1, Dhis2ApiStub.datastoreReads("envelope", "bahmni").size());
    assertEquals(1, Dhis2ApiStub.datastoreReads("mapping", "bahmni").size());
    assertEquals(5, lookupRequests().size());
    assertEquals(5, trackerRequests().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testOneInvalidWomanDoesNotSinkTheBatch() throws IOException {
    List<Map<String, Object>> export = TestPayloads.readJsonList("emr-mocks/bahmni/anc-records-batch.json");
    // Break woman 668464 (one encounter): no facilityCode anywhere -> INVALID.
    export.stream()
        .filter(e -> e.get("patientId").equals(668464))
        .forEach(e -> e.remove("facilityCode"));

    ResponseEntity<String> response = pushBatch(TestPayloads.toJson(export));

    assertEquals(207, response.getStatusCode().value());
    Map<String, Object> body = OBJECT_MAPPER.readValue(response.getBody(), Map.class);
    Map<String, Object> summary = (Map<String, Object>) body.get("summary");
    assertEquals(5, summary.get("received"));
    assertEquals(4, summary.get("imported"));
    assertEquals(1, summary.get("invalid"));

    List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
    Map<String, Object> invalid = results.get(2); // 668464 is the third woman to appear
    assertEquals("INVALID", invalid.get("status"));
    assertTrue(((List<String>) invalid.get("missingFields")).contains("facilityCode"));

    // Only the four valid women reached DHIS2.
    assertEquals(4, trackerRequests().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testDhis2RejectionsBecomeRejectedEntriesNotAFailedBatch() throws IOException {
    Dhis2ApiStub.server()
        .stubFor(
            post(urlPathEqualTo("/api/tracker"))
                .willReturn(
                    aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"httpStatusCode\":409,\"status\":\"ERROR\",\"message\":\"conflict\"}")));

    ResponseEntity<String> response = pushBatch(TestPayloads.read("emr-mocks/bahmni/anc-records-batch.json"));

    assertEquals(207, response.getStatusCode().value());
    Map<String, Object> body = OBJECT_MAPPER.readValue(response.getBody(), Map.class);
    assertEquals(5, ((Map<String, Object>) body.get("summary")).get("rejected"));
    ((List<Map<String, Object>>) body.get("results"))
        .forEach(
            r -> {
              assertEquals("REJECTED", r.get("status"));
              assertEquals(409, r.get("httpStatusCode"));
            });
  }

  @Test
  void testBadEnvelopeIsA400ForTheWholeRequest() {
    assertEquals(400, pushBatch("[]").getStatusCode().value());
    assertEquals(400, pushBatch("{\"records\":[]}").getStatusCode().value());
    assertEquals(400, pushBatch("{\"entries\":[{}]}").getStatusCode().value());
    assertEquals(0, trackerRequests().size());
  }

  @Test
  void testBatchEndpointRequiresTheSourcesOwnKey() {
    ResponseEntity<String> response =
        push("/api/bahmni/anc-records", PULSETECH_KEY, TestPayloads.read("emr-mocks/bahmni/anc-records-batch.json"));
    assertEquals(403, response.getStatusCode().value());
  }

  private ResponseEntity<String> pushBatch(String body) {
    return push("/api/bahmni/anc-records", BAHMNI_KEY, body);
  }
}
