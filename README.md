# Q1 – Canvas Streaming Automation & Resilience Testing

A Java-based browser automation and resilience testing project built using **Playwright, WebSockets, Maven, and JUnit 5**.

The project demonstrates WebSocket latency injection, canvas pixel-state validation, coordinate recalibration, circuit-breaker recovery, and detection of corrupted mathematical state.

## 1. Project Overview

This project implements a local canvas streaming testbed and an automated testing framework to evaluate browser behavior under network latency, rendering delays, coordinate drift, and invalid application state.

The test framework intercepts browser WebSocket traffic, injects Fibonacci-scaled delays, validates canvas rendering through pixel inspection, and performs mouse interactions within a specified timing window.

It also injects corrupted mathematical values into the frontend response stream to verify structured validation and state protection.

## 2. Technology Stack

| Technology | Purpose |
|---|---|
| Java 21 | Automation and test implementation |
| Playwright Java 1.48.0 | Browser automation and WebSocket interception |
| Maven | Dependency and build management |
| JUnit 5 | Automated testing |
| JavaScript | Canvas rendering and pixel validation |
| Node.js | Local WebSocket testbed |
| Express | Local HTTP server |
| WebSocket (`ws`) | Real-time browser-server communication |
| Chromium | Browser execution environment |

## 3. Project Structure

```text
frugal-q1-canvas-automation/
│
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/frugal/canvas/
|   |           └── App.java
│   │           └── CanvasCircuitBreaker.java
│   │
│   └── test/
│       └── java/
│           └── com/frugal/canvas/
│               ├── CanvasCircuitBreakerTest.java
│               ├── CorruptedMathStateTest.java
│               ├── ProjectSetupTest.java
│               └── WebSocketLatencyTest.java
│
├── testbed/
│   ├── public/
│   │   └── index.html
│   ├── server.js
│   ├── package.json
│   └── package-lock.json
│
├── artifacts/
│   ├── logs/
│   ├── reports/
│   └── screenshots/
│
├── pom.xml
├── README.md
└── .gitignore
```

## 4. Key Implementations

### 4.1 WebSocket Interception and Latency Injection

Playwright intercepts the active browser WebSocket connection and forwards messages between the browser and local server.

A Fibonacci-based delay model is applied to intercepted messages:

**Delay = 1000 × Fibonacci(n) milliseconds**

The injected delay is capped at 8000 milliseconds.

Example sequence:

| Message | Injected delay |
|---:|---:|
| 1 | 1000 ms |
| 2 | 1000 ms |
| 3 | 2000 ms |
| 4 | 3000 ms |
| 5 | 5000 ms |
| 6 | 8000 ms |

Both server-to-browser and browser-to-server traffic are intercepted.

### 4.2 Canvas Pixel-State Validation

The canvas begins in a gray loading state and transitions to an active green state.

Instead of relying on fixed waits or visibility polling, the automation uses embedded JavaScript with `requestAnimationFrame()`.

The pixel detector:

- Reads the canvas rendering context.
- Inspects pixel color values.
- Detects the active RGB color `34, 197, 94`.
- Requires three consecutive stable animation frames.
- Tracks canvas state versions to reject stale frames.
- Records the pixel-validation timestamp.

### 4.3 Coordinate Drift Detection

The automation calculates screen coordinates using the canvas's logical dimensions and its rendered bounding rectangle.

It simulates a 30-pixel horizontal canvas movement and recalculates the target coordinates.

The test verifies that the coordinate system detects the movement and updates the target position before interaction.

### 4.4 Timed Mouse Interaction

After successful pixel validation and freshness verification, the automation performs:

1. Hover over the calculated target.
2. Drag 15 pixels along the X-axis.
3. Release the mouse.
4. Click the updated target position.

The first interaction is measured against the pixel-validation timestamp.

**Required interaction window: 30–100 milliseconds.**

### 4.5 Circuit Breaker and Recovery

The `CanvasCircuitBreaker` implementation uses three states:

| State | Behavior |
|---|---|
| CLOSED | Normal canvas interaction |
| OPEN | Blocks interactions |
| HALF_OPEN | Permits a controlled recovery attempt |

The circuit opens after the configured consecutive failure threshold.

Recovery attempts are bounded. A successful recovery closes the circuit, while a failed recovery returns it to OPEN.

The integration test records success only after the complete interaction sequence succeeds.

### 4.6 Corrupted Mathematical State Detection

The corrupted-state tests inject invalid account balance values through the intercepted WebSocket response stream.

Test inputs:

- Fractional balance: `100.75`
- Scientific notation: `1e+7`

The frontend validates the raw balance representation and produces structured error information.

Expected behavior:

- Reject the corrupted value.
- Preserve the previously valid balance.
- Record a structured error code, field, reason, and raw value.

The tests verify that the valid balance remains `1000`.

## 5. Setup and Installation

### Prerequisites

Install:

- Java 21
- Apache Maven
- Node.js and npm
- Chromium browser support through Playwright

### Start the local testbed

Open a terminal in the project root:

```powershell
cd testbed
npm install
npm start
```

The local application runs at:

```text
http://localhost:3000
```

### Run the automated test suite

Open a second terminal in the project root:

```powershell
mvn clean test
```

Run only the WebSocket integration test:

```powershell
mvn -Dtest=WebSocketLatencyTest test
```

Run the circuit-breaker unit tests:

```powershell
mvn -Dtest=CanvasCircuitBreakerTest test
```

Run the corrupted mathematical state tests:

```powershell
mvn -Dtest=CorruptedMathStateTest test
```

## 6. Test Results

The complete Maven test suite was executed successfully.

| Test class | Tests | Failures | Errors |
|---|---:|---:|---:|
| CanvasCircuitBreakerTest | 4 | 0 | 0 |
| CorruptedMathStateTest | 2 | 0 | 0 |
| ProjectSetupTest | 1 | 0 | 0 |
| WebSocketLatencyTest | 1 | 0 | 0 |
| **Total** | **8** | **0** | **0** |

### Repeated Integration Test Results

The WebSocket integration test was executed five additional consecutive times.

| Run | Interaction delay | Result |
|---:|---:|---|
| 1 | 90.4 ms | Passed |
| 2 | 86.5 ms | Passed |
| 3 | 84.6 ms | Passed |
| 4 | 69.0 ms | Passed |
| 5 | 74.9 ms | Passed |

**Average interaction delay: 81.16 ms**

All five runs satisfied the required 30–100 ms interaction window.

### Final Build Status

```text
Tests run: 8
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```

## 7. Conclusion

This project demonstrates browser-level resilience testing under controlled WebSocket latency, canvas rendering changes, coordinate drift, and invalid mathematical state injection.

It combines browser automation, real-time communication interception, animation-frame-based pixel validation, coordinate recalibration, and circuit-breaker recovery into a reproducible local testing environment.

The implementation was validated through a successful full Maven test suite and five consecutive successful WebSocket integration test runs.
