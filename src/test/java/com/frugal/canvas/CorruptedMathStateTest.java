
package com.frugal.canvas;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.WebSocketRoute;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorruptedMathStateTest {

    private static final String APP_URL =
            "http://localhost:3000";

    private static final Pattern WS_URL =
            Pattern.compile("^ws://localhost:3000/?$");

    private static final String VALID_STATE =
            """
            {"type":"account-state","balance":1000}
            """;

    /*
     * Inject corrupted mathematical state without replacing
     * or suppressing the original canvas-state messages.
     */
    private void runCorruptedStateScenario(
            String corruptedBalance,
            String expectedReason) {

        try (Playwright playwright = Playwright.create()) {

            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(true)
            );

            try {

                Page page = browser.newPage();

                AtomicBoolean injectionCompleted =
                        new AtomicBoolean(false);

                page.routeWebSocket(WS_URL, ws -> {

                    System.out.println(
                            "[INTERCEPT] WebSocket: " + ws.url()
                    );

                    WebSocketRoute server = ws.connectToServer();

                    // Server -> Browser.
                    server.onMessage(frame -> {

                        /*
                         * Inject only once, when the first original
                         * server message arrives.
                         */
                        if (injectionCompleted.compareAndSet(
                                false, true)) {

                            System.out.println(
                                    "[INJECTION] Sending valid balance: 1000"
                            );

                            ws.send(VALID_STATE);

                            String corruptedMessage =
                                    """
                                    {"type":"account-state","balance":%s}
                                    """.formatted(corruptedBalance);

                            System.out.println(
                                    "[INJECTION] Sending corrupted balance: "
                                            + corruptedBalance
                            );

                            ws.send(corruptedMessage);
                        }

                        /*
                         * Always forward the original server message.
                         * Canvas loading and active states are preserved.
                         */
                        ws.send(frame.text());

                        System.out.println(
                                "[FORWARDED] Original server message preserved"
                        );
                    });

                    // Browser -> Server.
                    ws.onMessage(frame -> {
                        server.send(frame.text());
                    });
                });

                page.navigate(APP_URL);

                /*
                 * Wait until:
                 * 1. A valid mathematical state is accepted.
                 * 2. A structured error is recorded.
                 * 3. The exception boundary confirms it caught an exception.
                 */
                page.waitForFunction(
                        """
                        () =>
                            window.mathState !== null &&
                            window.mathStateError !== null &&
                            window.mathStateExceptionCaught === true &&
                            window.mathStateExceptionCount === 1 &&
                            window.mathStateLastExceptionName ===
                                "MathematicalStateException"
                        """,
                        null,
                        new Page.WaitForFunctionOptions()
                                .setTimeout(10000)
                );

                /*
                 * Read all frontend validation and exception-boundary
                 * information in one browser evaluation.
                 */
                @SuppressWarnings("unchecked")
                Map<String, Object> result =
                        (Map<String, Object>) page.evaluate(
                                """
                                () => ({
                                    state: window.mathState,
                                    error: window.mathStateError,
                                    exceptionCaught:
                                        window.mathStateExceptionCaught,
                                    exceptionCount:
                                        window.mathStateExceptionCount,
                                    exceptionName:
                                        window.mathStateLastExceptionName
                                })
                                """
                        );

                assertNotNull(
                        result,
                        "Frontend result is missing"
                );

                @SuppressWarnings("unchecked")
                Map<String, Object> acceptedState =
                        (Map<String, Object>) result.get("state");

                @SuppressWarnings("unchecked")
                Map<String, Object> error =
                        (Map<String, Object>) result.get("error");

                assertNotNull(
                        acceptedState,
                        "Previously accepted state must remain available"
                );

                assertNotNull(
                        error,
                        "Corrupted mathematical state must produce an error"
                );

                // -------------------------------------------------
                // 1. Verify previous valid state is preserved.
                // -------------------------------------------------

                assertEquals(
                        1000,
                        ((Number) acceptedState.get("balance")).intValue(),
                        "Corrupted state must not overwrite valid balance"
                );

                System.out.println(
                        "[STATE PROTECTION VERIFIED] Balance remains 1000"
                );

                // -------------------------------------------------
                // 2. Verify structured exception information.
                // -------------------------------------------------

                assertEquals(
                        "INVALID_MATHEMATICAL_STATE",
                        error.get("code"),
                        "Incorrect mathematical-state error code"
                );

                assertEquals(
                        "balance",
                        error.get("field"),
                        "Incorrect error field"
                );

                assertEquals(
                        expectedReason,
                        error.get("reason"),
                        "Incorrect mathematical validation reason"
                );

                assertEquals(
                        corruptedBalance,
                        error.get("rawValue"),
                        "Incorrect corrupted raw value"
                );

                System.out.println(
                        "[STRUCTURED ERROR VERIFIED] " + error
                );

                // -------------------------------------------------
                // 3. Verify the actual exception boundary.
                // -------------------------------------------------

                assertEquals(
                        Boolean.TRUE,
                        result.get("exceptionCaught"),
                        "Exception boundary did not catch the exception"
                );

                assertEquals(
                        1,
                        ((Number) result.get("exceptionCount")).intValue(),
                        "Expected exactly one caught exception"
                );

                assertEquals(
                        "MathematicalStateException",
                        result.get("exceptionName"),
                        "Incorrect exception type"
                );

                System.out.println(
                        "[EXCEPTION BOUNDARY VERIFIED] "
                                + result.get("exceptionName")
                );

                System.out.println(
                        "[EXCEPTION COUNT VERIFIED] "
                                + result.get("exceptionCount")
                );

                // -------------------------------------------------
                // 4. Verify WebSocket injection occurred.
                // -------------------------------------------------

                assertTrue(
                        injectionCompleted.get(),
                        "Corrupted-state injection did not execute"
                );

                System.out.println(
                        "[INJECTION VERIFIED] Corrupted state delivered"
                );

                System.out.println(
                        "[TEST PASSED] " + expectedReason
                );

            } finally {

                browser.close();
            }
        }
    }

    @Test
    void shouldRejectFractionalBalance() {

        runCorruptedStateScenario(
                "100.75",
                "FRACTIONAL_VALUE"
        );
    }

    @Test
    void shouldRejectScientificNotation() {

        runCorruptedStateScenario(
                "1e+7",
                "SCIENTIFIC_NOTATION"
        );
    }
}
