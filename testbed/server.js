
const express = require("express");
const http = require("http");
const WebSocket = require("ws");
const path = require("path");

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = 3000;

app.use(express.static(path.join(__dirname, "public")));

app.get("/health", (req, res) => {
    res.json({ status: "UP", service: "canvas-testbed" });
});

wss.on("connection", (socket) => {
    console.log("[WebSocket] Browser connected");

    // Initial gray loading state.
    socket.send(JSON.stringify({
        type: "canvas-state",
        status: "loading",
        color: "#808080",
        message: "Canvas is loading"
    }));

    // Transition to active state after the simulated server delay.
    const activationTimer = setTimeout(() => {
        if (socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({
                type: "canvas-state",
                status: "active",
                color: "#22c55e",
                message: "Canvas is active",
                target: {
                    x: 400,
                    y: 225,
                    radius: 30
                }
            }));

            console.log("[WebSocket] Active state sent");
        }
    }, 1200);

    socket.on("message", (rawMessage) => {
        try {
            const message = JSON.parse(rawMessage.toString());

            if (message.type === "canvas-action") {
                console.log("[Canvas action]", message);

                socket.send(JSON.stringify({
                    type: "action-ack",
                    action: message.action,
                    received: true
                }));
            }
        } catch (error) {
            console.error("[WebSocket] Invalid message:", error.message);

            socket.send(JSON.stringify({
                type: "error",
                code: "INVALID_MESSAGE",
                message: "Malformed JSON message"
            }));
        }
    });

    socket.on("close", () => {
        clearTimeout(activationTimer);
        console.log("[WebSocket] Browser disconnected");
    });

    socket.on("error", (error) => {
        console.error("[WebSocket] Error:", error.message);
    });
});

server.listen(PORT, () => {
    console.log(`Canvas testbed running at http://localhost:${PORT}`);
    console.log(`WebSocket endpoint: ws://localhost:${PORT}`);
});
