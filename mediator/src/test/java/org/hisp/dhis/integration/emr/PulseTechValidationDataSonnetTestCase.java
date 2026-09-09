package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the PulseTech validation rules
 * (config/datastore/pulsetech-validation.ds, tested through the packed datastore form).
 *
 * <p>The rules are the source of the mediator's 400 responses: the missingFields strings
 * asserted here are exactly what an EMR developer sees.
 */
public class PulseTechValidationDataSonnetTestCase extends AbstractDataSonnetTestCase {

  private String rules;

  @BeforeEach
  public void beforeEach() throws IOException {
    rules = readDatastoreExpression("pulsetech-validation.json");
  }

  @SuppressWarnings("unchecked")
  private List<String> missingFields(Map<String, Object> result) {
    return (List<String>) result.get("missingFields");
  }

  @Test
  public void testReferenceSamplesAreValid() throws IOException {
    // The shipped samples must always pass their own shipped rules.
    assertEquals(
        Map.of("valid", true, "missingFields", List.of(), "mrn", "719685"),
        evaluate(rules, readEmrSample("pulsetech/anc-record.json")));
    assertEquals(
        Map.of("valid", true, "missingFields", List.of(), "mrn", "804112"),
        evaluate(rules, readEmrSample("pulsetech/anc-record-single-visit.json")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMissingAndBlankFieldsAreListedPrecisely() throws IOException {
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    sample.remove("pnt_mrn");
    sample.put("pnt_phone", "   "); // blank counts as missing
    ((List<Map<String, Object>>) sample.get("anc_followup")).get(0).remove("lmp_date");

    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertEquals(
        List.of("pnt_mrn", "pnt_phone", "anc_followup[0].lmp_date"), missingFields(result));
  }

  @Test
  public void testNamesAreOptional() throws IOException {
    // Names are mapped when the export includes them and dropped when it
    // does not; a record without any name field is a valid record.
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    sample.remove("pnt_fname");
    sample.remove("pnt_mname");
    sample.remove("pnt_lname");
    assertEquals(true, evaluate(rules, sample).get("valid"));
  }

  @Test
  public void testRecordWithoutFollowupsIsRejected() throws IOException {
    Map<String, Object> sample = readEmrSample("pulsetech/anc-record.json");
    sample.put("anc_followup", List.of());

    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("anc_followup (at least one entry)"));
  }

  @Test
  public void testArbitrarilyMalformedInputDoesNotCrashTheRules() {
    // The rules run BEFORE anything else, on raw vendor input — they must degrade to
    // a field list, never to an evaluation error.
    Map<String, Object> result = evaluate(rules, Map.of("anc_followup", "not-a-list"));
    assertEquals(false, result.get("valid"));
    assertFalse(missingFields(result).isEmpty());
  }
}
