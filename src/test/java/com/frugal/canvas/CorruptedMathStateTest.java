
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

                // Wait for the frontend's structured rejection.
                page.waitForFunction(
                        """
                        () =>
                            window.mathStateError !== null &&
                            window.mathState !== null
                        """,
                        null,
                        new Page.WaitForFunctionOptions()
                                .setTimeout(10000)
                );

                @SuppressWarnings("unchecked")
                Map<String, Object> result =
                        (Map<String, Object>) page.evaluate(
                                """
                                () => ({
                                    state: window.mathState,
                                    error: window.mathStateError
                                })
                                """
                        );

                assertNotNull(result, "Frontend result is missing");

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

                // Verify that the previous valid balance was not overwritten.
                assertEquals(
                        1000,
                        ((Number) acceptedState.get("balance")).intValue(),
                        "Corrupted state must not overwrite valid balance"
                );

                // Verify structured error information.
                assertEquals(
                        "INVALID_MATHEMATICAL_STATE",
                        error.get("code")
                );

                assertEquals(
                        "balance",
                        error.get("field")
                );

                assertEquals(
                        expectedReason,
                        error.get("reason")
                );

                assertTrue(
                        injectionCompleted.get(),
                        "Corrupted-state injection did not execute"
                );

                System.out.println(
                        "[STRUCTURED ERROR VERIFIED] " + error
                );

                System.out.println(
                        "[STATE PROTECTION VERIFIED] Balance remains 1000"
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
