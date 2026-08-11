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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers timestamp validation and JSON-RPC number type handling for the
 * {@code send} command's {@code --timestamp} option (spec tests 1-5, 8).
 * Tests 6-7 (compose with quote, group sends) need a real Manager and are
 * covered by the manual run_tests.sh suite instead.
 */
class SendTimestampTest {

    private static final String SELF_NUMBER = "+10000000000";

    /**
     * Build a Manager mock that captures the Message passed to sendMessage.
     * Returns the supplied timestamp (or a fixed one if not supplied) in
     * SendMessageResults so we can verify the echo.
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
        assertTrue(ex.getMessage().contains("future"));
    }

    // --- Test 8: Small integer over JSON-RPC yields clean handling, not ClassCastException ---

    @Test
    void smallIntegerTimestampYieldsUserError() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        // Jackson produces Integer for values < 2^31
        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", 12345
        ));

        // 12345 is a valid positive past timestamp, so it should pass validation
        // and be threaded through as Optional.of(12345L)
        try {
            new SendCommand().handleCommand(ns, manager, nullWriter());
            assertEquals(Optional.of(12345L), captured.get().timestamp());
        } catch (CommandException e) {
            // Also acceptable if treated as a clean user error
            assertTrue(e instanceof UserErrorException, "Should be UserErrorException, not ClassCastException");
        }
    }

    @Test
    void smallIntegerDoesNotThrowClassCastException() {
        var captured = new AtomicReference<Message>();
        var manager = capturingManager(captured);

        // Explicitly test Integer type (what Jackson produces for small numbers)
        var ns = jsonRpcNamespace(Map.of(
                "recipient", "+11234567890",
                "message", "hello",
                "timestamp", Integer.valueOf(12345)
        ));

        // Must not throw ClassCastException
        try {
            new SendCommand().handleCommand(ns, manager, nullWriter());
        } catch (UserErrorException e) {
            // Clean user error is acceptable
        } catch (ClassCastException e) {
            throw new AssertionError("Small integer timestamp must not cause ClassCastException", e);
        } catch (CommandException e) {
            throw new AssertionError("Unexpected exception: " + e, e);
        }
    }
}
