package com.frugal.canvas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanvasCircuitBreakerTest {

    @Test
    void shouldOpenAfterThreeConsecutiveFailures() {

        CanvasCircuitBreaker breaker =
                new CanvasCircuitBreaker(3, 2);

        assertEquals(
                CanvasCircuitBreaker.State.CLOSED,
                breaker.getState()
        );

        breaker.recordFailure("Pixel validation failed");
        breaker.recordFailure("Stale frame detected");

        assertEquals(
                CanvasCircuitBreaker.State.CLOSED,
                breaker.getState()
        );

        breaker.recordFailure("Canvas repaint timeout");

        assertEquals(
                CanvasCircuitBreaker.State.OPEN,
                breaker.getState()
        );

        assertFalse(breaker.allowsInteraction());
    }

    @Test
    void shouldRecoverAfterSuccessfulHalfOpenAttempt() {

        CanvasCircuitBreaker breaker =
                new CanvasCircuitBreaker(3, 2);

        breaker.recordFailure("Failure 1");
        breaker.recordFailure("Failure 2");
        breaker.recordFailure("Failure 3");

        assertTrue(breaker.beginRecovery());

        assertEquals(
                CanvasCircuitBreaker.State.HALF_OPEN,
                breaker.getState()
        );

        breaker.recordSuccess();

        assertEquals(
                CanvasCircuitBreaker.State.CLOSED,
                breaker.getState()
        );

        assertTrue(breaker.allowsInteraction());
        assertEquals(0, breaker.getRecoveryAttempts());
    }

    @Test
    void shouldBlockAfterMaximumRecoveryAttempts() {

        CanvasCircuitBreaker breaker =
                new CanvasCircuitBreaker(3, 2);

        breaker.recordFailure("Failure 1");
        breaker.recordFailure("Failure 2");
        breaker.recordFailure("Failure 3");

        assertTrue(breaker.beginRecovery());

        breaker.recordFailure("Recovery failed");

        assertEquals(
                CanvasCircuitBreaker.State.OPEN,
                breaker.getState()
        );

        assertTrue(breaker.beginRecovery());

        breaker.recordFailure("Second recovery failed");

        assertEquals(
                CanvasCircuitBreaker.State.OPEN,
                breaker.getState()
        );

        assertFalse(breaker.beginRecovery());
        assertFalse(breaker.allowsInteraction());
        assertEquals(2, breaker.getRecoveryAttempts());
    }

    @Test
    void shouldAllowOnlyControlledProbeDuringHalfOpenRecovery() {

        CanvasCircuitBreaker breaker =
                new CanvasCircuitBreaker(2, 1);

        breaker.recordFailure("Pixel validation failed");
        breaker.recordFailure("Stale frame detected");

        assertEquals(
                CanvasCircuitBreaker.State.OPEN,
                breaker.getState()
        );

        assertFalse(breaker.allowsInteraction());

        assertTrue(breaker.beginRecovery());

        assertEquals(
                CanvasCircuitBreaker.State.HALF_OPEN,
                breaker.getState()
        );

        assertTrue(
                breaker.allowsInteraction(),
                "HALF_OPEN must permit the one controlled recovery interaction"
        );

        breaker.recordFailure("Drag interaction failed");

        assertEquals(
                CanvasCircuitBreaker.State.OPEN,
                breaker.getState()
        );

        assertFalse(breaker.allowsInteraction());
    }
}