package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prepares the idempotency lookup: does this woman already exist in DHIS2?
 *
 * <p>The mediator NEVER generates identifiers — DHIS2 owns UID generation. Instead, before every
 * import, the mediator asks the tracker API whether a tracked entity with this record's MRN
 * already exists in the target program. This processor builds that query from the MAPPED payload
 * (in the {@code trackerPayload} exchange variable), so it stays completely source-agnostic: the
 * program UID is read from the payload's enrollment, the MRN from the configured identity
 * attribute ({@code dhis2.tracker.identity-attribute}).
 *
 * <p>The query it prepares: {@code GET /api/tracker/trackedEntities?program=<program>
 * &ouMode=ACCESSIBLE&filter=<identityAttribute>:eq:<mrn>&fields=...}. Note ouMode=ACCESSIBLE:
 * the mediator's DHIS2 service account must be able to SEARCH across the participating org
 * units, or lookups will miss and duplicates appear (see docs/REFERENCE.md §6).
 */
@Component("trackedEntityLookupRequest")
public class TrackedEntityLookupRequestProcessor implements Processor {

  // The merge needs every field it matches on: the visit-number data value, the
  // dates (fallback match), and the event STATUS — pass 2 finds the open SCHEDULE
  // placeholder by status alone, since SCHEDULE events can carry no data values
  // (E1315). Dropping a field here silently breaks matching and duplicates events.
  static final String LOOKUP_FIELDS =
      "trackedEntity,enrollments[enrollment,program,status,"
          + "events[event,programStage,status,occurredAt,scheduledAt,"
          + "dataValues[dataElement,value]]]";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String identityAttribute;

  public TrackedEntityLookupRequestProcessor(
      @Value("${dhis2.tracker.identity-attribute}") String identityAttribute) {
    this.identityAttribute = identityAttribute;
  }

  @Override
  public void process(Exchange exchange) throws Exception {
    JsonNode payload =
        OBJECT_MAPPER.readTree(exchange.getVariable("trackerPayload", String.class));
    JsonNode trackedEntity = payload.path("trackedEntities").path(0);

    String program = trackedEntity.path("enrollments").path(0).path("program").asText("");
    String mrn = null;
    for (JsonNode attribute : trackedEntity.path("attributes")) {
      if (identityAttribute.equals(attribute.path("attribute").asText())) {
        mrn = attribute.path("value").asText();
      }
    }
    if (mrn == null || mrn.isBlank() || program.isBlank()) {
      throw new IllegalStateException(
          "Mapped payload carries no identity attribute ("
              + identityAttribute
              + ") or no program — cannot enforce idempotency. Check the source's mapping "
              + "against dhis2.tracker.identity-attribute.");
    }

    Map<String, List<String>> queryParams = new LinkedHashMap<>();
    queryParams.put("program", List.of(program));
    queryParams.put("ouMode", List.of("ACCESSIBLE"));
    queryParams.put("filter", List.of(identityAttribute + ":eq:" + mrn));
    queryParams.put("fields", List.of(LOOKUP_FIELDS));
    queryParams.put("paging", List.of("false"));
    exchange.getMessage().setHeader("CamelDhis2.queryParams", queryParams);
  }
}
