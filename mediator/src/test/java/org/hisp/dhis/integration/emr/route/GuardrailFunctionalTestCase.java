package org.hisp.dhis.integration.emr.route;

import static org.hisp.dhis.integration.emr.support.TestPayloads.BAHMNI_KEY;
import static org.hisp.dhis.integration.emr.support.TestPayloads.PULSETECH_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hisp.dhis.integration.emr.support.AbstractIngestFunctionalTestCase;
import org.hisp.dhis.integration.emr.support.TestPayloads;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for the ingest guardrails, with the limits configured LOW so they trip:
 * request-size cap (413, before anything else runs), the batch entry cap (400) and the throttle
 * (429). Own application context because of the tightened limits; DHIS2 is the same shared stub.
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
  @Order(3)
  void testBurstBeyondTheThrottleIsRejectedWith429() {
    String record = TestPayloads.read("emr-mocks/pulsetech/anc-record.json");
    assertEquals(200, push("/api/pulsetech/anc-record", PULSETECH_KEY, record).getStatusCode().value());
    assertEquals(200, push("/api/pulsetech/anc-record", PULSETECH_KEY, record).getStatusCode().value());

    ResponseEntity<String> third = push("/api/pulsetech/anc-record", PULSETECH_KEY, record);
    assertEquals(429, third.getStatusCode().value());
    assertTrue(third.getBody().contains("Too many requests"));
  }
}
