package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regenerates the golden tracker-payload fixtures from the SHIPPED artifacts — the packed
 * mappings in config/datastore/ applied to the samples in emr-mocks/. Skipped in normal runs;
 * activated by {@code yarn fixtures:update} (i.e. {@code -Dfixtures.write=true}).
 *
 * <p>This is what keeps a contract change (a new field, a mandatory-ness decision) a THREE-file
 * edit — mapping/rules, sample, done — instead of a cascade: fixtures are DERIVED, never
 * hand-maintained. Review the regenerated diff like any other code change; the other tests then
 * hold the new truth in place.
 */
public class FixtureRegeneratorTestCase extends AbstractDataSonnetTestCase {

  private static final Path FIXTURES = Path.of("src/test/resources");

  @Test
  void regenerateGoldenFixtures() throws IOException {
    assumeTrue(Boolean.getBoolean("fixtures.write"), "run via `yarn fixtures:update`");

    regenerate("pulsetech-mapping.json", "pulsetech/anc-record.json",
        "expected-pulsetech-tracker-payload.json");
    regenerate("pulsetech-mapping.json", "pulsetech/anc-record-single-visit.json",
        "expected-pulsetech-single-visit-tracker-payload.json");
    regenerate("bahmni-mapping.json", "bahmni/anc-record.json",
        "expected-bahmni-tracker-payload.json");
  }

  private static void regenerate(String mapping, String sample, String fixture)
      throws IOException {
    Map<String, Object> payload = evaluate(readDatastoreExpression(mapping), readEmrSample(sample));
    Files.writeString(
        FIXTURES.resolve(fixture),
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload) + "\n");
    System.out.println("regenerated " + fixture + "  <-  " + mapping + " (" + sample + ")");
  }
}
