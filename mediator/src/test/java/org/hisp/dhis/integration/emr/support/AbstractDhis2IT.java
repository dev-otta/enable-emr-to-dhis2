package org.hisp.dhis.integration.emr.support;

import org.hisp.dhis.integration.emr.Dhis2Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the integration suites (*IT — {@code mvn verify}, Docker required): the booted
 * mediator pointed at a REAL DHIS2 loaded from the ENABLE dump ({@link Dhis2Environment},
 * started once per JVM, datastore pre-seeded with the shipped expressions).
 *
 * <p>These suites prove what a stub structurally cannot: DHIS2's actual import semantics —
 * org-unit code resolution, the (dump-carried) relaxed mandatory flags, program rules, SCHEDULE
 * event behaviour, and true re-push idempotency against a real database.
 */
public abstract class AbstractDhis2IT extends AbstractMediatorHttpTestCase {

  @DynamicPropertySource
  static void dhis2Connection(DynamicPropertyRegistry registry) {
    registry.add("dhis2.api-url", Dhis2Environment::apiUrl);
    registry.add("dhis2.username", () -> Dhis2Environment.USERNAME);
    registry.add("dhis2.password", () -> Dhis2Environment.PASSWORD);
  }
}
