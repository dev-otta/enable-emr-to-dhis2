package org.hisp.dhis.integration.emr.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The shared test inputs: the site API keys from application-test.yaml and readers for the
 * SHIPPED artifacts (emr-mocks samples, packed datastore expressions) — every layer tests the
 * exact files that ship, never copies that can drift.
 */
public final class TestPayloads {

  public static final String PULSETECH_KEY = "test-pulsetech-key-0001";
  public static final String BAHMNI_KEY = "test-bahmni-key-000001";

  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TestPayloads() {}

  /** Reads a repo file (path relative to the repo root, e.g. "emr-mocks/bahmni/anc-record.json"). */
  public static String read(String repoRelativePath) {
    try {
      return Files.readString(Path.of("../" + repoRelativePath), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(repoRelativePath, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> readJson(String repoRelativePath) {
    try {
      return OBJECT_MAPPER.readValue(read(repoRelativePath), Map.class);
    } catch (IOException e) {
      throw new UncheckedIOException(repoRelativePath, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> readJsonList(String repoRelativePath) {
    try {
      return OBJECT_MAPPER.readValue(read(repoRelativePath), List.class);
    } catch (IOException e) {
      throw new UncheckedIOException(repoRelativePath, e);
    }
  }

  public static String toJson(Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (IOException e) {
      throw new UncheckedIOException("serializing test payload", e);
    }
  }
}
