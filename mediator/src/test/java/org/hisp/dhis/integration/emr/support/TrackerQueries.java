package org.hisp.dhis.integration.emr.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.hisp.dhis.integration.emr.Dhis2Environment;

/**
 * Read-side assertions against the REAL DHIS2 instance, for the *IT suites. Women are found
 * exactly the way the mediator's idempotency lookup finds them — by MRN attribute + program —
 * because every UID is DHIS2-generated and nothing may be assumed about it.
 */
public final class TrackerQueries {

  public static final String PROGRAM = "WSGAb5XwJ3Y";
  public static final String MRN_ATTRIBUTE = "OYuDdqr2MvX";
  public static final String PROFILE_STAGE = "iF5roNU7QWm";
  public static final String EXAM_STAGE = "JqW7c9HYjVr";
  public static final String VISIT_NUMBER_DE = "bXVD2EMF7UW";

  private static final String FIELDS =
      "trackedEntity,attributes[attribute,value],enrollments[enrollment,status,"
          + "events[event,programStage,status,occurredAt,scheduledAt,dataValues[dataElement,value]]]";

  private TrackerQueries() {}

  public static List<JsonNode> findByMrn(String mrn) {
    String body =
        Dhis2Environment.get(
            "tracker/trackedEntities?program=" + PROGRAM + "&ouMode=ACCESSIBLE"
                + "&filter=" + MRN_ATTRIBUTE + ":eq:" + mrn
                + "&fields=" + FIELDS + "&paging=false");
    try {
      JsonNode root = TestPayloads.OBJECT_MAPPER.readTree(body);
      JsonNode instances = root.has("instances") ? root.get("instances") : root.get("trackedEntities");
      List<JsonNode> result = new ArrayList<>();
      if (instances != null) {
        instances.forEach(result::add);
      }
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException("parsing tracker response", e);
    }
  }

  public static String attributeValue(JsonNode trackedEntity, String attributeUid) {
    for (JsonNode attribute : trackedEntity.path("attributes")) {
      if (attributeUid.equals(attribute.path("attribute").asText())) {
        return attribute.path("value").asText();
      }
    }
    return null;
  }

  public static List<JsonNode> events(JsonNode trackedEntity) {
    List<JsonNode> result = new ArrayList<>();
    trackedEntity.path("enrollments").path(0).path("events").forEach(result::add);
    return result;
  }

  public static String dataValue(JsonNode event, String dataElementUid) {
    for (JsonNode dv : event.path("dataValues")) {
      if (dataElementUid.equals(dv.path("dataElement").asText())) {
        return dv.path("value").asText();
      }
    }
    return null;
  }

  /** The single event carrying this visit number, or null. */
  public static JsonNode eventWithVisitNumber(JsonNode trackedEntity, String visitNumber) {
    for (JsonNode event : events(trackedEntity)) {
      if (visitNumber.equals(dataValue(event, VISIT_NUMBER_DE))) {
        return event;
      }
    }
    return null;
  }

  /**
   * The open SCHEDULE placeholder (at most one per enrollment by construction), or null. It
   * carries no visit number — DHIS2 forbids data values on SCHEDULE events (E1315) — so status is
   * the only way to find it.
   */
  public static JsonNode scheduledEvent(JsonNode trackedEntity) {
    for (JsonNode event : events(trackedEntity)) {
      if ("SCHEDULE".equals(event.path("status").asText())) {
        return event;
      }
    }
    return null;
  }
}
