package org.hisp.dhis.integration.emr.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Collects the per-entry result objects produced by {@link BatchEntryOutcomeProcessor} into one
 * list, in entry order — the {@code results} array of the batch response.
 */
@Component("batchResultAggregation")
public class BatchResultAggregationStrategy implements AggregationStrategy {

  @Override
  @SuppressWarnings("unchecked")
  public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
    Map<String, Object> entryResult = newExchange.getMessage().getBody(Map.class);
    if (oldExchange == null) {
      List<Map<String, Object>> results = new ArrayList<>();
      results.add(entryResult);
      newExchange.getMessage().setBody(results);
      return newExchange;
    }
    oldExchange.getMessage().getBody(List.class).add(entryResult);
    return oldExchange;
  }
}
