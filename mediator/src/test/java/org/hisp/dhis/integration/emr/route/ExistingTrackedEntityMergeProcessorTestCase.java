package org.hisp.dhis.integration.emr.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the idempotent-upsert merge — the component that turns "the mediator never
 * generates UIDs" into "re-pushes never duplicate". Pure JUnit: the processor is instantiated
 * directly with the ANC visit-number data element as the event-match key.
 */
public class ExistingTrackedEntityMergeProcessorTestCase {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String VISIT_NUMBER = "bXVD2EMF7UW";
  private static final String PROFILE_STAGE = "iF5roNU7QWm";
  private static final String EXAM_STAGE = "JqW7c9HYjVr";

  private final ExistingTrackedEntityMergeProcessor processor =
      new ExistingTrackedEntityMergeProcessor(VISIT_NUMBER);

  // ---- fixture builders ------------------------------------------------------------

  private static String mappedPayload() {
    return """
        {"trackedEntities":[{"trackedEntityType":"MCPQUTHX1Ze","orgUnit":"1057888",
          "attributes":[{"attribute":"OYuDdqr2MvX","value":"719685"}],
          "enrollments":[{"program":"WSGAb5XwJ3Y","orgUnit":"1057888","enrolledAt":"2026-02-15",
            "occurredAt":"2026-02-15","status":"ACTIVE","events":[
              {"programStage":"iF5roNU7QWm","orgUnit":"1057888","occurredAt":"2026-02-15","status":"ACTIVE",
               "dataValues":[{"dataElement":"w4ky6EkVahL","value":"2025-10-20"}]},
              {"programStage":"JqW7c9HYjVr","orgUnit":"1057888","occurredAt":"2026-02-15","status":"ACTIVE",
               "dataValues":[{"dataElement":"bXVD2EMF7UW","value":1}]},
              {"programStage":"JqW7c9HYjVr","orgUnit":"1057888","occurredAt":"2026-03-15","status":"ACTIVE",
               "dataValues":[{"dataElement":"bXVD2EMF7UW","value":2}]}
        ]}]}]}""";
  }

  /** An existing woman as the tracker lookup returns her (values are strings, dates full ISO). */
  private static Map<String, Object> existingWoman() throws Exception {
    return OBJECT_MAPPER.readValue(
        """
        {"instances":[{"trackedEntity":"TeiExisting1","enrollments":[
          {"enrollment":"EnrExisting1","program":"WSGAb5XwJ3Y","status":"ACTIVE","events":[
            {"event":"WphExisting1","programStage":"iF5roNU7QWm","occurredAt":"2026-02-15T00:00:00.000",
             "dataValues":[{"dataElement":"w4ky6EkVahL","value":"2025-10-20"}]},
            {"event":"ExamExist01","programStage":"JqW7c9HYjVr","occurredAt":"2026-02-15T00:00:00.000",
             "dataValues":[{"dataElement":"bXVD2EMF7UW","value":"1"}]}
        ]}]}]}""",
        Map.class);
  }

  private JsonNode merge(String payload, Map<String, Object> lookupResult) throws Exception {
    Exchange exchange = new DefaultExchange(new DefaultCamelContext());
    exchange.setVariable("trackerPayload", payload);
    exchange.getMessage().setBody(lookupResult);
    processor.process(exchange);
    return OBJECT_MAPPER.readTree(exchange.getMessage().getBody(String.class));
  }

  private static JsonNode te(JsonNode merged) {
    return merged.path("trackedEntities").path(0);
  }

  private static JsonNode events(JsonNode merged) {
    return te(merged).path("enrollments").path(0).path("events");
  }

  // ---- the lookup missed: create everything, DHIS2 generates the UIDs ----------------

  @Test
  public void testUnknownWomanPassesThroughUntouched() throws Exception {
    JsonNode merged = merge(mappedPayload(), Map.of("instances", List.of()));
    assertEquals(OBJECT_MAPPER.readTree(mappedPayload()), merged);
    assertFalse(te(merged).has("trackedEntity"));
  }

  @Test
  public void testNullAndMissingLookupResultsAreTreatedAsUnknown() throws Exception {
    assertFalse(te(merge(mappedPayload(), Map.of())).has("trackedEntity"));
  }

  // ---- the lookup hit: inject, so DHIS2 updates ---------------------------------------

  @Test
  public void testExistingWomanGetsHerUidsInjected() throws Exception {
    JsonNode merged = merge(mappedPayload(), existingWoman());

    assertEquals("TeiExisting1", te(merged).path("trackedEntity").asText());
    assertEquals(
        "EnrExisting1", te(merged).path("enrollments").path(0).path("enrollment").asText());
    // the non-repeatable profile event and exam visit 1 match existing events...
    assertEquals("WphExisting1", events(merged).path(0).path("event").asText());
    assertEquals("ExamExist01", events(merged).path(1).path("event").asText());
    // ...while exam visit 2 is new: no UID, DHIS2 creates it
    assertFalse(events(merged).path(2).has("event"));
  }

  @Test
  public void testARealVisitClaimsTheStagesOpenSchedulePlaceholder() throws Exception {
    // The placeholder can carry NO data values (DHIS2's E1315), so no visit number.
    // When the real visit arrives (finished, June 3rd), it claims the stage's open
    // SCHEDULE event: attendance fulfils the appointment, the placeholder BECOMES
    // the visit — same event UID even though the dates differ.
    String payload =
        """
        {"trackedEntities":[{"attributes":[],"enrollments":[{"program":"WSGAb5XwJ3Y","events":[
          {"programStage":"JqW7c9HYjVr","occurredAt":"2026-06-03","status":"ACTIVE",
           "dataValues":[{"dataElement":"bXVD2EMF7UW","value":3}]}
        ]}]}]}""";
    Map<String, Object> existing =
        OBJECT_MAPPER.readValue(
            """
            {"instances":[{"trackedEntity":"TeiExisting1","enrollments":[
              {"enrollment":"EnrExisting1","program":"WSGAb5XwJ3Y","status":"ACTIVE","events":[
                {"event":"SchedExist1","programStage":"JqW7c9HYjVr","status":"SCHEDULE",
                 "scheduledAt":"2026-06-01T00:00:00.000"}
            ]}]}]}""",
            Map.class);

    JsonNode merged = merge(payload, existing);
    assertEquals("SchedExist1", events(merged).path(0).path("event").asText());
  }

  @Test
  public void testAnExactVisitNumberMatchBeatsThePlaceholderClaim() throws Exception {
    // A re-pushed visit that already exists as a REAL event must update that event,
    // never grab the open placeholder — even when the placeholder sits first in
    // DHIS2's event list.
    String payload =
        """
        {"trackedEntities":[{"attributes":[],"enrollments":[{"program":"WSGAb5XwJ3Y","events":[
          {"programStage":"JqW7c9HYjVr","occurredAt":"2026-06-03","status":"ACTIVE",
           "dataValues":[{"dataElement":"bXVD2EMF7UW","value":2}]}
        ]}]}]}""";
    Map<String, Object> existing =
        OBJECT_MAPPER.readValue(
            """
            {"instances":[{"trackedEntity":"TeiExisting1","enrollments":[
              {"enrollment":"EnrExisting1","program":"WSGAb5XwJ3Y","status":"ACTIVE","events":[
                {"event":"SchedExist1","programStage":"JqW7c9HYjVr","status":"SCHEDULE",
                 "scheduledAt":"2026-06-15T00:00:00.000"},
                {"event":"ExamExist02","programStage":"JqW7c9HYjVr","status":"ACTIVE",
                 "occurredAt":"2026-06-01T00:00:00.000",
                 "dataValues":[{"dataElement":"bXVD2EMF7UW","value":"2"}]}
            ]}]}]}""",
            Map.class);

    JsonNode merged = merge(payload, existing);
    assertEquals("ExamExist02", events(merged).path(0).path("event").asText());
  }

  @Test
  public void testARescheduledPlaceholderUpdatesTheOpenOneInPlace() throws Exception {
    // nextDate moved with no new visit: the incoming placeholder (different date,
    // no data values) must claim the existing open placeholder, not duplicate it.
    String payload =
        """
        {"trackedEntities":[{"attributes":[],"enrollments":[{"program":"WSGAb5XwJ3Y","events":[
          {"programStage":"JqW7c9HYjVr","scheduledAt":"2026-06-20","status":"SCHEDULE"}
        ]}]}]}""";
    Map<String, Object> existing =
        OBJECT_MAPPER.readValue(
            """
            {"instances":[{"trackedEntity":"TeiExisting1","enrollments":[
              {"enrollment":"EnrExisting1","program":"WSGAb5XwJ3Y","status":"ACTIVE","events":[
                {"event":"SchedExist1","programStage":"JqW7c9HYjVr","status":"SCHEDULE",
                 "scheduledAt":"2026-06-01T00:00:00.000"}
            ]}]}]}""",
            Map.class);

    JsonNode merged = merge(payload, existing);
    assertEquals("SchedExist1", events(merged).path(0).path("event").asText());
  }

  @Test
  public void testKeyLessEventsFallBackToSameStageAndDate() throws Exception {
    // The Woman's Profile event carries no visit number; it matches its existing
    // counterpart by program stage + date.
    JsonNode merged = merge(mappedPayload(), existingWoman());
    assertEquals("WphExisting1", events(merged).path(0).path("event").asText());
  }

  @Test
  public void testEachExistingEventIsClaimedAtMostOnce() throws Exception {
    // Two mapped events that would both match the same existing event must not both
    // claim its UID — the second stays UID-less and becomes a create.
    String payload =
        """
        {"trackedEntities":[{"attributes":[],"enrollments":[{"program":"WSGAb5XwJ3Y","events":[
          {"programStage":"JqW7c9HYjVr","occurredAt":"2026-02-15","dataValues":[{"dataElement":"bXVD2EMF7UW","value":1}]},
          {"programStage":"JqW7c9HYjVr","occurredAt":"2026-02-15","dataValues":[{"dataElement":"bXVD2EMF7UW","value":1}]}
        ]}]}]}""";
    JsonNode merged = merge(payload, existingWoman());
    assertEquals("ExamExist01", events(merged).path(0).path("event").asText());
    assertFalse(events(merged).path(1).has("event"));
  }

  @Test
  public void testActiveEnrollmentIsPreferredOverCompletedOnes() throws Exception {
    Map<String, Object> existing =
        OBJECT_MAPPER.readValue(
            """
            {"instances":[{"trackedEntity":"TeiExisting1","enrollments":[
              {"enrollment":"EnrOldDone01","program":"WSGAb5XwJ3Y","status":"COMPLETED","events":[]},
              {"enrollment":"EnrActive001","program":"WSGAb5XwJ3Y","status":"ACTIVE","events":[]},
              {"enrollment":"EnrOtherPrg1","program":"OTHERPROGRM","status":"ACTIVE","events":[]}
            ]}]}""",
            Map.class);
    JsonNode merged = merge(mappedPayload(), existing);
    assertEquals(
        "EnrActive001", te(merged).path("enrollments").path(0).path("enrollment").asText());
  }

  @Test
  public void testExistingWomanWithoutAnEnrollmentInTheProgramGetsANewOne() throws Exception {
    Map<String, Object> existing =
        OBJECT_MAPPER.readValue(
            """
            {"instances":[{"trackedEntity":"TeiExisting1","enrollments":[
              {"enrollment":"EnrOtherPrg1","program":"OTHERPROGRM","status":"ACTIVE","events":[]}
            ]}]}""",
            Map.class);
    JsonNode merged = merge(mappedPayload(), existing);
    assertEquals("TeiExisting1", te(merged).path("trackedEntity").asText());
    assertFalse(te(merged).path("enrollments").path(0).has("enrollment"));
    assertFalse(events(merged).path(0).has("event"));
  }

  @Test
  public void testNumericMappedValuesMatchStringValuesFromDhis2() throws Exception {
    // The mapping emits visit numbers as JSON numbers; DHIS2 returns data values as
    // strings. The match must not care.
    JsonNode merged = merge(mappedPayload(), existingWoman());
    JsonNode examVisit1 = events(merged).path(1);
    assertTrue(examVisit1.has("event"));
    assertNotEquals(
        examVisit1.path("dataValues").path(0).path("value").getNodeType().name(),
        "STRING",
        "mapped value stays numeric — only the comparison normalises");
  }
}
