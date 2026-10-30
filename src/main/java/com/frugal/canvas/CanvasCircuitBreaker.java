package com.frugal.canvas;

/**
 * Controls canvas validation failures and bounded recovery attempts.
 *
 * States:
 * CLOSED    - Normal operation.
 * OPEN      - Interactions are blocked.
 * HALF_OPEN - A controlled recovery attempt is allowed.
 */
public final class CanvasCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final int maxRecoveryAttempts;

    private State state = State.CLOSED;

    private int consecutiveFailures;
    private int recoveryAttempts;

    public CanvasCircuitBreaker(
            int failureThreshold,
            int maxRecoveryAttempts) {

        if (failureThreshold < 1 || maxRecoveryAttempts < 0) {
            throw new IllegalArgumentException(
                    "Threshold must be positive and recovery attempts cannot be negative"
            );
        }

        this.failureThreshold = failureThreshold;
        this.maxRecoveryAttempts = maxRecoveryAttempts;
    }

    /**
     * Record a successful validation and recovery operation.
     */
    public synchronized void recordSuccess() {

        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            consecutiveFailures = 0;
            recoveryAttempts = 0;

            System.out.println(
                    "[CIRCUIT CLOSED] Canvas recovery successful."
            );

            return;
        }

        if (state == State.CLOSED) {
            consecutiveFailures = 0;
        }
    }

    /**
     * Record a failed validation or interaction operation.
     */
    public synchronized void recordFailure(String reason) {

        System.out.println(
                "[CIRCUIT FAILURE] " + reason
        );

        if (state == State.HALF_OPEN) {
            state = State.OPEN;

            System.out.println(
                    "[CIRCUIT OPEN] Recovery attempt failed."
            );

            return;
        }

        if (state == State.OPEN) {
            return;
        }

        consecutiveFailures++;

        System.out.println(
                "[CIRCUIT] Consecutive failures: "
                        + consecutiveFailures
        );

        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN;

            System.out.println(
                    "[CIRCUIT OPEN] Failure threshold reached. "
                            + "Canvas interactions blocked."
            );
        }
    }

    /**
     * Permit a bounded recovery attempt.
     */
    public synchronized boolean beginRecovery() {

        if (state != State.OPEN) {
            return false;
        }

        if (recoveryAttempts >= maxRecoveryAttempts) {

            System.out.println(
                    "[CIRCUIT LOCKED] Maximum recovery attempts exhausted."
            );

            return false;
        }

        recoveryAttempts++;
        state = State.HALF_OPEN;

        System.out.println(
                "[CIRCUIT HALF-OPEN] Recovery attempt "
                        + recoveryAttempts
                        + "/"
                        + maxRecoveryAttempts
        );

        return true;
    }

    /**
     * Permit normal interactions in CLOSED and one controlled
     * recovery probe in HALF_OPEN. OPEN always blocks interaction.
     */
    public synchronized boolean allowsInteraction() {
        return state == State.CLOSED || state == State.HALF_OPEN;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized int getRecoveryAttempts() {
        return recoveryAttempts;
    }
}