package org.asamk.signal.manager.helper;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link StackOverflowError}'s recorded frames into a short, bounded description that
 * identifies the recursion.
 * <p>
 * A blown stack is potentially tens of thousands of frames, so dumping it whole helps nobody.
 * What is diagnostic is the <em>repeating cycle</em>: it names the methods that recurse, which is
 * exactly what a GraalVM native image's fatal-error handler does not report.
 * <p>
 * Note what this deliberately does <em>not</em> claim to give you: the recursion's depth. The
 * recorded frame count may be capped by the runtime, so it is only ever a lower bound and cannot
 * tell you whether the recursion grows linearly with the input size or faster.
 * <p>
 * Kept separate from {@link SendHelper} so it can be unit tested: every method here is pure, and
 * the interesting cases (a periodic prefix above non-periodic frames, a trace cut mid-recursion,
 * degenerate short traces) are easy to get wrong and hard to reproduce from a real overflow.
 * <p>
 * {@link #describe} is public so the global uncaught-exception handler and the receive/Rx dispatch
 * guard (both outside this package) can render the same bounded summary the send-side guards do.
 * The remaining statics stay package-private -- they exist only for the same-package unit test.
 */
public final class StackOverflowDiagnostics {

    /**
     * How far down to look when searching for the period. Bounded for cost: the search is
     * O(window x MAX_PERIOD), and a recursion cycle that isn't visible in the top few hundred
     * frames won't be identified by eyeballing the dump either.
     */
    static final int SEARCH_WINDOW = 600;

    /** Longest cycle we try to detect. Deep layer chains (codec/interceptor stacks) can be long. */
    static final int MAX_PERIOD = 120;

    /**
     * A period must span at least this many whole repetitions before we believe it. Compared
     * against the verified <em>span</em> (matched prefix + one period), not the matched prefix
     * alone -- a cycle repeated exactly this many times covers {@code period * MIN_REPEATS}
     * frames but only yields {@code period * (MIN_REPEATS - 1)} matches.
     */
    static final int MIN_REPEATS = 3;

    /** ...and cover at least this many frames, so a 1-frame period needs real evidence. */
    static final int MIN_EVIDENCE_FRAMES = 24;

    /** Cap on how many cycle frames we print, so one occurrence can't flood the log. */
    static final int MAX_CYCLE_LINES = 20;

    static final int TOP_FRAMES = 25;
    static final int BOTTOM_FRAMES = 15;

    private StackOverflowDiagnostics() {
    }

    /**
     * Length of the shortest repeating frame cycle at the <em>top</em> of the stack, or 0 if none
     * is evident.
     * <p>
     * The periodicity only has to hold over a prefix, not the whole array. That matters: when the
     * runtime records the complete trace, the deepest frames are the non-recursive origin (the
     * caller, the thread's entry point) and are not periodic. Demanding periodicity everywhere
     * would find no cycle in exactly the case where the full trace was available.
     */
    static int findRepeatingCycleLength(final StackTraceElement[] frames) {
        if (frames == null || frames.length == 0) {
            return 0;
        }
        final var window = Math.min(frames.length, SEARCH_WINDOW);
        var best = 0;
        var bestSpan = 0;
        for (var period = 1; period <= effectiveMaxPeriod(frames); period++) {
            // Verified span = the matched prefix plus the one period that prefix implies.
            final var span = matchedPrefixLength(frames, period, window) + period;
            if (span < Math.max(period * MIN_REPEATS, MIN_EVIDENCE_FRAMES)) {
                continue;
            }
            // Take the period that explains the MOST of the stack, not merely the first that
            // clears the bar. Shortest-first is wrong: a cycle that happens to contain a long run
            // of identical frames (say [a x25, b]) would match period 1 across that run and be
            // reported as a one-frame cycle, which is both wrong and confidently stated -- and it
            // would then invert looksTruncated, since period 1 stops matching at the 'b'. The true
            // period explains a longer span, so it wins. Ties break toward the shorter period,
            // which discards exact multiples of the true period (they always span one period less).
            if (span > bestSpan) {
                bestSpan = span;
                best = period;
            }
        }
        return best;
    }

    /** The largest period this search can actually consider for a trace of this length. */
    static int effectiveMaxPeriod(final StackTraceElement[] frames) {
        if (frames == null || frames.length == 0) {
            return 0;
        }
        final var window = Math.min(frames.length, SEARCH_WINDOW);
        return Math.min(MAX_PERIOD, window / MIN_REPEATS);
    }

    /**
     * How many leading frames satisfy {@code frames[i]} equals {@code frames[i + period]}, looking
     * no further than {@code limit} frames in.
     */
    static int matchedPrefixLength(final StackTraceElement[] frames, final int period, final int limit) {
        if (frames == null || period <= 0) {
            return 0;
        }
        final var end = Math.min(limit, frames.length) - period;
        var i = 0;
        while (i < end && isSameFrame(frames[i], frames[i + period])) {
            i++;
        }
        return i;
    }

    /**
     * Whether the trace looks cut off mid-recursion rather than reaching the real origin.
     * <p>
     * If the periodic prefix runs all the way to the deepest recorded frame, there is no
     * non-recursive tail, which is what you see when the runtime stopped recording early. If the
     * periodicity stops short, the frames below it are the genuine origin and the trace is
     * complete. Measured over the whole array rather than the search window, so the answer does
     * not silently change if {@link #SEARCH_WINDOW} is retuned.
     */
    static boolean looksTruncated(final StackTraceElement[] frames, final int cycleLength) {
        if (frames == null || frames.length == 0 || cycleLength <= 0) {
            return false;
        }
        return matchedPrefixLength(frames, cycleLength, frames.length) + cycleLength >= frames.length;
    }

    /**
     * A bounded, human-readable summary: the cycle (if any), then a window of the top frames and,
     * for long traces, a window of the deepest ones. Returned as lines so the caller decides the
     * log level and the test can assert on content.
     */
    public static List<String> describe(final StackTraceElement[] frames) {
        final var lines = new ArrayList<String>();
        if (frames == null || frames.length == 0) {
            lines.add("no stack frames were recorded by the runtime");
            return lines;
        }

        final var cycleLength = findRepeatingCycleLength(frames);
        if (cycleLength > 0) {
            // Report repeats over the span actually verified, not frames.length -- extrapolating
            // past the verified prefix would be inventing a number.
            final var verified = matchedPrefixLength(frames, cycleLength, frames.length) + cycleLength;
            lines.add("recursion cycle: %d frames, repeated at least %d times".formatted(cycleLength,
                    verified / cycleLength));
            final var shown = Math.min(cycleLength, MAX_CYCLE_LINES);
            for (var i = 0; i < shown; i++) {
                lines.add("  cycle[%d] %s".formatted(i, frames[i]));
            }
            if (shown < cycleLength) {
                lines.add("  ... %d further cycle frames omitted".formatted(cycleLength - shown));
            }
        } else {
            // Report the bound that actually applied, not the constant: for a short trace the
            // effective bound is window/MIN_REPEATS, well below MAX_PERIOD, and saying otherwise
            // would overstate how wide a search failed to find anything.
            lines.add("no repeating frame cycle detected in the top %d frames (searched periods up to %d)".formatted(
                    Math.min(frames.length, SEARCH_WINDOW),
                    effectiveMaxPeriod(frames)));
        }

        appendWindow(lines, "top", frames, 0, Math.min(frames.length, TOP_FRAMES));
        if (frames.length > TOP_FRAMES + BOTTOM_FRAMES) {
            appendWindow(lines, "bottom", frames, frames.length - BOTTOM_FRAMES, frames.length);
        }
        return lines;
    }

    private static void appendWindow(
            final List<String> lines,
            final String label,
            final StackTraceElement[] frames,
            final int from,
            final int to
    ) {
        lines.add("%s frames [%d..%d):".formatted(label, from, to));
        for (var i = from; i < to; i++) {
            lines.add("  %s".formatted(frames[i]));
        }
    }

    /** Frames are compared by class and method only: a native image often carries no line numbers. */
    static boolean isSameFrame(final StackTraceElement a, final StackTraceElement b) {
        return a.getClassName().equals(b.getClassName()) && a.getMethodName().equals(b.getMethodName());
    }
}
