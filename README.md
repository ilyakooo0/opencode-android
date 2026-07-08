# opencode-android

An Android client for the [opencode](https://github.com/sst/opencode) server, built with
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
- **`ffi.rs`** — BoltFFI export wrapping `crux_core::bridge::Bridge`
- **`bin/codegen.rs`** — Type generator (produces Kotlin types via `facet-generate`)

### Kotlin Shell (`app/`)
- **`core/CoreFfi.kt`** — FFI interface + JNI bridge (loads `libopencode.so`)
- **`core/PureCoreFfi.kt`** — Pure-Kotlin fallback (mirrors Rust logic for dev without NDK)
- **`core/Core.kt`** — Android ViewModel bridge: sends events, processes effects, exposes `StateFlow<ViewModel>`
- **`core/HttpClient.kt`** — Executes `Effect.Http` via Ktor/OkHttp, returns `HttpResult` to core
- **`core/SseClient.kt`** — Subscribes to opencode SSE event stream
- **`ui/screens/`** — Connect, Sessions, Chat screens (Jetpack Compose + Material 3)

### Generated Types (`generated/`)
- Auto-generated Kotlin types matching the Rust core (Event, Effect, ViewModel, Request, etc.)
- Bincode serialization helpers (`com.novi.bincode`, `com.novi.serde`)

## Building

### Prerequisites
- Rust 1.90+ (with Android NDK targets for native builds)
- Android SDK (compile SDK 37, build-tools 37.0.0)
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

## API Coverage

The client connects to an opencode server and supports:
- **Health check** (`GET /global/health`)
- **Session list** (`GET /session`)
- **Create session** (`POST /session`)
- **Load messages** (`GET /session/{id}/message`)
- **Send message** (`POST /session/{id}/prompt_async`)
- **SSE events** (`GET /event`) — live message streaming

## Configuration

The default server URL is `http://localhost:4096`. Change it in the Connect screen.
`android:usesCleartextTraffic="true"` is enabled for local development.
