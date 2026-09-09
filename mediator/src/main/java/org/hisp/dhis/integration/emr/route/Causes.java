package org.hisp.dhis.integration.emr.route;

/**
 * Cause-chain lookup for the response processors. Camel frequently hands over a thrown exception
 * wrapped (e.g. in a CamelExecutionException), so a plain {@code instanceof} on
 * {@code Exchange.EXCEPTION_CAUGHT} misses the real cause — exactly the mistake that turns a DHIS2
 * 409 into a generic 502. Camel's own onException/doCatch matching walks the cause chain; these
 * processors must do the same.
 */
final class Causes {

  private Causes() {}

  /** Returns the first exception of the given type in the cause chain, or null. */
  static <T extends Throwable> T find(Throwable root, Class<T> type) {
    for (Throwable t = root; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
      if (t.getCause() == t) {
        break; // self-referencing cause — stop
      }
    }
    return null;
  }
}
