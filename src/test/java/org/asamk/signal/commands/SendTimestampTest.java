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

    // --- Test 3: Zero timestamp is rejected ---

    @Test
    void zeroTimestampIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", 0L
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("positive"));
    }

    // --- Test 3b: Negative timestamp is rejected ---

    @Test
    void negativeTimestampIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", -1L
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("positive"));
    }

    // --- Test 4: Future timestamp is rejected ---

    @Test
    void futureTimestampIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", System.currentTimeMillis() + 60_000
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("past"));
    }

    // --- Test 4b: Timestamp equal to "now" is rejected (closes the one-millisecond collision hole) ---

    @Test
    void currentTimestampIsRejected() throws CommandException {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        // System.currentTimeMillis() is sampled here and re-sampled inside
        // handleCommand a moment later, so on rare occasions the clock ticks
        // over between the two reads and the sampled value ends up strictly
        // in the past by the time it's validated (which is legitimately
        // accepted, not the case under test). Retry with a fresh sample until
        // we actually observe the ts == now boundary being rejected.
        UserErrorException ex = null;
        for (var attempt = 0; attempt < 100 && ex == null; attempt++) {
            var ns = jsonRpcNamespace(Map.of(
                    "recipient", "+11234567890",
                    "message", "hello",
                    "timestamp", System.currentTimeMillis()
            ));
            try {
                new SendCommand().handleCommand(ns, manager, nullWriter());
                // Accepted: the clock advanced past the sampled value before
                // validation ran. Not the boundary case, retry.
            } catch (UserErrorException e) {
                ex = e;
            }
        }
        assertTrue(ex != null, "Expected a timestamp equal to \"now\" to be rejected at least once");
        assertTrue(ex.getMessage().contains("past"));
    }

    // --- Test 8: Small integer over JSON-RPC yields clean handling, not ClassCastException ---

    @Test
    void smallIntegerTimestampIsHandledCleanly() throws CommandException {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        // Jackson produces Integer for values < 2^31
        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", 12345
        ));

        new SendCommand().handleCommand(ns, manager, nullWriter());
        assertEquals(Optional.of(12345L), captured.get().timestamp());
    }

    // --- Test 8b: BigInteger timestamp out of long range is rejected cleanly ---

    @Test
    void outOfRangeBigIntegerTimestampIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        // 2^64 + 1: far outside the range of a long, and outside the range
        // Number.longValue() would silently truncate into.
        var outOfRange = BigInteger.valueOf(2).pow(64).add(BigInteger.ONE);
        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", outOfRange
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("integer"));
    }

    // --- Test 8c: Double timestamp is rejected cleanly, not silently truncated ---

    @Test
    void doubleTimestampIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", 1.7548e12
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("integer"));
    }

    // --- Test 8d: String timestamp is rejected cleanly, not a ClassCastException ---

    @Test
    void stringTimestampIsRejected() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", "12345"
        ));

        var ex = assertThrows(UserErrorException.class,
                () -> new SendCommand().handleCommand(ns, manager, nullWriter()));
        assertTrue(ex.getMessage().contains("integer"));
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
