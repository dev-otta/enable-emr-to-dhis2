package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The idempotent-upsert merge: combines the mapped tracker payload (which carries NO identifiers
 * — DHIS2 owns UID generation) with the result of the tracked-entity lookup.
 *
 * <p>If the lookup found nobody, the payload passes through untouched and DHIS2 creates the
 * tracked entity, enrollment and events, generating every UID itself. If the woman exists, this
 * processor injects her existing UIDs so DHIS2's CREATE_AND_UPDATE import UPDATES instead of
 * duplicating:
 *
 * <ul>
 *   <li><b>Tracked entity</b>: the UID of the (single — the MRN attribute is unique) match.
 *   <li><b>Enrollment</b>: the ACTIVE enrollment in the payload's program, else the first one;
 *       none found → left blank so DHIS2 opens a new enrollment on the existing woman.
 *   <li><b>Events</b>: matched per program stage. Two events are "the same visit" when both carry
 *       the configured match data element ({@code dhis2.tracker.event-match-data-element}, the ANC
 *       visit number) with equal values. Events without the match element fall back to same-date
 *       matching (which covers the non-repeatable Woman's Profile stage: same stage = same event).
 *       A stage's open SCHEDULE placeholder — which can carry NO data values (DHIS2's E1315), so
 *       never a visit number — is claimed by the first real visit that found no exact match
 *       (attendance fulfils the appointment: the placeholder BECOMES the visit) or by an incoming
 *       placeholder whose date moved (a reschedule updates in place). Unmatched mapped events
 *       stay UID-less and are created; each existing event is matched at most once.
 * </ul>
 *
 * <p>Completely source-agnostic: no EMR field names, only two configured DHIS2 UIDs.
 */
@Component("existingTrackedEntityMerge")
public class ExistingTrackedEntityMergeProcessor implements Processor {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ExistingTrackedEntityMergeProcessor.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String eventMatchDataElement;

  public ExistingTrackedEntityMergeProcessor(
      @Value("${dhis2.tracker.event-match-data-element}") String eventMatchDataElement) {
    this.eventMatchDataElement = eventMatchDataElement;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void process(Exchange exchange) throws Exception {
    Map<String, Object> lookupResult = exchange.getMessage().getBody(Map.class);
    ObjectNode payload =
        (ObjectNode) OBJECT_MAPPER.readTree(exchange.getVariable("trackerPayload", String.class));

    JsonNode matches = matches(lookupResult);
    if (matches.size() > 0) {
      if (matches.size() > 1) {
        LOGGER.warn(
            "Lookup returned {} tracked entities for one MRN — the identity attribute should be "
                + "unique. Merging into the first match.",
            matches.size());
      }
      merge(payload, matches.get(0));
    }

    exchange.getMessage().setBody(OBJECT_MAPPER.writeValueAsString(payload));
  }

  private static JsonNode matches(Map<String, Object> lookupResult) {
    JsonNode root = OBJECT_MAPPER.valueToTree(lookupResult == null ? Map.of() : lookupResult);
    return root.hasNonNull("instances") ? root.path("instances") : root.path("trackedEntities");
  }

  private void merge(ObjectNode payload, JsonNode existing) {
    ObjectNode trackedEntity = (ObjectNode) payload.path("trackedEntities").path(0);
    trackedEntity.put("trackedEntity", existing.path("trackedEntity").asText());

    ObjectNode enrollment = (ObjectNode) trackedEntity.path("enrollments").path(0);
    JsonNode existingEnrollment =
        selectEnrollment(existing.path("enrollments"), enrollment.path("program").asText());
    if (existingEnrollment == null) {
      return; // existing woman, no matching enrollment: DHIS2 opens a new one
    }
    enrollment.put("enrollment", existingEnrollment.path("enrollment").asText());

    Set<String> claimed = new HashSet<>();
    for (JsonNode mappedEvent : enrollment.path("events")) {
      JsonNode match = findEvent(existingEnrollment.path("events"), mappedEvent, claimed);
      if (match != null) {
        claimed.add(match.path("event").asText());
        ((ObjectNode) mappedEvent).put("event", match.path("event").asText());
      }
    }
  }

  /** The ACTIVE enrollment in the payload's program, else the first enrollment in it. */
  private static JsonNode selectEnrollment(JsonNode existingEnrollments, String program) {
    JsonNode first = null;
    for (JsonNode enrollment : existingEnrollments) {
      if (!program.equals(enrollment.path("program").asText())) {
        continue;
      }
      if ("ACTIVE".equals(enrollment.path("status").asText())) {
        return enrollment;
      }
      if (first == null) {
        first = enrollment;
      }
    }
    return first;
  }

  private JsonNode findEvent(JsonNode existingEvents, JsonNode mappedEvent, Set<String> claimed) {
    String stage = mappedEvent.path("programStage").asText();
    String mappedKey = dataValue(mappedEvent, eventMatchDataElement);
    // Pass 1: the exact match — same stage and equal visit numbers, or (for events
    // that carry no visit number, like the placeholder and the non-repeatable
    // profile) same stage and same date.
    for (JsonNode existingEvent : existingEvents) {
      if (skip(existingEvent, stage, claimed)) {
        continue;
      }
      String existingKey = dataValue(existingEvent, eventMatchDataElement);
      boolean sameVisit =
          (mappedKey != null && mappedKey.equals(existingKey))
              || (mappedKey == null && existingKey == null && sameDate(mappedEvent, existingEvent));
      if (sameVisit) {
        return existingEvent;
      }
    }
    // Pass 2: the stage's open SCHEDULE placeholder is claimed by (a) a real visit
    // with no exact match — attendance fulfils the appointment and the placeholder
    // BECOMES the visit — or (b) an incoming placeholder whose date moved — a
    // reschedule updates it in place instead of duplicating it. (SCHEDULE events
    // cannot carry data values — DHIS2's E1315 — so the placeholder never has a
    // visit number to match on in pass 1; by construction there is at most one
    // open placeholder per stage.)
    boolean mappedIsPlaceholder = "SCHEDULE".equals(mappedEvent.path("status").asText());
    if (mappedKey != null || mappedIsPlaceholder) {
      for (JsonNode existingEvent : existingEvents) {
        if (skip(existingEvent, stage, claimed)) {
          continue;
        }
        if ("SCHEDULE".equals(existingEvent.path("status").asText())) {
          return existingEvent;
        }
      }
    }
    return null;
  }

  private static boolean skip(JsonNode existingEvent, String stage, Set<String> claimed) {
    return claimed.contains(existingEvent.path("event").asText())
        || !stage.equals(existingEvent.path("programStage").asText());
  }

  private static String dataValue(JsonNode event, String dataElement) {
    for (JsonNode dataValue : event.path("dataValues")) {
      if (dataElement.equals(dataValue.path("dataElement").asText())) {
        return dataValue.path("value").asText();
      }
    }
    return null;
  }

  /** Compares the events' dates (occurredAt, else scheduledAt), on the date part only. */
  private static boolean sameDate(JsonNode a, JsonNode b) {
    String dateA = eventDate(a);
    String dateB = eventDate(b);
    return dateA != null && dateA.equals(dateB);
  }

  private static String eventDate(JsonNode event) {
    String date = event.path("occurredAt").asText(null);
    if (date == null || date.isBlank()) {
      date = event.path("scheduledAt").asText(null);
    }
    return date == null || date.length() < 10 ? date : date.substring(0, 10);
  }
}
