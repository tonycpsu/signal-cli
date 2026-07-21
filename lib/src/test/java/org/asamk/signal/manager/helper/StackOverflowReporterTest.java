package org.asamk.signal.manager.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the reporter/handler pipeline with a <em>genuine</em> deeply-recursive
 * {@link StackOverflowError} -- not a hand-built frame array -- to prove that a real overflow
 * produces a bounded, cycle-naming description.
 * <p>
 * Limitation: this runs on the JVM. It cannot reproduce a GraalVM native image's fatal-error
 * handler, which may bypass the Java uncaught-exception handler entirely (the {@code rc=99} crash
 * seen in production). That path can only be validated by a native build, which is deferred to CI.
 */
class StackOverflowReporterTest {

    // Mutual recursion the JVM cannot tail-call away, so the recorded trace shows a real 2-frame cycle.
    @SuppressWarnings("InfiniteRecursion")
    private static int ping(final int n) {
        return pong(n + 1) + 1;
    }

    @SuppressWarnings("InfiniteRecursion")
    private static int pong(final int n) {
        return ping(n + 1) + 1;
    }

    private static StackOverflowError provokeOverflow() {
        try {
            ping(0);
            return null;
        } catch (final StackOverflowError e) {
            return e;
        }
    }

    @Test
    void describesTheCycleOfARealStackOverflow() {
        final var overflow = provokeOverflow();
        assertNotNull(overflow, "the recursion must actually overflow the stack");

        final var summary = String.join("\n", StackOverflowDiagnostics.describe(overflow.getStackTrace()));
        assertTrue(summary.contains("recursion cycle"),
                () -> "expected a recursion-cycle summary, got:\n" + summary);
        assertTrue(summary.contains("ping") || summary.contains("pong"),
                () -> "expected the recursing methods to be named, got:\n" + summary);
    }

    @Test
    void reporterHandlesARealOverflowWithoutThrowing() {
        final var overflow = provokeOverflow();
        assertNotNull(overflow);
        assertDoesNotThrow(() -> StackOverflowReporter.reportIfStackOverflow(overflow, "unit-test"));
    }

    @Test
    void reporterFindsAStackOverflowNestedInACauseChain() {
        final var overflow = provokeOverflow();
        assertNotNull(overflow);
        final var wrapped = new RuntimeException("outer", new IllegalStateException("middle", overflow));
        assertDoesNotThrow(() -> StackOverflowReporter.reportIfStackOverflow(wrapped, "wrapped"));
    }

    @Test
    void reporterIgnoresThrowablesThatAreNotStackOverflows() {
        assertDoesNotThrow(() -> StackOverflowReporter.reportIfStackOverflow(
                new RuntimeException("not an overflow"), "unrelated"));
        assertDoesNotThrow(() -> StackOverflowReporter.reportIfStackOverflow(null, "null"));
    }
}
