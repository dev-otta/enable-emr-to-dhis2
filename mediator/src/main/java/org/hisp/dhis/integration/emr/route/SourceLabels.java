package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;

/**
 * The "who pushed this" label the operator-facing log lines share: {@code
 * <source>-<facility org unit code>} (e.g. {@code pulsetech-1057888})
 */
final class SourceLabels {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private SourceLabels() {}

  static String of(Exchange exchange, String facility) {
    String source = exchange.getVariable("source", String.class);
    if (source == null) {
      source = "unknown-source";
    }
    return facility == null || facility.isBlank() ? source : source + "-" + facility;
  }

  /** The label with the facility read from the mapped tracker payload's org unit. */
  static String fromMappedPayload(Exchange exchange) {
    return of(exchange, orgUnitOf(exchange.getVariable("trackerPayload", String.class)));
  }

  private static String orgUnitOf(String trackerPayloadJson) {
    if (trackerPayloadJson == null) {
      return null;
    }
    try {
      JsonNode orgUnit =
          OBJECT_MAPPER.readTree(trackerPayloadJson).path("trackedEntities").path(0).path("orgUnit");
      return orgUnit.isTextual() ? orgUnit.asText() : null;
    } catch (Exception e) {
      return null;
    }
  }
}
