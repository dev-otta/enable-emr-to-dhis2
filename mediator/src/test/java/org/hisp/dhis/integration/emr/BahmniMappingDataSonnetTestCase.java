package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Bahmni -> DHIS2 tracker mapping (config/datastore/bahmni-mapping.ds), against
 * the REAL per-woman record shape (what the envelope expression produces from Bahmni's flat
 * encounter export — see emr-mocks/bahmni/).
 */
public class BahmniMappingDataSonnetTestCase extends AbstractDataSonnetTestCase {

  private String mapping;

  @BeforeEach
  public void beforeEach() throws IOException {
    mapping = readDatastoreExpression("bahmni-mapping.json");
  }

  @Test
  public void testRecordMapsToExpectedTrackerPayload() throws IOException {
    Map<String, Object> actual = evaluate(mapping, readEmrSample("bahmni/anc-record.json"));
    assertEquals(readExpected("expected-bahmni-tracker-payload.json"), actual);
  }

  @Test
  public void testMappingIsDeterministic() throws IOException {
    assertEquals(
        evaluate(mapping, readEmrSample("bahmni/anc-record.json")),
        evaluate(mapping, readEmrSample("bahmni/anc-record.json")));
  }

  @Test
  public void testPayloadCarriesNoClientGeneratedUids() throws IOException {
    assertCarriesNoClientGeneratedUids(evaluate(mapping, readEmrSample("bahmni/anc-record.json")));
  }

  @Test
  public void testPhoneRidesAlongAndANullPhoneIsOmittedDefensively() throws IOException {
    // The contract requires the phone (validation enforces it); the mapping still
    // guards defensively — a null must be OMITTED, never sent as a null value.
    Map<String, Object> record = readEmrSample("bahmni/anc-record.json");
    List<Map<String, Object>> attributes = attributes(evaluate(mapping, record));
    assertTrue(attributes.contains(Map.of("attribute", "RJxLa3nITB3", "value", "0911556677")));
    // ... and the MRN goes in as a STRING (the unique identity attribute).
    assertTrue(attributes.contains(Map.of("attribute", "OYuDdqr2MvX", "value", "668466")));

    record.put("phoneNumber", null);
    List<Map<String, Object>> withoutPhone = attributes(evaluate(mapping, record));
    assertTrue(withoutPhone.stream().noneMatch(a -> "RJxLa3nITB3".equals(a.get("attribute"))));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCapturedVisitsAreCompletedButThePlaceholderStaysScheduled() throws IOException {
    // A visit captured in the source IS a completed contact — COMPLETED,
    // completedAt = the day it happened. Only the appointment built from
    // nextDate stays SCHEDULE (overdue tracking keys off it).
    // Record: visit 1 only, with a nextDate.
    Map<String, Object> record = readEmrSample("bahmni/anc-record.json");
    List<Map<String, Object>> visits = (List<Map<String, Object>>) record.get("visits");
    visits.get(0).put("nextDate", "2026-09-15");
    record.put("visits", List.of(visits.get(0)));

    List<Map<String, Object>> events = events(evaluate(mapping, record));

    Map<String, Object> profile =
        events.stream().filter(e -> "iF5roNU7QWm".equals(e.get("programStage"))).findFirst().orElseThrow();
    assertEquals("COMPLETED", profile.get("status"));
    assertEquals(profile.get("occurredAt"), profile.get("completedAt"));

    Map<String, Object> visitOne =
        events.stream()
            .filter(e -> "JqW7c9HYjVr".equals(e.get("programStage")) && "COMPLETED".equals(e.get("status")))
            .findFirst()
            .orElseThrow();
    assertEquals(visits.get(0).get("encounterDate"), visitOne.get("occurredAt"));
    assertEquals(visits.get(0).get("encounterDate"), visitOne.get("completedAt"));

    Map<String, Object> scheduled =
        events.stream().filter(e -> "SCHEDULE".equals(e.get("status"))).findFirst().orElseThrow();
    assertEquals("2026-09-15", scheduled.get("scheduledAt"));
    assertNull(scheduled.get("completedAt"), "the open appointment must never be completed");
    assertEquals(3, events.size(), "profile + visit 1 + the placeholder");
  }

  @Test
  public void testNextDateBecomesOneScheduleEventWithoutDataValues() throws IOException {
    // The latest visit's nextDate -> ONE SCHEDULE event. It must carry NO data
    // values — DHIS2 rejects them on SCHEDULE events (E1315) — so no visit number:
    // the merge claims the stage's open placeholder when the real visit arrives.
    Map<String, Object> actual = evaluate(mapping, readEmrSample("bahmni/anc-record.json"));
    List<Map<String, Object>> scheduled =
        events(actual).stream().filter(e -> "SCHEDULE".equals(e.get("status"))).toList();
    assertEquals(1, scheduled.size());
    assertEquals("2026-09-02", scheduled.get(0).get("scheduledAt"));
    assertNull(scheduled.get(0).get("occurredAt"));
    assertNull(scheduled.get(0).get("dataValues"), "SCHEDULE events must carry no data values");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAbsentGestationalAgeIsOmittedNotDerived() throws IOException {
    // Bahmni sometimes sends gestationalAge: null on visit 1 (real data). Policy:
    // the mapping does NO clinical arithmetic — GA (and its source label) are
    // mapped verbatim when sent and omitted when not.
    Map<String, Object> record = readEmrSample("bahmni/anc-record.json");
    List<Map<String, Object>> visits = (List<Map<String, Object>>) record.get("visits");
    visits.get(0).put("gestationalAge", null);

    Map<String, Object> profile =
        events(evaluate(mapping, record)).stream()
            .filter(e -> "iF5roNU7QWm".equals(e.get("programStage")))
            .findFirst()
            .orElseThrow();
    List<Map<String, Object>> dataValues =
        (List<Map<String, Object>>) profile.get("dataValues");
    assertTrue(dataValues.stream().noneMatch(dv -> "w9p8MQDRyMr".equals(dv.get("dataElement"))));
    assertTrue(dataValues.stream().noneMatch(dv -> "RPSgZF1i0hk".equals(dv.get("dataElement"))));
    // LNMP and EDD still map as sent.
    assertTrue(dataValues.stream().anyMatch(dv -> "w4ky6EkVahL".equals(dv.get("dataElement"))));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testFollowUpOnlyRecordBuildsNoProfileEvent() throws IOException {
    // A record without visit 1 (her registration was pushed in an earlier export)
    // maps no Woman's Profile event — the merge leaves her existing profile untouched.
    Map<String, Object> record = readEmrSample("bahmni/anc-record.json");
    record.put("lnmp", null);
    record.put("edd", null);
    record.put("dateOfFirstVisit", null);
    List<Map<String, Object>> visits = (List<Map<String, Object>>) record.get("visits");
    visits.get(0).put("visitNumber", 3);
    visits.get(1).put("visitNumber", 4);

    Map<String, Object> actual = evaluate(mapping, record);
    assertFalse(
        events(actual).stream().anyMatch(e -> "iF5roNU7QWm".equals(e.get("programStage"))));
    // ... and the enrollment anchors on the earliest encounter in the record.
    assertEquals("2026-08-24", enrollment(actual).get("enrolledAt"));
    // ... and the latest visit's nextDate still becomes the (data-value-less) placeholder.
    Map<String, Object> scheduled =
        events(actual).stream()
            .filter(e -> "SCHEDULE".equals(e.get("status")))
            .findFirst()
            .orElseThrow();
    assertEquals("2026-09-02", scheduled.get("scheduledAt"));
    assertNull(scheduled.get("dataValues"));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> attributes(Map<String, Object> payload) {
    return (List<Map<String, Object>>) trackedEntity(payload).get("attributes");
  }
}
