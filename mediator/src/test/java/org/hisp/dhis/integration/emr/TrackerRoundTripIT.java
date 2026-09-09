package org.hisp.dhis.integration.emr;

import static org.hisp.dhis.integration.emr.support.TestPayloads.BAHMNI_KEY;
import static org.hisp.dhis.integration.emr.support.TestPayloads.PULSETECH_KEY;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.EXAM_STAGE;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.MRN_ATTRIBUTE;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.PROFILE_STAGE;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.VISIT_NUMBER_DE;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.attributeValue;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.dataValue;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.eventWithVisitNumber;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.events;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.findByMrn;
import static org.hisp.dhis.integration.emr.support.TrackerQueries.scheduledEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.hisp.dhis.integration.emr.support.AbstractDhis2IT;
import org.hisp.dhis.integration.emr.support.TestPayloads;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;

/**
 * The round trip against a REAL DHIS2 (Testcontainers + the ENABLE dump): what WireMock
 * structurally cannot prove. Runs in {@code mvn verify}; Docker required. Ordered as a narrative —
 * later tests build on earlier state, the way consecutive weekly pushes do in production.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TrackerRoundTripIT extends AbstractDhis2IT {

  @Test
  @Order(1)
  void pulseTechRecordLandsAsAWomanWithProfileAndExamEvents() {
    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));
    assertEquals(200, response.getStatusCode().value(), response.getBody());

    List<JsonNode> instances = findByMrn("719685");
    assertEquals(1, instances.size());
    JsonNode woman = instances.get(0);
    assertEquals("719685", attributeValue(woman, MRN_ATTRIBUTE));
    assertEquals("Abeba", attributeValue(woman, "sB1IHYu2xQT"));
    assertTrue(events(woman).stream().anyMatch(e -> PROFILE_STAGE.equals(e.path("programStage").asText())));
    long exams =
        events(woman).stream().filter(e -> EXAM_STAGE.equals(e.path("programStage").asText())).count();
    assertEquals(3, exams);
    // Every captured contact is COMPLETED — verified against a REAL DHIS2, which
    // enforces ON_COMPLETE validation the WireMock stubs never could.
    for (JsonNode event : events(woman)) {
      assertEquals("COMPLETED", event.path("status").asText());
    }
  }

  @Test
  @Order(2)
  void rePushingTheSameRecordUpdatesInsteadOfDuplicating() {
    // The events from test 1 are COMPLETED, so this re-push also verifies that
    // the tracker API lets the idempotent merge UPDATE completed events
    // (default program settings).
    JsonNode before = findByMrn("719685").get(0);
    int eventsBefore = events(before).size();

    ResponseEntity<String> response =
        push("/api/pulsetech/anc-record", PULSETECH_KEY, TestPayloads.read("emr-mocks/pulsetech/anc-record.json"));
    assertEquals(200, response.getStatusCode().value(), response.getBody());

    List<JsonNode> after = findByMrn("719685");
    assertEquals(1, after.size(), "re-push must never create a second woman");
    assertEquals(
        before.path("trackedEntity").asText(),
        after.get(0).path("trackedEntity").asText(),
        "same DHIS2-generated tracked entity");
    assertEquals(eventsBefore, events(after.get(0)).size(), "unchanged event count");
  }

  @Test
  @Order(3)
  @SuppressWarnings("unchecked")
  void theRealBahmniExportLandsEveryWomanOnceIncludingNamelessWomen() {
    ResponseEntity<String> response =
        push("/api/bahmni/anc-records", BAHMNI_KEY, TestPayloads.read("emr-mocks/bahmni/anc-records-batch.json"));
    assertEquals(200, response.getStatusCode().value(), response.getBody());

    Map<String, Object> body;
    try {
      body = OBJECT_MAPPER.readValue(response.getBody(), Map.class);
    } catch (Exception e) {
      throw new IllegalStateException(response.getBody(), e);
    }
    assertEquals(5, ((Map<String, Object>) body.get("summary")).get("imported"));

    // A woman with NO NAMES imports (name attributes are non-mandatory in the
    // program metadata) — identified by MRN + DOB, reachable by SMS via her
    // (mandatory) phone number.
    List<JsonNode> nameless = findByMrn("668466");
    assertEquals(1, nameless.size());
    assertEquals("0911556677", attributeValue(nameless.get(0), "RJxLa3nITB3"));
    assertNull(attributeValue(nameless.get(0), "sB1IHYu2xQT"), "no first-name attribute");
    // Her nextDate became ONE SCHEDULE placeholder (no data values — E1315).
    JsonNode placeholder = scheduledEvent(nameless.get(0));
    assertNotNull(placeholder, "SCHEDULE placeholder from her nextDate");
    assertTrue(placeholder.path("scheduledAt").asText().startsWith("2026-09-02"));
    // Captured visits are COMPLETED; ONLY the placeholder stays open (overdue tracking).
    for (JsonNode event : events(nameless.get(0))) {
      if (!"SCHEDULE".equals(event.path("status").asText())) {
        assertEquals("COMPLETED", event.path("status").asText());
      }
    }

    // A follow-up-only woman (no visit 1 in the export) exists WITHOUT a profile event.
    List<JsonNode> followUpOnly = findByMrn("668465");
    assertEquals(1, followUpOnly.size());
    assertFalse(
        events(followUpOnly.get(0)).stream()
            .anyMatch(e -> PROFILE_STAGE.equals(e.path("programStage").asText())));
  }

  @Test
  @Order(4)
  @SuppressWarnings("unchecked")
  void rePushingTheIdenticalExportChangesNothing() {
    var counts = new java.util.HashMap<String, Integer>();
    for (String mrn : List.of("668462", "668465", "668464", "668463", "668466")) {
      counts.put(mrn, events(findByMrn(mrn).get(0)).size());
    }

    ResponseEntity<String> response =
        push("/api/bahmni/anc-records", BAHMNI_KEY, TestPayloads.read("emr-mocks/bahmni/anc-records-batch.json"));
    assertEquals(200, response.getStatusCode().value(), response.getBody());

    for (String mrn : counts.keySet()) {
      List<JsonNode> instances = findByMrn(mrn);
      assertEquals(1, instances.size(), "one woman for " + mrn);
      assertEquals(counts.get(mrn), events(instances.get(0)).size(), "event count for " + mrn);
    }
  }

  @Test
  @Order(5)
  void theSchedulePlaceholderBecomesTheVisitWhenSheAttends() {
    // Week 1 left woman 668466 with an open SCHEDULE placeholder (test 3).
    JsonNode before = findByMrn("668466").get(0);
    JsonNode placeholder = scheduledEvent(before);
    assertNotNull(placeholder);
    String placeholderUid = placeholder.path("event").asText();
    int eventsBefore = events(before).size();

    // Week 2: she attended — visit 3 arrives as a real encounter (follow-up-only record).
    String visitThree =
        """
        {
          "patientId": 668466,
          "birthDate": "1994-06-18",
          "phoneNumber": "0911556677",
          "facilityCode": "1057888",
          "dateOfFirstVisit": "2026-08-25",
          "lnmp": "2026-08-25",
          "edd": "2027-06-01",
          "visits": [
            { "encounterId": 40, "encounterDate": "2026-09-02", "visitNumber": 3,
              "gestationalAge": 46, "nextDate": "2026-09-30" }
          ]
        }
        """;
    ResponseEntity<String> response = push("/api/bahmni/anc-record", BAHMNI_KEY, visitThree);
    assertEquals(200, response.getStatusCode().value(), response.getBody());

    JsonNode after = findByMrn("668466").get(0);
    // The placeholder BECAME the visit: same DHIS2 event UID, now real, carrying
    // the visit number (the merge claims the stage's open SCHEDULE event).
    JsonNode visit3 = eventWithVisitNumber(after, "3");
    assertNotNull(visit3);
    assertEquals(placeholderUid, visit3.path("event").asText(), "updated in place, not duplicated");
    assertEquals("COMPLETED", visit3.path("status").asText(), "attended = completed");
    // A NEW placeholder rolled forward onto the new nextDate.
    JsonNode next = scheduledEvent(after);
    assertNotNull(next, "new SCHEDULE placeholder from the new nextDate");
    assertNotEquals(placeholderUid, next.path("event").asText());
    assertTrue(next.path("scheduledAt").asText().startsWith("2026-09-30"));
    assertEquals(eventsBefore + 1, events(after).size(), "exactly one event added (the new placeholder)");
  }

  @Test
  @Order(6)
  void anUnresolvableFacilityCodeIsRejectedByDhis2With409() {
    String record =
        TestPayloads.read("emr-mocks/pulsetech/anc-record.json")
            .replace("\"719685\"", "\"999404\"")
            .replace("1057888", "0000000");
    ResponseEntity<String> response = push("/api/pulsetech/anc-record", PULSETECH_KEY, record);
    assertEquals(409, response.getStatusCode().value(), response.getBody());
    assertTrue(findByMrn("999404").isEmpty(), "nothing may be created");
  }
}
