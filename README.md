# opencode-android

An Android client for the [opencode](https://github.com/anomalyco/opencode) server, built with
[Crux](https://github.com/redbadger/crux) (shared Rust core + Kotlin shell) and Jetpack Compose.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Shell (Kotlin / Jetpack Compose)               │
│  ┌─────────────┐  ┌──────────────────────────┐  │
│  │ Compose UI   │  │  HttpClient / SseClient  │  │
│  │ (3 screens)  │  │  (Ktor + OkHttp)         │  │
│  └──────┬───────┘  └────────────┬─────────────┘  │
│         │                       │                │
│    Event (bincode)        Effect (bincode)        │
│         │                       ▲                │
│  ┌──────▼───────────────────────┴────────────┐   │
│  │  Core (ViewModel) — FFI bridge            │   │
│  │  CoreFfi → NativeCoreFfi / PureCoreFfi    │   │
│  └───────────────────────────────────────────┘   │
└──────────┬──────────────────────────────────────┘
           │  JNI (bincode bytes)
┌──────────▼──────────────────────────────────────┐
│  Core (Rust / Crux)                              │
│  ┌────────┐  ┌─────────┐  ┌──────────┐          │
│  │  App   │  │  Model  │  │ Commands │          │
│  │ update │  │ state   │  │ effects  │          │
│  └────────┘  └─────────┘  └──────────┘          │
│  No side-effects — pure logic                   │
└─────────────────────────────────────────────────┘
```

### Rust Core (`shared/`)
- **`app.rs`** — Crux `App` implementation: `Event`, `Model`, `ViewModel`, `Effect`, `update()`, `view()`
  - **Basic-auth detection**: probes `/global/health` without credentials; on 401 surfaces
    `auth_required` in the ViewModel and waits for the user to supply credentials. All subsequent
    requests carry `Authorization: Basic <base64(user:pass)>` (RFC 7617).
  - **Crash log tracking**: accepts `CrashLog(String)` events from the shell and exposes
    `crash_log_count` / `latest_crash_log` in the ViewModel.
- **`ffi.rs`** — BoltFFI export wrapping `crux_core::bridge::Bridge`
- **`bin/codegen.rs`** — Type generator (produces Kotlin types via `facet-generate`)

### Kotlin Shell (`app/`)
- **`CrashLogger.kt`** — Installs as the global `UncaughtExceptionHandler`. Crashes are
  persisted to `filesDir/crashes/` (max 10 reports, newest first) and logged to logcat.
  Reports survive process death and can be enumerated/exported/cleared.
- **`core/CoreFfi.kt`** — FFI interface + JNI bridge (loads `libopencode.so`)
- **`core/PureCoreFfi.kt`** — Pure-Kotlin fallback (mirrors Rust logic for dev without NDK),
  includes basic-auth detection matching the Rust core
- **`core/Core.kt`** — Android ViewModel bridge: sends events, processes effects, exposes
  `StateFlow<ViewModel>`, forwards crash logs
- **`core/HttpClient.kt`** — Executes `Effect.Http` via Ktor/OkHttp, returns `HttpResult` to core
- **`core/SseClient.kt`** — Subscribes to opencode SSE event stream; errors reported to CrashLogger
- **`ui/screens/`** — Connect (with credential fields), Sessions, Chat screens (Jetpack Compose + Material 3)

### Generated Types (`generated/`)
- Auto-generated Kotlin types matching the Rust core (Event, Effect, ViewModel, Request, etc.)
- Bincode serialization helpers (`com.novi.bincode`, `com.novi.serde`)

## Building

### Prerequisites
- Rust 1.90+ (with Android NDK targets for native builds)
- Android SDK (compile SDK 36, build-tools 36.0.0)
- JDK 17+

### Generate Kotlin types
```sh
cd shared
cargo run --bin codegen --features codegen -- --language kotlin --output-dir ../generated
```

### Build native library (requires NDK)
```sh
# Install Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# Build and pack with BoltFFI
boltffi pack android
```

### Build the APK
```sh
./gradlew assembleDebug
```

The app works without the native library — `PureCoreFfi` provides a pure-Kotlin
reimplementation of the core logic. When `libopencode.so` is present, `NativeCoreFfi`
is used automatically.

### Run tests
```sh
# Rust core tests
cd shared && cargo test

# Kotlin shell tests
./gradlew testDebugUnitTest
```

## Basic Auth Detection

The opencode server uses HTTP Basic Auth when `OPENCODE_SERVER_PASSWORD` is set
(username defaults to `opencode`). The client detects this automatically:

1. On Connect, the core probes `GET /global/health` without credentials
2. If the server responds `401`, the core sets `auth_required = true` in the ViewModel
3. The UI shows username/password fields
4. The user enters credentials and taps Connect again
5. The core retries the probe with `Authorization: Basic <base64(user:pass)>`
6. On `200`, the core marks `authed = true` and all subsequent requests (sessions,
   messages, send, SSE) carry the Authorization header
7. On `401` with credentials, the core shows "Invalid credentials"

## Crash Logs

`CrashLogger` installs as the global `Thread.UncaughtExceptionHandler` in
`OpencodeApp.onCreate()`. When a crash occurs:

- The stack trace is written to logcat (`opencode-crash` tag)
- A crash report file is persisted to `filesDir/crashes/crash_<timestamp>.txt`
- Reports are pruned to the 10 most recent
- Reports survive process death and can be accessed via `CrashLogger.getReports()`

The crash log count is displayed on the Connect screen.

## API Coverage

The client connects to an opencode server and supports:
- **Health check** (`GET /global/health`) — with basic-auth detection
- **Session list** (`GET /session`)
- **Create session** (`POST /session`)
- **Load messages** (`GET /session/{id}/message`)
- **Send message** (`POST /session/{id}/prompt_async`)
- **SSE events** (`GET /event`) — live message streaming

## Configuration

The default server URL is `http://localhost:4096`. Change it in the Connect screen.
`android:usesCleartextTraffic="true"` is enabled for local development.
