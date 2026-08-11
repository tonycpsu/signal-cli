package org.asamk.signal.manager.internal;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ManagerImpl#selectMessageTimestamp}, the decision of
 * whether to use a caller-supplied message timestamp or mint a new one.
 */
class ManagerImplTest {

    @Test
    void suppliedTimestampIsReturnedVerbatimAndMinterIsNeverInvoked() {
        final var minterInvocations = new AtomicInteger();
        final long supplied = 123456789L;

        final long result = ManagerImpl.selectMessageTimestamp(Optional.of(supplied), () -> {
            minterInvocations.incrementAndGet();
            return 999L;
        });

        assertEquals(supplied, result);
        assertEquals(0, minterInvocations.get());
    }

    @Test
    void emptyOptionalInvokesMinterAndReturnsItsValue() {
        final var minterInvocations = new AtomicInteger();
        final long minted = 987654321L;

        final long result = ManagerImpl.selectMessageTimestamp(Optional.empty(), () -> {
            minterInvocations.incrementAndGet();
            return minted;
        });

        assertEquals(minted, result);
        assertEquals(1, minterInvocations.get());
    }
}
