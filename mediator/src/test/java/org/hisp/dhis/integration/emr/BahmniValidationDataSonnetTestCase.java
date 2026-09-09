package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Bahmni validation rules (config/datastore/bahmni-validation.ds), against the
 * real per-woman record shape. The rules are STRUCTURAL only ("can the mapping run?"); the data
 * model itself — mandatory attributes, types, ranges — is DHIS2's to enforce.
 */
public class BahmniValidationDataSonnetTestCase extends AbstractDataSonnetTestCase {

  private String rules;

  @BeforeEach
  public void beforeEach() throws IOException {
    rules = readDatastoreExpression("bahmni-validation.json");
  }

  @SuppressWarnings("unchecked")
  private List<String> missingFields(Map<String, Object> result) {
    return (List<String>) result.get("missingFields");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> visits(Map<String, Object> sample) {
    return (List<Map<String, Object>>) sample.get("visits");
  }

  @Test
  public void testReferenceSampleIsValid() throws IOException {
    // The verdict also names WHO it is about — the 400 carries it so a vendor
    // pushing a whole batch knows which patient to fix.
    assertEquals(
        Map.of("valid", true, "missingFields", List.of(), "mrn", "668466"),
        evaluate(rules, readEmrSample("bahmni/anc-record.json")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGestationalAgeIsRequiredOnVisitOne() throws IOException {
    // The mapping agreement: GA, LNMP and EDD are all given with visit 1.
    // The profile completes on import and GA is compulsory on completion, so
    // reject GA-less visit-1 records here with a vendor-readable message.
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    ((List<Map<String, Object>>) sample.get("visits")).get(0).put("gestationalAge", null);
    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).stream().anyMatch(f -> f.contains("gestationalAge")));
  }

  @Test
  public void testVerdictCarriesNullMrnWhenTheIdItselfIsMissing() throws IOException {
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    sample.put("patientId", null);
    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertEquals(null, result.get("mrn"));
  }

  @Test
  public void testPhonelessWomanIsRejectedWithAReadableMessage() throws IOException {
    // Policy row in the rules: the phone number IS required — the SMS programme is
    // the point of the integration, and the attribute is mandatory in DHIS2. The
    // mediator names the field instead of letting DHIS2 answer with an error code.
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    sample.put("phoneNumber", null);
    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("phoneNumber"));
  }

  @Test
  public void testMissingIdentityFieldsAreListedPrecisely() throws IOException {
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    sample.remove("patientId");
    sample.put("facilityCode", null);

    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("patientId"));
    assertTrue(missingFields(result).contains("facilityCode"));
  }

  @Test
  public void testLnmpAndEddAreRequiredOnlyWithVisitOne() throws IOException {
    // With visit 1 in the record, the Woman's Profile event is built -> lnmp/edd required.
    Map<String, Object> withVisitOne = readEmrSample("bahmni/anc-record.json");
    withVisitOne.put("lnmp", null);
    Map<String, Object> result = evaluate(rules, withVisitOne);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).stream().anyMatch(f -> f.startsWith("lnmp")));

    // Without visit 1 (follow-up-only record), no profile is built -> lnmp/edd optional.
    Map<String, Object> followUpOnly = readEmrSample("bahmni/anc-record.json");
    followUpOnly.put("lnmp", null);
    followUpOnly.put("edd", null);
    visits(followUpOnly).get(0).put("visitNumber", 3);
    visits(followUpOnly).get(1).put("visitNumber", 4);
    assertEquals(true, evaluate(rules, followUpOnly).get("valid"));
  }

  @Test
  public void testPerVisitFieldsAreNamed() throws IOException {
    Map<String, Object> sample = readEmrSample("bahmni/anc-record.json");
    visits(sample).get(1).remove("encounterDate");
    visits(sample).get(1).put("visitNumber", "two"); // not a number

    Map<String, Object> result = evaluate(rules, sample);
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("visits[1].encounterDate"));
    assertTrue(missingFields(result).contains("visits[1].visitNumber"));
  }

  @Test
  public void testArbitrarilyMalformedInputDoesNotCrashTheRules() {
    Map<String, Object> result = evaluate(rules, Map.of("visits", "not-an-array"));
    assertEquals(false, result.get("valid"));
    assertTrue(missingFields(result).contains("patientId"));
    assertTrue(missingFields(result).contains("visits (at least one encounter)"));
  }
}
