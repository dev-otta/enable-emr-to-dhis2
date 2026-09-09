package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the PulseTech -> DHIS2 tracker mapping
 * (config/datastore/pulsetech-mapping.ds, tested through its packed datastore form).
 *
 * <p>The mapping is a pure function of the payload; org units are carried as the facility's
 * DHIS2 org-unit CODE (the import runs with orgUnitIdScheme=CODE).
 */
public class PulseTechMappingDataSonnetTestCase extends AbstractDataSonnetTestCase {

  private String mapping;

  @BeforeEach
  public void beforeEach() throws IOException {
    mapping = readDatastoreExpression("pulsetech-mapping.json");
  }

  @Test
  public void testMultiVisitRecordMapsToExpectedTrackerPayload() throws IOException {
    Map<String, Object> actual = evaluate(mapping, readEmrSample("pulsetech/anc-record.json"));
    assertEquals(readExpected("expected-pulsetech-tracker-payload.json"), actual);
  }

  @Test
  public void testSingleVisitRecordMapsToExpectedTrackerPayload() throws IOException {
    Map<String, Object> actual =
        evaluate(mapping, readEmrSample("pulsetech/anc-record-single-visit.json"));
    assertEquals(readExpected("expected-pulsetech-single-visit-tracker-payload.json"), actual);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testEveryCapturedContactIsCompletedOnItsOwnDate() throws IOException {
    // PulseTech has no scheduled-visit concept: everything it exports already
    // happened, so profile and every follow-up land as COMPLETED events with
    // completedAt = the follow-up date.
    Map<String, Object> actual = evaluate(mapping, readEmrSample("pulsetech/anc-record.json"));
    List<Map<String, Object>> events =
        (List<Map<String, Object>>)
            ((List<Map<String, Object>>)
                    ((List<Map<String, Object>>) actual.get("trackedEntities"))
                        .get(0)
                        .get("enrollments"))
                .get(0)
                .get("events");
    assertEquals(4, events.size(), "profile + three follow-ups");
    for (Map<String, Object> event : events) {
      assertEquals("COMPLETED", event.get("status"));
      assertEquals(event.get("occurredAt"), event.get("completedAt"));
    }
  }

  @Test
  public void testOrgUnitsCarryTheFacilityCodeUntouched() throws IOException {
    // The mediator never resolves org units: the facility's DHIS2 org-unit CODE flows
    // through verbatim, and DHIS2 resolves it at import (orgUnitIdScheme=CODE).
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    sample.put("facility_dhis2_org_unit_code", "SOME-OTHER-CODE");

    Map<String, Object> actual = evaluate(mapping, sample);
    assertEquals("SOME-OTHER-CODE", trackedEntity(actual).get("orgUnit"));
    assertEquals("SOME-OTHER-CODE", enrollment(actual).get("orgUnit"));
    events(actual).forEach(e -> assertEquals("SOME-OTHER-CODE", e.get("orgUnit")));
  }

  @Test
  public void testMappingIsDeterministic() throws IOException {
    // Mapping the same record twice must produce identical payloads — the foundation
    // the route's lookup-and-merge idempotency builds on.
    Map<String, Object> first = evaluate(mapping, readEmrSample("pulsetech/anc-record.json"));
    Map<String, Object> second = evaluate(mapping, readEmrSample("pulsetech/anc-record.json"));
    assertEquals(first, second);
  }

  @Test
  public void testPayloadCarriesNoClientGeneratedUids() throws IOException {
    // DHIS2 owns identifier generation; the mediator only injects EXISTING UIDs
    // found by the lookup. The mapping itself must never invent any.
    assertCarriesNoClientGeneratedUids(evaluate(mapping, readEmrSample("pulsetech/anc-record.json")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testVisitNumberTextIsParsedToInteger() throws IOException {
    // PulseTech sends "Visit 12"; the ANC Visit Number data element is INTEGER_POSITIVE.
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record-single-visit.json");
    List<Map<String, Object>> followups = (List<Map<String, Object>>) sample.get("anc_followup");
    followups.get(0).put("visit_number", "Visit 12");

    Map<String, Object> actual = evaluate(mapping, sample);
    List<Map<String, Object>> events = events(actual);
    Map<String, Object> examEvent = events.get(events.size() - 1);
    List<Map<String, Object>> dataValues = (List<Map<String, Object>>) examEvent.get("dataValues");
    assertEquals(12, dataValues.get(0).get("value"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAbsentNamesAreDroppedNeverSentAsNull() throws IOException {
    // All name fields are optional: an absent name is left off entirely.
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    sample.remove("pnt_fname");
    sample.remove("pnt_mname");
    sample.remove("pnt_lname");

    Map<String, Object> actual = evaluate(mapping, sample);
    List<Map<String, Object>> attributes =
        (List<Map<String, Object>>) trackedEntity(actual).get("attributes");
    assertEquals(3, attributes.size()); // MRN, date of birth, phone
    assertFalse(attributes.stream().anyMatch(a -> a.get("attribute").equals("sB1IHYu2xQT")));
    assertFalse(attributes.stream().anyMatch(a -> a.get("attribute").equals("ZtQqOYot5ut")));
    assertFalse(attributes.stream().anyMatch(a -> a.get("attribute").equals("ENRjVGxVL6l")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFollowupsAreSortedByDateBeforeAnchoringTheEnrollment() throws IOException {
    // The enrollment (and the Woman's Profile event) must anchor on the EARLIEST
    // follow-up even if the EMR sends visits out of order.
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    List<Object> followups = (List<Object>) sample.get("anc_followup");
    java.util.Collections.reverse(followups);

    Map<String, Object> actual = evaluate(mapping, sample);
    assertEquals(readExpected("expected-pulsetech-tracker-payload.json"), actual);
  }
}
