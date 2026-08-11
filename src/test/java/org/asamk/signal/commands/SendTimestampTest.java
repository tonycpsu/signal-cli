package org.asamk.signal.commands;

import net.sourceforge.argparse4j.inf.Namespace;

import org.asamk.signal.commands.exceptions.CommandException;
import org.asamk.signal.commands.exceptions.UserErrorException;
import org.asamk.signal.manager.Manager;
import org.asamk.signal.manager.api.Message;
import org.asamk.signal.manager.api.SendMessageResults;
import org.asamk.signal.output.PlainTextWriter;
import org.asamk.signal.output.PlainTextWriterImpl;
import org.asamk.signal.testutil.ManagerMock;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers timestamp validation and JSON-RPC number type handling for the
 * {@code send} command's {@code --timestamp} option: positivity, the
 * past-only bound (including the same-millisecond edge case), mutual
 * exclusion with {@code --edit-timestamp} and {@code --end-session}, and
 * strict JSON-RPC numeric type checking (spec tests 1-4, 8-9).
 * <p>
 * The parsing/validation logic itself (spec tests 3, 3b, 4, 4b, 8, 8b-8d) is
 * covered deterministically against
 * {@link SendCommand#parseMessageTimestamp(Object, long)} directly, since it
 * takes the clock as a parameter and needs no Manager mock or wall-clock
 * sampling. The tests here that go through {@link SendCommand#handleCommand}
 * instead verify the wiring: that the parsed value is threaded through to the
 * outgoing message (or omitted), and that the mutual-exclusion checks fire.
 * <p>
 * Spec test 5 ({@code lastMessageTimestamp} left untouched by the supplied
 * path) and tests 6-7 (compose with quote, group sends) need a real Manager
 * and are not covered here; test 5's actual selection logic
 * ({@code ManagerImpl.selectMessageTimestamp}) is covered directly in
 * {@code ManagerImplTest} instead.
 */
class SendTimestampTest {

    private static final String SELF_NUMBER = "+10000000000";

    /**
     * Build a Manager mock that captures the Message passed to sendMessage.
     * Fabricates a SendMessageResults timestamp (the supplied one, or a fixed
     * placeholder) purely so the mock has something to return; no test relies
     * on this value.
     */
    private static Manager capturingManager(AtomicReference<Message> captured) {
        final var state = new ManagerMock.State();
        return ManagerMock.create(SELF_NUMBER, state, Map.of(
                "sendMessage", (proxy, method, args) -> {
                    final var message = (Message) args[0];
                    captured.set(message);
                    long ts = message.timestamp().orElse(1000000000000L);
                    return new SendMessageResults(ts, Map.of());
                }
        ));
    }

    /**
     * Build a Namespace from a JSON-RPC style map, simulating how
     * JsonRpcLocalCommand delivers params to handleCommand.
     */
    private static Namespace jsonRpcNamespace(Map<String, Object> params) {
        return new JsonRpcNamespace(params);
    }

    private static PlainTextWriter nullWriter() {
        return new PlainTextWriterImpl(new StringWriter());
    }

    // --- parseMessageTimestamp: deterministic boundary and type tests ---

    @Test
    void parseMessageTimestampRejectsTimestampEqualToNow() {
        var ex = assertThrows(UserErrorException.class, () -> SendCommand.parseMessageTimestamp(1000L, 1000L));
        assertTrue(ex.getMessage().contains("past"));
    }

    @Test
    void parseMessageTimestampAcceptsTimestampJustBeforeNow() throws UserErrorException {
        assertEquals(Optional.of(999L), SendCommand.parseMessageTimestamp(999L, 1000L));
    }

    @Test
    void parseMessageTimestampRejectsFutureTimestamp() {
        var ex = assertThrows(UserErrorException.class, () -> SendCommand.parseMessageTimestamp(1001L, 1000L));
        assertTrue(ex.getMessage().contains("past"));
    }

    @Test
    void parseMessageTimestampReturnsEmptyForNull() throws UserErrorException {
        assertEquals(Optional.empty(), SendCommand.parseMessageTimestamp(null, 1000L));
    }

    @Test
    void parseMessageTimestampRejectsZero() {
        var ex = assertThrows(UserErrorException.class, () -> SendCommand.parseMessageTimestamp(0L, 1000L));
        assertTrue(ex.getMessage().contains("positive"));
    }

    @Test
    void parseMessageTimestampRejectsNegative() {
        var ex = assertThrows(UserErrorException.class, () -> SendCommand.parseMessageTimestamp(-1L, 1000L));
        assertTrue(ex.getMessage().contains("positive"));
    }

    @Test
    void parseMessageTimestampHandlesSmallIntegerCleanly() throws UserErrorException {
        // Jackson produces Integer for values < 2^31
        assertEquals(Optional.of(12345L), SendCommand.parseMessageTimestamp(12345, 1000000L));
    }

    @Test
    void parseMessageTimestampRejectsOutOfRangeBigInteger() {
        // 2^64 + 1: far outside the range of a long, and outside the range
        // Number.longValue() would silently truncate into.
        var outOfRange = BigInteger.valueOf(2).pow(64).add(BigInteger.ONE);
        var ex = assertThrows(UserErrorException.class,
                () -> SendCommand.parseMessageTimestamp(outOfRange, 1000000L));
        assertTrue(ex.getMessage().contains("integer"));
    }

    @Test
    void parseMessageTimestampRejectsDouble() {
        var ex = assertThrows(UserErrorException.class,
                () -> SendCommand.parseMessageTimestamp(1.7548e12, 1000000L));
        assertTrue(ex.getMessage().contains("integer"));
    }

    @Test
    void parseMessageTimestampRejectsString() {
        var ex = assertThrows(UserErrorException.class, () -> SendCommand.parseMessageTimestamp("12345", 1000000L));
        assertTrue(ex.getMessage().contains("integer"));
    }

    // --- handleCommand: wiring tests (supplied/omitted timestamp, mutual exclusion) ---

    // --- Test 1: Supplied timestamp is used verbatim and echoed ---

    @Test
    void suppliedTimestampIsPassedThroughMessage() throws CommandException {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);
        long ts = System.currentTimeMillis() - 5000;

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", ts
        ));

        new SendCommand().handleCommand(ns, manager, nullWriter());

        assertEquals(Optional.of(ts), captured.get().timestamp());
    }

    // --- Test 2: Omitted timestamp passes empty ---

    @Test
    void omittedTimestampPassesEmpty() throws CommandException {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello"
        ));

        new SendCommand().handleCommand(ns, manager, nullWriter());

        assertEquals(Optional.empty(), captured.get().timestamp());
    }

    // --- Test 9: --timestamp combined with --edit-timestamp is rejected ---

    @Test
    void timestampWithEditTimestampIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", System.currentTimeMillis() - 5000,
                "editTimestamp", System.currentTimeMillis() - 10000
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("--edit-timestamp"));
    }

    // --- Test 10: --timestamp combined with --end-session is rejected ---

    @Test
    void timestampWithEndSessionIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", System.currentTimeMillis() - 5000,
                "end-session", true
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("--end-session"));
    }
}
