package org.hisp.dhis.integration.emr.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.hisp.dhis.integration.emr.support.Dhis2ApiStub.lookupRequests;
import static org.hisp.dhis.integration.emr.support.Dhis2ApiStub.trackerRequests;
import static org.hisp.dhis.integration.emr.support.TestPayloads.BAHMNI_KEY;
import static org.hisp.dhis.integration.emr.support.TestPayloads.PULSETECH_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.hisp.dhis.integration.emr.support.AbstractIngestFunctionalTestCase;
import org.hisp.dhis.integration.emr.support.Dhis2ApiStub;
import org.hisp.dhis.integration.emr.support.TestPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The FAST functional suite for the single-record pipeline: the real app driving the shipped
 * datastore expressions, with the shared {@link Dhis2ApiStub} WireMock standing in for DHIS2.
 * Covers the exact-payload contract (golden files), the security rules, and the fault paths a
 * healthy real instance cannot produce. Real DHIS2 import semantics live in TrackerRoundTripIT.
 */
public class RouteFunctionalTestCase extends AbstractIngestFunctionalTestCase {

  // ---- happy paths ---------------------------------------------------------------

  @Test
  void testHealthEndpointIsPublic() {
    ResponseEntity<String> response = restTemplate.getForEntity("/api/health", String.class);
    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody().contains("\"status\":\"OK\""));
  }

  @Test
  void testPulseTechRecordIsTransformedAndImported() throws IOException {
    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));

    assertEquals(200, response.getStatusCode().value());
    assertTrue(response.getBody().contains("\"status\":\"OK\""));

    LoggedRequest trackerRequest = trackerRequests().get(0);
    // Import knobs from application.yaml: synchronous, full report, org units by CODE —
    // DHIS2 resolves the facility code; the mediator never looks it up.
    assertEquals("false", trackerRequest.queryParameter("async").firstValue());
    assertEquals("FULL", trackerRequest.queryParameter("reportMode").firstValue());
    assertEquals("CODE", trackerRequest.queryParameter("orgUnitIdScheme").firstValue());
    // Rules are for the Capture UI; on machine imports they misfire (the package's
    // Age ASSIGN gets injected into the enrollment and rejected with E1019).
    assertEquals("true", trackerRequest.queryParameter("skipRuleEngine").firstValue());
    assertEquals(
        TestPayloads.readJson("mediator/src/test/resources/expected-pulsetech-tracker-payload.json"),
        OBJECT_MAPPER.readValue(trackerRequest.getBodyAsString(), Map.class));

    // The idempotency lookup happened, scoped to this woman's MRN and the program.
    LoggedRequest lookup = lookupRequests().get(0);
    assertEquals("OYuDdqr2MvX:eq:719685", lookup.queryParameter("filter").firstValue());
    assertEquals("WSGAb5XwJ3Y", lookup.queryParameter("program").firstValue());
    assertEquals("ACCESSIBLE", lookup.queryParameter("ouMode").firstValue());
    // ... requesting every field the merge matches on. Event `status` is load-bearing:
    // without it the open SCHEDULE placeholder is invisible to the merge and a moved
    // nextDate DUPLICATES it instead of updating it.
    String fields = lookup.queryParameter("fields").firstValue();
    assertTrue(fields.contains("events[event,programStage,status,occurredAt,scheduledAt"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testExistingWomanIsUpdatedWithHerDhis2GeneratedUids() throws IOException {
    // The mediator never invents identifiers: on a lookup hit it injects the UIDs
    // DHIS2 generated when she was first created, so the import updates in place.
    Dhis2ApiStub.server()
        .stubFor(
            get(urlPathEqualTo("/api/tracker/trackedEntities"))
                .willReturn(
                    okJson(
                        "{\"instances\":[{\"trackedEntity\":\"TeiExisting1\",\"enrollments\":["
                            + "{\"enrollment\":\"EnrExisting1\",\"program\":\"WSGAb5XwJ3Y\",\"status\":\"ACTIVE\",\"events\":["
                            + "{\"event\":\"WphExisting1\",\"programStage\":\"iF5roNU7QWm\",\"occurredAt\":\"2026-02-15T00:00:00.000\","
                            + "\"dataValues\":[{\"dataElement\":\"w4ky6EkVahL\",\"value\":\"2025-10-20\"}]},"
                            + "{\"event\":\"ExamExist01\",\"programStage\":\"JqW7c9HYjVr\",\"occurredAt\":\"2026-02-15T00:00:00.000\","
                            + "\"dataValues\":[{\"dataElement\":\"bXVD2EMF7UW\",\"value\":\"1\"}]}]}]}]}")));

    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));
    assertEquals(200, response.getStatusCode().value());

    Map<String, Object> imported =
        OBJECT_MAPPER.readValue(trackerRequests().get(0).getBodyAsString(), Map.class);
    Map<String, Object> trackedEntity =
        ((List<Map<String, Object>>) imported.get("trackedEntities")).get(0);
    assertEquals("TeiExisting1", trackedEntity.get("trackedEntity"));
    Map<String, Object> enrollment =
        ((List<Map<String, Object>>) trackedEntity.get("enrollments")).get(0);
    assertEquals("EnrExisting1", enrollment.get("enrollment"));
    List<Map<String, Object>> events = (List<Map<String, Object>>) enrollment.get("events");
    assertEquals("WphExisting1", events.get(0).get("event")); // profile: stage+date match
    assertEquals("ExamExist01", events.get(1).get("event")); // exam visit 1: visit-number match
    assertFalse(events.get(2).containsKey("event")); // exam visit 2 is new: DHIS2 creates it
    assertFalse(events.get(3).containsKey("event")); // exam visit 3 is new: DHIS2 creates it
  }

  @Test
  void testBahmniRecordIsTransformedAndImported() throws IOException {
    ResponseEntity<String> response =
        push("/api/bahmni/anc-record", BAHMNI_KEY, TestPayloads.read("emr-mocks/bahmni/anc-record.json"));

    assertEquals(200, response.getStatusCode().value());
    assertEquals(
        TestPayloads.readJson("mediator/src/test/resources/expected-bahmni-tracker-payload.json"),
        OBJECT_MAPPER.readValue(trackerRequests().get(0).getBodyAsString(), Map.class));
  }

  @Test
  void testRePushSendsAnIdenticalPayload() {
    // The mapping is a pure function: the same record pushed twice (with the lookup
    // still finding nobody) produces byte-identical tracker payloads.
    String record = TestPayloads.read("emr-mocks/pulsetech/anc-record.json");
    assertEquals(200, push("/api/pulsetech/anc-record", PULSETECH_KEY, record).getStatusCode().value());
    assertEquals(200, push("/api/pulsetech/anc-record", PULSETECH_KEY, record).getStatusCode().value());

    List<LoggedRequest> requests = trackerRequests();
    assertEquals(2, requests.size());
    assertEquals(requests.get(0).getBodyAsString(), requests.get(1).getBodyAsString());
  }

  // ---- security ------------------------------------------------------------------

  @Test
  void testIngestWithoutApiKeyIsRejected() {
    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", null, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));
    assertEquals(403, response.getStatusCode().value());
    assertEquals(0, trackerRequests().size());
  }

  @Test
  void testIngestWithUnknownApiKeyIsRejected() {
    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", "wrong-key", TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));
    assertEquals(401, response.getStatusCode().value());
    assertEquals(0, trackerRequests().size());
  }

  @Test
  void testUnknownSourcePathIsDeniedEvenWithAValidKey() {
    // Sources exist only through configuration (api.keys). A path for a source
    // that has no configured key matches no rule and is denied outright — a
    // valid key for ANOTHER source doesn't help.
    ResponseEntity<String> response =
        push("/api/openclinic/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));
    assertEquals(403, response.getStatusCode().value());
    assertEquals(0, trackerRequests().size());
  }

  @Test
  void testOneKeyPerSite_PulseTechKeyCannotPushToBahmniEndpoint() {
    ResponseEntity<String> response =
        push("/api/bahmni/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/bahmni/anc-record.json"));
    assertEquals(403, response.getStatusCode().value());
    assertEquals(0, trackerRequests().size());
  }

  // ---- error contract ------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void testInvalidPayloadReturns400WithTheMissingFields() throws IOException {
    // The 400 is produced by the datastore-held validation rules — this exercises the
    // whole chain: fetch rules -> evaluate -> validation gate -> error response.
    Map<String, Object> record = TestPayloads.readJson("emr-mocks/pulsetech/anc-record.json");
    record.remove("pnt_mrn");
    ((List<Map<String, Object>>) record.get("anc_followup")).get(0).remove("lmp_date");

    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.toJson(record));

    assertEquals(400, response.getStatusCode().value());
    Map<String, Object> error = OBJECT_MAPPER.readValue(response.getBody(), Map.class);
    List<String> missing = (List<String>) error.get("missingFields");
    assertTrue(missing.contains("pnt_mrn"));
    // The id itself is what's missing here — so no patient reference in the body.
    assertFalse(error.containsKey("patient"));
    assertTrue(missing.contains("anc_followup[0].lmp_date"));
    assertEquals(0, trackerRequests().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testBahmniRegistrationEncounterWithoutLnmpReturns400() {
    Map<String, Object> record = TestPayloads.readJson("emr-mocks/bahmni/anc-record.json");
    ((List<Map<String, Object>>) record.get("encounters")).get(0).put("LNMP", null);

    ResponseEntity<String> response =
        push("/api/bahmni/anc-record", BAHMNI_KEY, TestPayloads.toJson(record));
    assertEquals(400, response.getStatusCode().value());
    assertTrue(response.getBody().contains("LNMP"));
    assertEquals(0, trackerRequests().size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void test400NamesThePatientTheVerdictIsAbout() throws IOException {
    Map<String, Object> record = TestPayloads.readJson("emr-mocks/bahmni/anc-record.json");
    ((List<Map<String, Object>>) record.get("encounters")).get(0).put("LNMP", null);

    ResponseEntity<String> response =
        push("/api/bahmni/anc-record", BAHMNI_KEY, TestPayloads.toJson(record));
    assertEquals(400, response.getStatusCode().value());
    Map<String, Object> error = OBJECT_MAPPER.readValue(response.getBody(), Map.class);
    assertEquals("668466", error.get("patient"));
    assertTrue(((String) error.get("error")).contains("668466"));
  }

  @Test
  void testImportReportWithErrorsReturns409() {
    Dhis2ApiStub.server()
        .stubFor(
            post(urlPathEqualTo("/api/tracker"))
                .willReturn(
                    okJson(
                        "{\"status\":\"ERROR\",\"validationReport\":{\"errorReports\":"
                            + "[{\"errorCode\":\"E1018\",\"message\":\"Attribute is mandatory\"}]}}")));

    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));

    assertEquals(409, response.getStatusCode().value());
    assertTrue(response.getBody().contains("E1018"));
  }

  @Test
  void testDhis2ConflictPassesThroughAs409WithTheRealReport() {
    Dhis2ApiStub.server()
        .stubFor(
            post(urlPathEqualTo("/api/tracker"))
                .willReturn(
                    aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            "{\"httpStatusCode\":409,\"status\":\"ERROR\",\"message\":"
                                + "\"Org unit code 0000000 could not be resolved\"}")));

    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));

    assertEquals(409, response.getStatusCode().value());
    assertTrue(response.getBody().contains("0000000"));
  }

  @Test
  void testDhis2HttpErrorReturns502() {
    Dhis2ApiStub.server()
        .stubFor(
            post(urlPathEqualTo("/api/tracker"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"httpStatusCode\":500,\"message\":\"boom\"}")));

    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));

    assertEquals(502, response.getStatusCode().value());
    assertTrue(response.getBody().contains("DHIS2 rejected the request"));
  }
}
