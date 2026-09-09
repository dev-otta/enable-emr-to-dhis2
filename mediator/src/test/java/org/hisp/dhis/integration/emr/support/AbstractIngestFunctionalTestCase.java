package org.hisp.dhis.integration.emr.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for the FAST functional suites (plain {@code mvn test}, no Docker): the booted mediator
 * pointed at the shared {@link Dhis2ApiStub} WireMock, reset to the happy-path defaults before
 * every test. Suites overlay test-specific stubs via {@code Dhis2ApiStub.server()}.
 */
public abstract class AbstractIngestFunctionalTestCase extends AbstractMediatorHttpTestCase {

  @DynamicPropertySource
  static void dhis2ApiUrl(DynamicPropertyRegistry registry) {
    registry.add("dhis2.api-url", () -> "http://localhost:" + Dhis2ApiStub.port() + "/api");
  }

  @BeforeEach
  void resetDhis2Stub() {
    Dhis2ApiStub.resetAndStubDefaults();
  }
}
