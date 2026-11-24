package org.hisp.dhis.integration.emr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The mediator must never fall back to default DHIS2 credentials. */
class Dhis2ClientConfigTestCase {

  @Test
  void testMissingUsernameRefusesToStart() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> Dhis2ClientConfig.requireCredentials("", "secret"));
    assertTrue(e.getMessage().contains("DHIS2_USERNAME"));
  }

  @Test
  void testMissingPasswordRefusesToStart() {
    assertThrows(
        IllegalStateException.class,
        () -> Dhis2ClientConfig.requireCredentials("enable_mediator", "  "));
  }

  @Test
  void testNullCredentialsRefuseToStart() {
    assertThrows(
        IllegalStateException.class, () -> Dhis2ClientConfig.requireCredentials(null, null));
  }

  @Test
  void testConfiguredCredentialsAreAccepted() {
    assertDoesNotThrow(() -> Dhis2ClientConfig.requireCredentials("enable_mediator", "secret"));
  }
}
