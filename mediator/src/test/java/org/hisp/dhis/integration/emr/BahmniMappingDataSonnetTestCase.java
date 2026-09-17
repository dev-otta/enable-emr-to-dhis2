package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Bahmni -> DHIS2 tracker mapping (config/datastore/bahmni-mapping.ds), against
 * the per-person record shape Bahmni exports (see emr-mocks/bahmni/).
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
  public void testCapturedVisitsAreCompletedButThePlaceholderStaysScheduled() throws IOException {
    // A visit captured in the source IS a completed contact — COMPLETED,
    // completedAt = the day it happened. Only the appointment built from
    // nextDate stays SCHEDULE (overdue tracking keys off it).
    // The sample: one registration encounter with a nextDate.
    List<Map<String, Object>> events =
        events(evaluate(mapping, readEmrSample("bahmni/anc-record.json")));

    Map<String, Object> profile =
        events.stream().filter(e -> "iF5roNU7QWm".equals(e.get("programStage"))).findFirst().orElseThrow();
    assertEquals("COMPLETED", profile.get("status"));
    assertEquals(profile.get("occurredAt"), profile.get("completedAt"));

    Map<String, Object> visitOne =
        events.stream()
            .filter(e -> "JqW7c9HYjVr".equals(e.get("programStage")) && "COMPLETED".equals(e.get("status")))
            .findFirst()
            .orElseThrow();
    assertEquals("2026-08-25", visitOne.get("occurredAt"));
    assertEquals("2026-08-25", visitOne.get("completedAt"));

    Map<String, Object> scheduled =
        events.stream().filter(e -> "SCHEDULE".equals(e.get("status"))).findFirst().orElseThrow();
    assertEquals("2026-09-02", scheduled.get("scheduledAt"));
    assertNull(scheduled.get("completedAt"), "the open appointment must never be completed");
    assertNull(scheduled.get("dataValues"), "SCHEDULE events must carry no data values");
    assertEquals(3, events.size(), "profile + visit 1 + the placeholder");
  }

  @Test
  public void testProfileIsBuiltFromTheRegistrationEncounterNotFromVisitOne() throws IOException {
    // The registration data (LNMP/EDD/GA, dateOfFirstVisit) can arrive on ANY
    // encounter — in the reference export, woman 668463 carries it on her
    // visit-3 row. The profile is gated on the data being present, not on the
    // record containing visit number 1.
    Map<String, Object> record = recordWithEncounters(
        encounter(28, "2026-08-21", 4, "12", "2026-09-04", null, null, null),
        encounter(35, "2026-08-25", 3, "33", "2026-09-05", "2026-08-18", "2026-03-19", "2026-12-24"));

    Map<String, Object> actual = evaluate(mapping, record);
    Map<String, Object> profile =
        events(actual).stream()
            .filter(e -> "iF5roNU7QWm".equals(e.get("programStage")))
            .findFirst()
            .orElseThrow();
    List<Map<String, Object>> dataValues = dataValues(profile);
    assertTrue(dataValues.contains(Map.of("dataElement", "w4ky6EkVahL", "value", "2026-03-19")));
    assertTrue(dataValues.contains(Map.of("dataElement", "Ru01omP2WCQ", "value", "2026-12-24")));
    // GA is mapped verbatim (Bahmni sends it as a string), source is LMP.
    assertTrue(dataValues.contains(Map.of("dataElement", "w9p8MQDRyMr", "value", "33")));
    assertTrue(dataValues.contains(Map.of("dataElement", "RPSgZF1i0hk", "value", "LMP")));
    // The enrollment anchors on the hoisted dateOfFirstVisit.
    assertEquals("2026-08-18", enrollment(actual).get("enrolledAt"));
  }

  @Test
  public void testFollowUpOnlyRecordBuildsNoProfileEvent() throws IOException {
    // No encounter carries LNMP/EDD (her registration was pushed earlier): no
    // Woman's Profile event — the merge leaves her existing profile untouched.
    Map<String, Object> record = recordWithEncounters(
        encounter(26, "2026-08-21", 2, "23", "2026-09-04", null, null, null),
        encounter(36, "2026-08-25", 3, "34", "2026-09-05", null, null, null));

    Map<String, Object> actual = evaluate(mapping, record);
    assertFalse(
        events(actual).stream().anyMatch(e -> "iF5roNU7QWm".equals(e.get("programStage"))));
    // ... the enrollment anchors on the earliest encounter in the record ...
    assertEquals("2026-08-21", enrollment(actual).get("enrolledAt"));
    // ... and the latest encounter's nextDate still becomes the placeholder.
    Map<String, Object> scheduled =
        events(actual).stream()
            .filter(e -> "SCHEDULE".equals(e.get("status")))
            .findFirst()
            .orElseThrow();
    assertEquals("2026-09-05", scheduled.get("scheduledAt"));
    assertNull(scheduled.get("dataValues"));
  }

  @Test
  public void testAbsentGestationalAgeIsOmittedNotDerived() throws IOException {
    // A GA-less registration encounter is rejected upstream by validation; the
    // mapping still guards defensively — GA and its source label are mapped
    // verbatim when sent and OMITTED when not, never derived.
    Map<String, Object> record = readEmrSample("bahmni/anc-record.json");
    encounters(record).get(0).put("gestationalAge", null);

    Map<String, Object> profile =
        events(evaluate(mapping, record)).stream()
            .filter(e -> "iF5roNU7QWm".equals(e.get("programStage")))
            .findFirst()
            .orElseThrow();
    List<Map<String, Object>> dataValues = dataValues(profile);
    assertTrue(dataValues.stream().noneMatch(dv -> "w9p8MQDRyMr".equals(dv.get("dataElement"))));
    assertTrue(dataValues.stream().noneMatch(dv -> "RPSgZF1i0hk".equals(dv.get("dataElement"))));
    // LNMP and EDD still map as sent.
    assertTrue(dataValues.stream().anyMatch(dv -> "w4ky6EkVahL".equals(dv.get("dataElement"))));
  }

  // ---- helpers -------------------------------------------------------------

  private Map<String, Object> recordWithEncounters(Map<String, Object>... encounters)
      throws IOException {
    Map<String, Object> record = readEmrSample("bahmni/anc-record.json");
    record.put("encounters", new ArrayList<>(List.of(encounters)));
    return record;
  }

  private static Map<String, Object> encounter(
      int id, String date, int visitNumber, String ga, String nextDate,
      String dateOfFirstVisit, String lnmp, String edd) {
    Map<String, Object> encounter = new HashMap<>();
    encounter.put("encounterId", id);
    encounter.put("encounterDate", date);
    encounter.put("visitNumber", visitNumber);
    encounter.put("gestationalAge", ga);
    encounter.put("nextDate", nextDate);
    encounter.put("dateOfFirstVisit", dateOfFirstVisit);
    encounter.put("LNMP", lnmp);
    encounter.put("EDD", edd);
    return encounter;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> encounters(Map<String, Object> record) {
    return (List<Map<String, Object>>) record.get("encounters");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> dataValues(Map<String, Object> event) {
    return (List<Map<String, Object>>) event.get("dataValues");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> attributes(Map<String, Object> payload) {
    return (List<Map<String, Object>>) trackedEntity(payload).get("attributes");
  }
}
