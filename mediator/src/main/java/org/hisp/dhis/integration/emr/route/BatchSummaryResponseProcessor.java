package org.hisp.dhis.integration.emr.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * Renders the batch response from the aggregated per-entry results: a summary (received /
 * imported / conflicts / invalid / rejected / errors) followed by the ordered results.
 *
 * <p>HTTP status: <b>200</b> when every entry imported, <b>207</b> (Multi-Status) when outcomes
 * are mixed — the summary and per-entry rows carry the detail, and entries are independent by
 * design: the EMR fixes and re-sends only its failures (or lazily re-sends the whole batch,
 * which the lookup-based idempotency makes harmless).
 */
@Component("batchSummaryResponse")
public class BatchSummaryResponseProcessor implements Processor {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  @SuppressWarnings("unchecked")
  public void process(Exchange exchange) throws Exception {
    List<Map<String, Object>> results = exchange.getMessage().getBody(List.class);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("received", results.size());
    summary.put("imported", count(results, "IMPORTED"));
    summary.put("conflicts", count(results, "CONFLICT"));
    summary.put("invalid", count(results, "INVALID"));
    summary.put("rejected", count(results, "REJECTED"));
    summary.put("errors", count(results, "ERROR"));

    boolean allImported = (long) results.size() == (long) summary.get("imported");
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("summary", summary);
    response.put("results", results);

    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, allImported ? 200 : 207);
    exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
    exchange.getMessage().setBody(OBJECT_MAPPER.writeValueAsString(response));
  }

  private static long count(List<Map<String, Object>> results, String status) {
    return results.stream().filter(r -> status.equals(r.get("status"))).count();
  }
}
