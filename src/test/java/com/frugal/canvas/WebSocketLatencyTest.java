package com.frugal.canvas;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketLatencyTest {

    private static final String APP_URL = "http://localhost:3000";

    private static final Pattern WS_URL =
            Pattern.compile("^ws://localhost:3000/?$");

    private static final int EXPECTED_SERVER_MESSAGES = 6;
    private static final int WEBSOCKET_COMPLETION_TIMEOUT_SECONDS = 20;

    private static final double DRIFT_TOLERANCE = 0.5;
    private static final double DRIFT_INJECTION_PX = 30.0;

    private static final int REQUIRED_STABLE_FRAMES = 3;
    private static final int PIXEL_VALIDATION_TIMEOUT_MS = 10000;

    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final int MAX_RECOVERY_ATTEMPTS = 2;

    private record CanvasCalibration(
            double targetX,
            double targetY,
            double canvasWidth,
            double canvasHeight,
            double boundsX,
            double boundsY,
            double boundsWidth,
            double boundsHeight,
            double screenX,
            double screenY
    ) {}

    /*
     * Read canvas geometry and calculate target coordinates.
     */
    private CanvasCalibration calibrateCanvas(
            Page page,
            double logicalTargetX,
            double logicalTargetY) {

        @SuppressWarnings("unchecked")
        Map<String, Object> geometry =
                (Map<String, Object>) page.evaluate(
                        """
                        () => {
                            const canvas = document.querySelector('canvas');

                            if (!canvas) {
                                throw new Error('Canvas not found');
                            }

                            const rect = canvas.getBoundingClientRect();

                            return {
                                logicalWidth: canvas.width,
                                logicalHeight: canvas.height,
                                boundsX: rect.x,
                                boundsY: rect.y,
                                boundsWidth: rect.width,
                                boundsHeight: rect.height
                            };
                        }
                        """
                );

        double logicalWidth = number(geometry, "logicalWidth");
        double logicalHeight = number(geometry, "logicalHeight");

        double boundsX = number(geometry, "boundsX");
        double boundsY = number(geometry, "boundsY");

        double boundsWidth = number(geometry, "boundsWidth");
        double boundsHeight = number(geometry, "boundsHeight");

        if (logicalWidth <= 0 ||
                logicalHeight <= 0 ||
                boundsWidth <= 0 ||
                boundsHeight <= 0) {

            throw new AssertionError(
                    "Invalid canvas dimensions or bounding box"
            );
        }

        if (logicalTargetX < 0 ||
                logicalTargetX > logicalWidth ||
                logicalTargetY < 0 ||
                logicalTargetY > logicalHeight) {

            throw new AssertionError(
                    "Target is outside the canvas"
            );
        }

        double screenX = boundsX +
                (logicalTargetX / logicalWidth) * boundsWidth;

        double screenY = boundsY +
                (logicalTargetY / logicalHeight) * boundsHeight;

        CanvasCalibration calibration = new CanvasCalibration(
                logicalTargetX,
                logicalTargetY,
                logicalWidth,
                logicalHeight,
                boundsX,
                boundsY,
                boundsWidth,
                boundsHeight,
                screenX,
                screenY
        );

        System.out.println("[CALIBRATION] " + calibration);

        return calibration;
    }

    private double number(
            Map<String, Object> values,
            String key) {

        Object value = values.get(key);

        if (!(value instanceof Number)) {
            throw new AssertionError(
                    "Missing or invalid geometry value: " + key
            );
        }

        return ((Number) value).doubleValue();
    }

    /*
     * Inject horizontal canvas movement.
     */
    private void simulateCanvasDrift(Page page) {

        System.out.println(
                "[DRIFT INJECTION] Moving canvas horizontally by "
                        + DRIFT_INJECTION_PX + " pixels."
        );

        page.locator("canvas").evaluate(
                "(canvas, offset) => {" +
                        "canvas.style.transform = `translateX(${offset}px)`;" +
                        "}",
                DRIFT_INJECTION_PX
        );
    }

    /*
     * Detect canvas movement and recalculate target coordinates.
     */
    private CanvasCalibration detectDriftAndRecalibrate(
            Page page,
            CanvasCalibration previous) {

        CanvasCalibration current = calibrateCanvas(
                page,
                previous.targetX(),
                previous.targetY()
        );

        boolean positionChanged =
                Math.abs(current.boundsX() - previous.boundsX())
                        > DRIFT_TOLERANCE ||
                Math.abs(current.boundsY() - previous.boundsY())
                        > DRIFT_TOLERANCE;

        boolean sizeChanged =
                Math.abs(current.boundsWidth() - previous.boundsWidth())
                        > DRIFT_TOLERANCE ||
                Math.abs(current.boundsHeight() - previous.boundsHeight())
                        > DRIFT_TOLERANCE;

        boolean logicalSizeChanged =
                current.canvasWidth() != previous.canvasWidth() ||
                current.canvasHeight() != previous.canvasHeight();

        if (positionChanged || sizeChanged || logicalSizeChanged) {

            System.out.println(
                    "[DRIFT DETECTED] Canvas geometry changed."
            );

            System.out.println(
                    "[RECALIBRATION] Updated target coordinates: X="
                            + current.screenX()
                            + ", Y=" + current.screenY()
            );

        } else {

            System.out.println(
                    "[DRIFT CHECK] Canvas geometry unchanged."
            );
        }

        return current;
    }

    /*
     * Fibonacci-based WebSocket delay.
     * Sequence: 1000, 1000, 2000, 3000, 5000, 8000 ms.
     */
    private static int fibonacciDelayMillis(int index) {

        int a = 1;
        int b = 1;

        if (index <= 2) {
            return 1000;
        }

        for (int i = 3; i <= index; i++) {
            int next = a + b;
            a = b;
            b = next;
        }

        return Math.min(8000, b * 1000);
    }

    /*
     * Detect active pixels and require consecutive stable animation frames.
     * A frame-version change resets the consecutive-frame counter.
     */
    private static final String PIXEL_DETECTOR = """
            () => new Promise((resolve, reject) => {

                const canvas = document.querySelector('canvas');

                if (!canvas) {
                    reject(new Error('Canvas element not found'));
                    return;
                }

                const context = canvas.getContext('2d');

                if (!context) {
                    reject(new Error('2D canvas context unavailable'));
                    return;
                }

                if (typeof window.canvasStateVersion !== 'number') {
                    reject(new Error(
                        'canvasStateVersion is missing. ' +
                        'Update testbed/public/index.html first.'
                    ));
                    return;
                }

                const startedAt = performance.now();
                const deadline = startedAt + 10000;

                let stableFrames = 0;
                let validatedVersion = null;
                let lastColor = null;

                function inspectFrame() {

                    if (performance.now() > deadline) {
                        reject(new Error(
                            'Pixel validation timed out. ' +
                            'Repaint may not have completed.'
                        ));
                        return;
                    }

                    const currentVersion = window.canvasStateVersion;

                    const pixel = context.getImageData(5, 5, 1, 1).data;

                    const isActive =
                        pixel[0] === 34 &&
                        pixel[1] === 197 &&
                        pixel[2] === 94;

                    const color =
                        `rgb(${pixel[0]}, ${pixel[1]}, ${pixel[2]})`;

                    if (!isActive) {

                        stableFrames = 0;
                        validatedVersion = null;
                        lastColor = color;

                        requestAnimationFrame(inspectFrame);
                        return;
                    }

                    if (validatedVersion === null) {

                        validatedVersion = currentVersion;
                        stableFrames = 1;
                        lastColor = color;

                    } else if (
                        currentVersion !== validatedVersion ||
                        color !== lastColor
                    ) {

                        validatedVersion = currentVersion;
                        stableFrames = 1;
                        lastColor = color;

                    } else {

                        stableFrames++;
                    }

                    if (
                        stableFrames >= 3 &&
                        window.canvasStateVersion === validatedVersion
                    ) {

                        const validatedAt = performance.now();

                        if (typeof window.recordPixelValidation === 'function') {
                            window.recordPixelValidation(validatedAt);
                        }

                        resolve({
                            detected: true,
                            color: color,
                            validatedAt: validatedAt,
                            elapsedMs: validatedAt - startedAt,
                            stableFrames: stableFrames,
                            frameVersion: validatedVersion
                        });

                        return;
                    }

                    requestAnimationFrame(inspectFrame);
                }

                requestAnimationFrame(inspectFrame);
            })
            """;

    /*
     * Verify that the validated frame is still current.
     */
    private void assertFrameIsFresh(
            Page page,
            Object pixelResult) {

        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                (Map<String, Object>) pixelResult;

        if (result == null ||
                result.get("frameVersion") == null) {

            throw new AssertionError(
                    "Pixel validation did not return a frame version"
            );
        }

        long validatedVersion =
                ((Number) result.get("frameVersion")).longValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> currentState =
                (Map<String, Object>) page.evaluate(
                        """
                        () => ({
                            version: window.canvasStateVersion,
                            exists:
                                typeof window.canvasStateVersion === 'number'
                        })
                        """
                );

        if (!Boolean.TRUE.equals(currentState.get("exists"))) {

            throw new AssertionError(
                    "Canvas state version tracking is unavailable"
            );
        }

        long currentVersion =
                ((Number) currentState.get("version")).longValue();

        if (currentVersion != validatedVersion) {

            throw new AssertionError(
                    "STALE FRAME DETECTED: validated version="
                            + validatedVersion
                            + ", current version="
                            + currentVersion
            );
        }

        System.out.println(
                "[FRAME VALIDATION PASSED] Frame version "
                        + validatedVersion + " is current."
        );
    }

    /*
     * Perform Hover -> Drag -> Click only when the circuit permits it.
     */
    private void performCanvasInteractions(
            Page page,
            double targetX,
            double targetY,
            CanvasCircuitBreaker breaker) {

        if (!breaker.allowsInteraction()) {

            throw new AssertionError(
                    "Mouse interactions blocked: circuit state is "
                            + breaker.getState()
            );
        }

        page.mouse().move(targetX, targetY);

        System.out.println("[ACTION] Hover completed");

        page.mouse().down();

        page.mouse().move(targetX + 15, targetY);

        page.mouse().up();

        System.out.println(
                "[ACTION] Drag completed: 15 pixels"
        );

        page.mouse().click(targetX + 15, targetY);

        System.out.println("[ACTION] Click completed");
    }

    /*
     * Validate pixels with circuit-breaker-controlled retries and recovery.
     *
     * CLOSED:
     *   Retry validation until the failure threshold is reached.
     *
     * OPEN:
     *   Block interactions and begin bounded recovery.
     *
     * HALF_OPEN:
     *   Recalibrate and attempt visual validation.
     */
    private Object validateCanvasAndInteractWithRecovery(
            Page page,
            CanvasCircuitBreaker breaker,
            CanvasCalibration initialCalibration) {

        CanvasCalibration calibration = initialCalibration;

        while (true) {

            Object pixelResult;

            try {

                System.out.println(
                        "[CANVAS VALIDATION] Starting pixel validation. Circuit: "
                                + breaker.getState()
                );

                pixelResult = page.evaluate(PIXEL_DETECTOR);

                System.out.println(
                        "[PIXEL VALIDATION] " + pixelResult
                );

                // Preserve the original 50ms condition-based timing gate.
                page.waitForFunction(
                        "() => window.canvasTiming.pixelValidatedAt !== null && " +
                                "(performance.now() - window.canvasTiming.pixelValidatedAt) >= 50",
                        null,
                        new Page.WaitForFunctionOptions()
                                .setTimeout(2000)
                );

                // Reject stale frames before interaction.
                assertFrameIsFresh(page, pixelResult);

            } catch (RuntimeException | AssertionError failure) {

                String reason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();

                breaker.recordFailure(reason);

                System.out.println(
                        "[CANVAS RECOVERY] Validation failed: " + reason
                );

                // Retry while the circuit is still CLOSED.
                if (breaker.getState()
                        == CanvasCircuitBreaker.State.CLOSED) {
                    continue;
                }

                // Circuit is OPEN. Begin bounded recovery.
                if (!breaker.beginRecovery()) {

                    throw new AssertionError(
                            "Canvas recovery exhausted. " +
                                    "Interactions are blocked.",
                            failure
                    );
                }

                System.out.println(
                        "[CANVAS RECOVERY] Recalibrating canvas coordinates."
                );

                calibration = detectDriftAndRecalibrate(
                        page,
                        calibration
                );

                System.out.println(
                        "[CANVAS RECOVERY] Recalibration completed. Circuit: "
                                + breaker.getState()
                );

                // Retry validation in HALF_OPEN state.
                continue;
            }

            if (!breaker.allowsInteraction()) {
                throw new AssertionError(
                        "Interactions blocked by circuit state: "
                                + breaker.getState()
                );
            }

            /*
             * Treat validation + Hover/Drag/Click as one recovery probe.
             * In HALF_OPEN, the interaction is the probe; only a fully
             * successful sequence closes the circuit.
             */
            try {
                performCanvasInteractions(
                        page,
                        calibration.screenX(),
                        calibration.screenY(),
                        breaker
                );
            } catch (RuntimeException | AssertionError interactionFailure) {
                String reason = interactionFailure.getMessage() == null
                        ? interactionFailure.getClass().getSimpleName()
                        : interactionFailure.getMessage();

                breaker.recordFailure("Canvas interaction failed: " + reason);
                throw interactionFailure;
            }

            // Commit success only after the entire interaction sequence passes.
            breaker.recordSuccess();
            return pixelResult;
        }
    }

    /*
     * Wait until all expected server messages are forwarded.
     */
    private void waitForServerMessages(
            Page page,
            AtomicInteger interceptedCount,
            AtomicInteger forwardedCount) {

        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(
                        WEBSOCKET_COMPLETION_TIMEOUT_SECONDS
                );

        System.out.println(
                "[SYNC] Waiting for all server messages to be forwarded..."
        );

        while (forwardedCount.get() < EXPECTED_SERVER_MESSAGES
                && System.nanoTime() < deadline) {

            page.waitForTimeout(100);
        }

        assertEquals(
                EXPECTED_SERVER_MESSAGES,
                interceptedCount.get(),
                "Not all expected server messages were intercepted"
        );

        assertEquals(
                EXPECTED_SERVER_MESSAGES,
                forwardedCount.get(),
                "Not all expected server messages were forwarded"
        );

        System.out.println(
                "[SYNC PASSED] All "
                        + EXPECTED_SERVER_MESSAGES
                        + " server messages were forwarded."
        );
    }

    @Test
    void shouldInterceptWebSocketAndInjectFibonacciDelays() {

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
            );

            try {

                Page page = browser.newPage();

                AtomicInteger serverMessageCount =
                        new AtomicInteger(0);

                AtomicInteger browserMessageCount =
                        new AtomicInteger(0);

                AtomicInteger serverForwardedCount =
                        new AtomicInteger(0);

                AtomicInteger browserForwardedCount =
                        new AtomicInteger(0);

                // Register WebSocket interception before navigation.
                page.routeWebSocket(WS_URL, ws -> {

                    System.out.println(
                            "[INTERCEPT] WebSocket: " + ws.url()
                    );

                    WebSocketRoute server = ws.connectToServer();

                    // Server -> Browser.
                    server.onMessage(frame -> {

                        int index = serverMessageCount.incrementAndGet();

                        int delay = fibonacciDelayMillis(index);

                        System.out.printf(
                                "[SERVER -> BROWSER] Message %d | Delay: %d ms%n",
                                index, delay
                        );

                        page.waitForTimeout(delay);

                        ws.send(frame.text());

                        serverForwardedCount.incrementAndGet();

                        System.out.println(
                                "[FORWARDED] Server message delivered to browser"
                        );
                    });

                    // Browser -> Server.
                    ws.onMessage(frame -> {

                        int index = browserMessageCount.incrementAndGet();

                        int delay = fibonacciDelayMillis(index);

                        System.out.printf(
                                "[BROWSER -> SERVER] Message %d | Delay: %d ms%n",
                                index, delay
                        );

                        page.waitForTimeout(delay);

                        server.send(frame.text());

                        browserForwardedCount.incrementAndGet();

                        System.out.println(
                                "[FORWARDED] Browser message delivered to server"
                        );
                    });
                });

                page.navigate(APP_URL);

                // Step 1: Initial calibration.
                CanvasCalibration calibration =
                        calibrateCanvas(page, 400, 225);

                double originalX = calibration.screenX();

                System.out.println(
                        "[ORIGINAL POSITION] X=" + originalX
                );

                // Step 2: Inject actual canvas movement.
                simulateCanvasDrift(page);

                // Step 3: Detect drift and recalculate coordinates.
                CanvasCalibration updatedCalibration =
                        detectDriftAndRecalibrate(
                                page,
                                calibration
                        );

                // Step 4: Verify actual canvas movement.
                double actualMovement = Math.abs(
                        updatedCalibration.boundsX()
                                - calibration.boundsX()
                );

                assertTrue(
                        actualMovement >=
                                DRIFT_INJECTION_PX - DRIFT_TOLERANCE,
                        "Canvas drift was not detected. Actual movement: "
                                + actualMovement
                );

                double targetMovement = Math.abs(
                        updatedCalibration.screenX() - originalX
                );

                assertTrue(
                        targetMovement >=
                                DRIFT_INJECTION_PX - DRIFT_TOLERANCE,
                        "Target coordinates were not recalibrated"
                );

                System.out.println(
                        "[DRIFT TEST PASSED] Canvas movement detected "
                                + "and coordinates recalibrated."
                );

                // Step 5: Initialize circuit breaker.
                CanvasCircuitBreaker circuitBreaker =
                        new CanvasCircuitBreaker(
                                CIRCUIT_FAILURE_THRESHOLD,
                                MAX_RECOVERY_ATTEMPTS
                        );

                System.out.println(
                        "[CIRCUIT INITIALIZED] State: "
                                + circuitBreaker.getState()
                );

                // Step 6: Validate pixels and perform interactions.
                Object pixelResult =
                        validateCanvasAndInteractWithRecovery(
                                page,
                                circuitBreaker,
                                updatedCalibration
                        );

                System.out.println(
                        "[TEST PASSED] Active canvas pixel detected."
                );

                assertEquals(
                        CanvasCircuitBreaker.State.CLOSED,
                        circuitBreaker.getState(),
                        "Circuit must be CLOSED after successful validation"
                );

                // Step 7: Read browser-side timing.
                @SuppressWarnings("unchecked")
                Map<String, Object> timingResult =
                        (Map<String, Object>) page.evaluate(
                                "() => window.canvasTiming"
                        );

                if (timingResult == null ||
                        timingResult.get("interactionDelayMs") == null) {

                    throw new AssertionError(
                            "Browser interaction timing was not recorded"
                    );
                }

                double interactionDelay =
                        ((Number) timingResult.get(
                                "interactionDelayMs"
                        )).doubleValue();

                System.out.println(
                        "[TIMING RESULT] " + timingResult
                );

                assertTrue(
                        interactionDelay >= 30 &&
                                interactionDelay <= 100,
                        "Interaction timing outside permitted range: "
                                + interactionDelay + " ms"
                );

                System.out.println(
                        "[TIMING PASSED] Interaction occurred within 30-100 ms."
                );

                // Step 8: Synchronize WebSocket messages.
                waitForServerMessages(
                        page,
                        serverMessageCount,
                        serverForwardedCount
                );

                // Step 9: Print final statistics.
                System.out.println(
                        "Server messages intercepted: "
                                + serverMessageCount.get()
                );

                System.out.println(
                        "Server messages forwarded: "
                                + serverForwardedCount.get()
                );

                System.out.println(
                        "Browser messages intercepted: "
                                + browserMessageCount.get()
                );

                System.out.println(
                        "Browser messages forwarded: "
                                + browserForwardedCount.get()
                );

                System.out.println(
                        "[CIRCUIT FINAL STATE] "
                                + circuitBreaker.getState()
                );

            } finally {
                browser.close();
            }
        }
    }
}