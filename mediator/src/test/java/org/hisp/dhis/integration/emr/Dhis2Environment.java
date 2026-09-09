package org.hisp.dhis.integration.emr;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A REAL DHIS2 instance for the integration tests (*IT classes, run by failsafe in {@code mvn
 * verify}) — the Testcontainers pattern from dhis2/integration-dhis-rapidpro, simplified because
 * this repo already ships its authoritative state: the ENABLE dump provides all metadata
 * (including the relaxed mandatory flags), and the shipped packed expressions seed the datastore
 * exactly the way the compose seed container does.
 *
 * <p>Containers start once per JVM (shared across IT classes) and are reaped by Testcontainers
 * when the JVM exits. Overridable via system properties: {@code dhis2.test.image},
 * {@code dhis2.test.username}, {@code dhis2.test.password}.
 */
public final class Dhis2Environment {

  public static final String USERNAME = System.getProperty("dhis2.test.username", "admin");
  public static final String PASSWORD = System.getProperty("dhis2.test.password", "district");

  private static final String DHIS2_IMAGE =
      System.getProperty("dhis2.test.image", "dhis2/core:2.41.5.1");
  private static final String POSTGRES_IMAGE = "ghcr.io/baosystems/postgis:16-3.4";

  private static final Network NETWORK = Network.newNetwork();

  /** Same image, same dump, same alias ("db" — what dhis.conf points at) as docker-compose. */
  private static final GenericContainer<?> DB =
      new GenericContainer<>(POSTGRES_IMAGE)
          .withNetwork(NETWORK)
          .withNetworkAliases("db")
          .withEnv("POSTGRES_USER", "dhis")
          .withEnv("POSTGRES_DB", "dhis")
          .withEnv("POSTGRES_PASSWORD", "dhis")
          .withCopyFileToContainer(
              MountableFile.forHostPath("../db-dump/db-dump.sql.gz"),
              "/docker-entrypoint-initdb.d/db-dump.sql.gz")
          // the init phase loads the dump, then postgres restarts -> ready appears twice
          .waitingFor(
              Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                  .withStartupTimeout(Duration.ofMinutes(5)));

  private static final GenericContainer<?> DHIS2 =
      new GenericContainer<>(DHIS2_IMAGE)
          .withNetwork(NETWORK)
          .withCopyFileToContainer(
              MountableFile.forHostPath("../config/dhis2/dhis.conf"), "/opt/dhis2/dhis.conf")
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/dhis-web-login/")
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(5)));

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static String apiUrl;

  private Dhis2Environment() {}

  /** Starts (once) and returns the instance's API url, datastore already seeded. */
  public static synchronized String apiUrl() {
    if (apiUrl == null) {
      DB.start();
      DHIS2.start();
      apiUrl = "http://" + DHIS2.getHost() + ":" + DHIS2.getMappedPort(8080) + "/api";
      seedDatastore();
    }
    return apiUrl;
  }

  /** Writes the shipped packed expressions — what the compose seed container does. */
  private static void seedDatastore() {
    for (String source : List.of("pulsetech", "bahmni")) {
      for (String kind : List.of("envelope", "validation", "mapping")) {
        String namespace = "enable-emr-" + kind;
        Path file = Path.of("../config/datastore/" + source + "-" + kind + ".json");
        int status = send("PUT", "dataStore/" + namespace + "/" + source, file);
        if (status >= 400) {
          status = send("POST", "dataStore/" + namespace + "/" + source, file);
        }
        if (status >= 400) {
          throw new IllegalStateException(
              "Seeding " + namespace + "/" + source + " failed with HTTP " + status);
        }
      }
    }
  }

  private static int send(String method, String path, Path bodyFile) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl + "/" + path))
              .header("Content-Type", "application/json")
              .header("Authorization", basicAuth())
              .method(method, HttpRequest.BodyPublishers.ofString(Files.readString(bodyFile)))
              .build();
      return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Datastore seeding request failed: " + path, e);
    }
  }

  /** GET against the instance, for test assertions (returns the raw response body). */
  public static String get(String pathAndQuery) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl() + "/" + pathAndQuery))
              .header("Authorization", basicAuth())
              .GET()
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "GET " + pathAndQuery + " -> HTTP " + response.statusCode());
      }
      return response.body();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GET " + pathAndQuery + " failed", e);
    }
  }

  private static String basicAuth() {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
  }
}
