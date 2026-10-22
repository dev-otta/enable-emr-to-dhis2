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
  @SuppressWarnings("unchecked")
  public void testRecordWithoutLmpEddMapsWithoutThoseDataValues() throws IOException {
    // LMP/EDD are optional: absent values are left off the profile event entirely
    // (never sent as null), and the GA source becomes Clinical instead of LMP.
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    for (Map<String, Object> followup :
        (List<Map<String, Object>>) sample.get("anc_followup")) {
      followup.remove("lmp_date");
      followup.remove("edd");
    }

    Map<String, Object> actual = evaluate(mapping, sample);
    List<Map<String, Object>> dataValues =
        (List<Map<String, Object>>) events(actual).get(0).get("dataValues");
    assertEquals(1, dataValues.size()); // gestational age only
    assertFalse(dataValues.stream().anyMatch(d -> d.get("dataElement").equals("w4ky6EkVahL")));
    assertFalse(dataValues.stream().anyMatch(d -> d.get("dataElement").equals("Ru01omP2WCQ")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGaSourceIsLmpOnlyWhenTheExportCarriesAnLnmp() throws IOException {
    // With an LNMP the GA demonstrably derives from it (Akaki data: 92% within a
    // week), so the source is reported as LMP. Without one the export has no
    // source signal and none is sent — never guessed, never defaulted.
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    Map<String, Object> actual = evaluate(mapping, sample);
    List<Map<String, Object>> dataValues =
        (List<Map<String, Object>>) events(actual).get(0).get("dataValues");
    assertEquals(
        "LMP",
        dataValues.stream()
            .filter(d -> d.get("dataElement").equals("RPSgZF1i0hk"))
            .findFirst()
            .orElseThrow()
            .get("value"));

    for (Map<String, Object> followup :
        (List<Map<String, Object>>) sample.get("anc_followup")) {
      followup.remove("lmp_date");
      followup.remove("edd");
    }
    List<Map<String, Object>> without =
        (List<Map<String, Object>>) events(evaluate(mapping, sample)).get(0).get("dataValues");
    assertFalse(without.stream().anyMatch(d -> d.get("dataElement").equals("RPSgZF1i0hk")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testAgeInYearsIsMappedWhenThereIsNoDateOfBirth() throws IOException {
    // PulseTech exports age, not DOB: the age lands on the Age (years) attribute
    // and no date-of-birth attribute is sent (never null).
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    sample.remove("pnt_date_birth");
    sample.put("pnt_age", 27);

    Map<String, Object> actual = evaluate(mapping, sample);
    List<Map<String, Object>> attributes =
        (List<Map<String, Object>>) trackedEntity(actual).get("attributes");
    assertFalse(attributes.stream().anyMatch(a -> a.get("attribute").equals("Fgf497oYvSC")));
    assertEquals(
        27,
        attributes.stream()
            .filter(a -> a.get("attribute").equals("B6TnnFMgmCk"))
            .findFirst()
            .orElseThrow()
            .get("value"));
  }

  @Test
  public void testPayloadCarriesNoClientGeneratedUids() throws IOException {
    // DHIS2 owns identifier generation; the mediator only injects EXISTING UIDs
    // found by the lookup. The mapping itself must never invent any.
    assertCarriesNoClientGeneratedUids(evaluate(mapping, readEmrSample("pulsetech/anc-record.json")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testIdentityIsScopedByFacilitySoMrnsCannotCollideAcrossSites() throws IOException {
    // MRNs are only unique within one facility. The identity value is
    // '<facility code>-<MRN>', so the same MRN from two sites never matches
    // the same woman in DHIS2.
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    assertEquals("1057888-719685", identityValue(evaluate(mapping, sample)));

    sample.put("facility_dhis2_org_unit_code", "9990001");
    assertEquals("9990001-719685", identityValue(evaluate(mapping, sample)));
  }

  @SuppressWarnings("unchecked")
  private String identityValue(Map<String, Object> actual) {
    return (String)
        ((List<Map<String, Object>>) trackedEntity(actual).get("attributes"))
            .stream()
                .filter(a -> a.get("attribute").equals("OYuDdqr2MvX"))
                .findFirst()
                .orElseThrow()
                .get("value");
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
