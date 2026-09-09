package org.hisp.dhis.integration.emr.security;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The registry of EMR sources, bound from configuration: {@code api.keys.<source> = <site key>}.
 *
 * <p>This map IS the list of enabled pipelines. A source with a configured key exists — it gets an
 * ingest endpoint ({@code POST /api/<source>/anc-record}), a security rule, and its two datastore
 * expressions are fetched by name. A source with a blank key simply does not exist: no endpoint,
 * no role, nothing to misconfigure. Onboarding a new EMR is therefore configuration, not code:
 * set {@code API_KEYS_<SOURCE>} (Spring relaxed binding) or add an entry under {@code api.keys},
 * and seed the source's validation + mapping expressions into the datastore.
 *
 * <p>For the POC, only PulseTech is enabled by default; Bahmni stays configured-but-empty until
 * its export shape is confirmed.
 */
@ConfigurationProperties(prefix = "api")
public class ApiKeysProperties {

  /** source name -> site API key; blank values mean "source disabled". */
  private Map<String, String> keys = new HashMap<>();

  public Map<String, String> getKeys() {
    return keys;
  }

  public void setKeys(Map<String, String> keys) {
    this.keys = keys;
  }

  /** The enabled sources: configured keys that are non-blank, in stable order. */
  public Map<String, String> enabledKeys() {
    Map<String, String> enabled = new LinkedHashMap<>();
    keys.forEach(
        (source, key) -> {
          if (key != null && !key.isBlank()) {
            enabled.put(source.toLowerCase(java.util.Locale.ROOT), key);
          }
        });
    return enabled;
  }
}
