package org.asamk.signal.manager.helper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central sink for {@link StackOverflowError} diagnostics.
 * <p>
 * leakfix2 caught the overflow at two specific {@link SendHelper} group-send call sites. Production
 * kept crashing because the overflow also fires on a different path -- recipient resolution /
 * registration-marking on the background {@code [receive-N]} and {@code RxCachedThreadScheduler-*}
 * threads -- which those call-site catches never see. This class is the shared entry point the
 * global uncaught-exception handler and the receive/Rx dispatch guard both call, so the same
 * bounded recursion-cycle summary is emitted no matter which thread or code path blew the stack.
 * <p>
 * It is diagnostic only: it logs and returns. Callers rethrow; nothing here swallows the error or
 * alters the crash/recovery behaviour the ops wrapper depends on.
 */
public final class StackOverflowReporter {

    private static final Logger logger = LoggerFactory.getLogger(StackOverflowReporter.class);

    /** Greppable marker for every diagnostic line this class emits. */
    public static final String LOG_PREFIX = "SIGNAL-CLI SOE DIAGNOSTIC:";

    /** How far down a cause chain to look for a StackOverflowError; bounded in case the chain cycles. */
    private static final int MAX_CAUSE_DEPTH = 32;

    /**
     * Identity of the last throwable already described, so an overflow logged at a catch site and
     * then rethrown into the global uncaught-exception handler is not described twice for one event.
     * A single volatile slot -- no map, no allocation -- because the stack is already blown when
     * this runs: the rethrow reaches the handler on the same thread, and a rare double-log from a
     * concurrent overflow on another thread is harmless.
     */
    private static volatile Throwable lastReported;

    private StackOverflowReporter() {
    }

    /**
     * If {@code t} is, or wraps, a {@link StackOverflowError}, log a bounded description of the
     * recursion cycle at ERROR under {@link #LOG_PREFIX}. Does nothing for any other throwable, and
     * never throws -- logging under a blown stack can itself fail, and the diagnostic must never
     * mask or alter the crash. Callers are expected to rethrow after calling this.
     *
     * @param context short greppable note about where the overflow was observed (thread/pipeline)
     */
    public static void reportIfStackOverflow(final Throwable t, final String context) {
        final var soe = findStackOverflow(t);
        if (soe == null) {
            return;
        }
        if (t == lastReported || soe == lastReported) {
            return;
        }
        lastReported = t;
        try {
            logger.error("{} StackOverflowError on thread [{}]{}",
                    LOG_PREFIX,
                    Thread.currentThread().getName(),
                    context == null || context.isBlank() ? "" : " (" + context + ")");
            for (final var line : StackOverflowDiagnostics.describe(soe.getStackTrace())) {
                logger.error("{} {}", LOG_PREFIX, line);
            }
        } catch (Throwable loggingFailure) {
            // Never let a failure to log the diagnostic interfere with the crash path.
        }
    }

    private static StackOverflowError findStackOverflow(final Throwable t) {
        var current = t;
        for (var i = 0; i < MAX_CAUSE_DEPTH && current != null; i++) {
            if (current instanceof StackOverflowError soe) {
                return soe;
            }
            current = current.getCause();
        }
        return null;
    }
}
