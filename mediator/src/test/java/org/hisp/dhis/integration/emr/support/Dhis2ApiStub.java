package org.hisp.dhis.integration.emr.support;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;

/**
 * ONE WireMock standing in for the whole DHIS2 API, shared by all fast functional suites (started
 * once per JVM; Testcontainers-based *IT classes use the real thing instead — {@link
 * org.hisp.dhis.integration.emr.Dhis2Environment}).
 *
 * <p>{@link #resetAndStubDefaults()} restores the happy path: datastore reads answering with the
 * SHIPPED packed expressions (all three per source), an empty idempotency lookup, and an OK
 * import report. Tests overlay their own stubs via {@link #server()}.
 */
public final class Dhis2ApiStub {

  public static final String OK_IMPORT_REPORT =
      "{\"status\":\"OK\",\"stats\":{\"created\":1,\"updated\":0,\"deleted\":0,\"ignored\":0}}";

  private static final WireMockServer SERVER;

  static {
    SERVER = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    SERVER.start();
  }

  private Dhis2ApiStub() {}

  public static WireMockServer server() {
    return SERVER;
  }

  public static int port() {
    return SERVER.port();
  }

  /** The happy-path baseline every test starts from. */
  public static void resetAndStubDefaults() {
    SERVER.resetAll();
    for (String source : List.of("pulsetech", "bahmni")) {
      for (String kind : List.of("envelope", "validation", "mapping")) {
        SERVER.stubFor(
            get(urlPathEqualTo("/api/dataStore/enable-emr-" + kind + "/" + source))
                .willReturn(
                    okJson(TestPayloads.read("config/datastore/" + source + "-" + kind + ".json"))));
      }
    }
    SERVER.stubFor(
        get(urlPathEqualTo("/api/tracker/trackedEntities"))
            .willReturn(okJson("{\"instances\":[]}")));
    SERVER.stubFor(post(urlPathEqualTo("/api/tracker")).willReturn(okJson(OK_IMPORT_REPORT)));
  }

  // ---- request journals ------------------------------------------------------------

  public static List<LoggedRequest> trackerRequests() {
    return SERVER.findAll(postRequestedFor(urlPathEqualTo("/api/tracker")));
  }

  public static List<LoggedRequest> lookupRequests() {
    return SERVER.findAll(getRequestedFor(urlPathEqualTo("/api/tracker/trackedEntities")));
  }

  public static List<LoggedRequest> datastoreReads(String kind, String source) {
    return SERVER.findAll(
        getRequestedFor(urlPathEqualTo("/api/dataStore/enable-emr-" + kind + "/" + source)));
  }
}
