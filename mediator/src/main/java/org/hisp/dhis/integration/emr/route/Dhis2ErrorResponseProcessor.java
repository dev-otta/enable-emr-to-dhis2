package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.hisp.dhis.integration.sdk.api.RemoteDhis2ClientException;
import org.springframework.stereotype.Component;

/**
 * Renders a DHIS2-side failure per the mediator's error contract:
 *
 * <ul>
 *   <li><b>409</b> when DHIS2 answered 409 (tracker import validation conflict — e.g. a mandatory
 *       attribute missing, a program rule, or an org-unit code that resolves to nothing under
 *       orgUnitIdScheme=CODE). DHIS2's own response body (the import report) is passed through so
 *       the EMR vendor sees the real reason.
 *   <li><b>502</b> for anything else (DHIS2 unreachable, 5xx, unexpected status).
 * </ul>
 */
@Component("dhis2ErrorResponse")
public class Dhis2ErrorResponseProcessor implements Processor {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public void process(Exchange exchange) throws Exception {
    Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    // The thrown exception may reach us wrapped (CamelExecutionException etc.) — walk
    // the cause chain the way Camel's own onException matching does.
    RemoteDhis2ClientException remote = Causes.find(cause, RemoteDhis2ClientException.class);

    int statusCode = 502;
    Object dhis2Response = null;
    if (remote != null) {
      if (remote.getHttpStatusCode() == 409) {
        statusCode = 409;
      }
      dhis2Response = tryParse(remote.getBody());
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "ERROR");
    body.put("error", "DHIS2 rejected the request");
    if (dhis2Response != null) {
      body.put("dhis2Response", dhis2Response);
    } else if (cause != null) {
      body.put("detail", cause.getMessage());
    }

    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, statusCode);
    exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
    exchange.getMessage().setBody(OBJECT_MAPPER.writeValueAsString(body));
  }

  private static Object tryParse(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return OBJECT_MAPPER.readValue(body, Object.class);
    } catch (Exception notJson) {
      return body;
    }
  }
}
