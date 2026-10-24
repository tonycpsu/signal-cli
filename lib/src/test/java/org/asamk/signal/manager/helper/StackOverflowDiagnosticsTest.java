package org.asamk.signal.manager.helper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackOverflowDiagnosticsTest {

    private static StackTraceElement frame(final String method) {
        return new StackTraceElement("com.example.Recursing", method, null, -1);
    }

    /** {@code cycle} repeated {@code repeats} times, deepest-last, as a real trace is ordered. */
    private static StackTraceElement[] periodic(final List<String> cycle, final int repeats) {
        return IntStream.range(0, repeats)
                .boxed()
                .flatMap(r -> cycle.stream())
                .map(StackOverflowDiagnosticsTest::frame)
                .toArray(StackTraceElement[]::new);
    }

    /** A periodic prefix above a non-recursive tail -- what a COMPLETE trace looks like. */
    private static StackTraceElement[] periodicThenOrigin(final List<String> cycle, final int repeats) {
        final var recursive = periodic(cycle, repeats);
        final var tail = new StackTraceElement[]{
                frame("sendGroupMessageInternalWithSenderKey"), frame("sendGroupMessage"), frame("run"),
        };
        final var all = new StackTraceElement[recursive.length + tail.length];
        System.arraycopy(recursive, 0, all, 0, recursive.length);
        System.arraycopy(tail, 0, all, recursive.length, tail.length);
        return all;
    }

    @Test
    void detectsMutualRecursionCycle() {
        final var frames = periodic(List.of("a", "b", "c"), 100);
        assertEquals(3, StackOverflowDiagnostics.findRepeatingCycleLength(frames));
    }

    @Test
    void detectsSelfRecursion() {
        final var frames = periodic(List.of("self"), 100);
        assertEquals(1, StackOverflowDiagnostics.findRepeatingCycleLength(frames));
    }

    @Test
    void detectsFatCycleOfManyFramesPerLevel() {
        final var cycle = IntStream.range(0, 40).mapToObj(i -> "layer" + i).toList();
        final var frames = periodic(cycle, 10);
        assertEquals(40, StackOverflowDiagnostics.findRepeatingCycleLength(frames));
    }

    /**
     * The regression this class exists for: when the runtime records the COMPLETE trace, the
     * deepest frames are the non-recursive origin. An implementation that demands periodicity over
     * the whole array finds no cycle here -- i.e. it fails precisely when the full trace was
     * available, and then reports "no cycle detected" about a trace that plainly contains one.
     */
    @Test
    void detectsCycleEvenWhenTraceReachesNonRecursiveOrigin() {
        final var frames = periodicThenOrigin(List.of("a", "b", "c"), 100);
        assertEquals(3, StackOverflowDiagnostics.findRepeatingCycleLength(frames));
        assertFalse(StackOverflowDiagnostics.looksTruncated(frames, 3),
                "a trace that reaches its origin must not be reported as truncated");
    }

    @Test
    void reportsTruncationWhenPeriodicityRunsToTheDeepestFrame() {
        final var frames = periodic(List.of("a", "b", "c"), 400);
        assertTrue(StackOverflowDiagnostics.looksTruncated(frames, 3),
                "periodicity running to the last recorded frame means the trace was cut mid-recursion");
    }

    @Test
    void findsNoCycleInNonRepeatingTrace() {
        final var frames = IntStream.range(0, 60)
                .mapToObj(i -> frame("distinct" + i))
                .toArray(StackTraceElement[]::new);
        assertEquals(0, StackOverflowDiagnostics.findRepeatingCycleLength(frames));
        assertFalse(StackOverflowDiagnostics.looksTruncated(frames, 0));
    }

    @Test
    void handlesEmptyAndDegenerateTraces() {
        assertEquals(0, StackOverflowDiagnostics.findRepeatingCycleLength(new StackTraceElement[0]));
        assertEquals(0, StackOverflowDiagnostics.findRepeatingCycleLength(null));
        assertFalse(StackOverflowDiagnostics.looksTruncated(new StackTraceElement[0], 3));
        assertEquals(List.of("no stack frames were recorded by the runtime"),
                StackOverflowDiagnostics.describe(new StackTraceElement[0]));
        // Too short to satisfy MIN_REPEATS, so no cycle should be claimed.
        assertEquals(0, StackOverflowDiagnostics.findRepeatingCycleLength(periodic(List.of("a", "b"), 2)));
    }

    /** A few repeats of a short period is not evidence; MIN_EVIDENCE_FRAMES guards against noise. */
    @Test
    void requiresSubstantialEvidenceBeforeClaimingAShortCycle() {
        assertEquals(0, StackOverflowDiagnostics.findRepeatingCycleLength(periodic(List.of("a"), 5)));
        assertEquals(1, StackOverflowDiagnostics.findRepeatingCycleLength(periodic(List.of("a"), 200)));
    }

    /**
     * A cycle repeated exactly MIN_REPEATS times must be accepted. The acceptance test compares
     * the verified SPAN, not the matched prefix -- comparing the prefix instead silently demands
     * one extra repetition, so the constant would not mean what its name says.
     */
    @Test
    void acceptsACycleRepeatedExactlyMinRepeatsTimes() {
        final var cycle = IntStream.range(0, 30).mapToObj(i -> "layer" + i).toList();
        final var frames = periodic(cycle, StackOverflowDiagnostics.MIN_REPEATS);
        assertEquals(30, StackOverflowDiagnostics.findRepeatingCycleLength(frames));
    }

    /**
     * A cycle containing a long run of identical frames must not be mistaken for a one-frame
     * cycle. Taking the first period that clears the bar picks period 1 here (the run is longer
     * than MIN_EVIDENCE_FRAMES) and then reports a truncated trace as complete, because period 1
     * stops matching at the frame that ends the run. The true period explains far more of the
     * stack, so selection has to be by longest explained span.
     */
    @Test
    void prefersTheCycleThatExplainsMostOfTheStackOverAShortSpuriousRun() {
        final var cycle = new java.util.ArrayList<String>();
        IntStream.range(0, 25).forEach(i -> cycle.add("sameFrame"));
        cycle.add("distinctTail");
        final var frames = periodic(cycle, 10);

        assertEquals(26, StackOverflowDiagnostics.findRepeatingCycleLength(frames),
                "a 26-frame cycle containing a 25-frame identical run must not report as period 1");
        assertTrue(StackOverflowDiagnostics.looksTruncated(frames, 26),
                "with the true period found, a fully periodic trace is correctly seen as truncated");
    }

    /** Cycles longer than MAX_PERIOD are undetectable by design; the summary must say so, not lie. */
    @Test
    void reportsNoCycleRatherThanGuessingWhenPeriodExceedsTheSearchBound() {
        final var cycle = IntStream.range(0, StackOverflowDiagnostics.MAX_PERIOD + 10)
                .mapToObj(i -> "layer" + i)
                .toList();
        final var frames = periodic(cycle, 5);
        assertEquals(0, StackOverflowDiagnostics.findRepeatingCycleLength(frames));
        assertTrue(String.join("\n", StackOverflowDiagnostics.describe(frames)).contains("no repeating frame cycle"));
    }

    @Test
    void boundsLogVolumeForALongCycle() {
        final var cycle = IntStream.range(0, 100).mapToObj(i -> "layer" + i).toList();
        final var lines = StackOverflowDiagnostics.describe(periodic(cycle, 6));
        final var cycleLines = lines.stream().filter(l -> l.contains("cycle[")).count();
        assertEquals(StackOverflowDiagnostics.MAX_CYCLE_LINES, cycleLines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("further cycle frames omitted")));
    }

    @Test
    void repeatCountIsNotExtrapolatedBeyondTheVerifiedPrefix() {
        // 100 clean repeats, then unrelated frames: the count must describe the 100, not the whole array.
        final var frames = periodicThenOrigin(List.of("a", "b", "c"), 100);
        final var summary = String.join("\n", StackOverflowDiagnostics.describe(frames));
        assertTrue(summary.contains("repeated at least 100 times"), summary.lines().findFirst().orElse(""));
    }
}
