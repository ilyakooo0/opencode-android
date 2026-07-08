# opencode-android

A native Android client for the [**opencode**](https://opencode.ai) server (the
`sst/opencode` AI coding agent), built with **Crux** (a Rust core), **Kotlin**,
and **Jetpack Compose**.

Connect to a running `opencode serve`, browse and create sessions, and chat with
the agent — assistant replies (text, reasoning, and tool calls) stream in live
over the server's SSE event stream.

<p align="center"><em>Connect → Sessions → Chat, with token-by-token streaming.</em></p>

## Architecture

The app follows the Crux pattern: **all application logic lives in a pure,
platform-agnostic core; the platform is a thin shell** that renders the core's
view model, sends it events, and performs the side effects it requests.

```
┌─────────────────────────── shared/ (Rust) ───────────────────────────┐
│  Crux core — the single source of truth                               │
│    • Model / Event / Effect / ViewModel                               │
│    • update(): connect+auth, sessions, chat, SSE stream application    │
│    • crux_http for HTTP effects; unit-tested with `cargo test`         │
└───────────────────────────────────────────────────────────────────────┘
                         ▲ mirrored, behaviour-for-behaviour
┌─────────────────────────── app/ (Kotlin) ────────────────────────────┐
│  Jetpack Compose shell                                                 │
│    • OpencodeCore  — Kotlin port of the reducer (same ViewModel)       │
│    • HttpClient (OkHttp) + SseClient (okhttp-sse)  — the effects       │
│    • Compose UI: Connect / Sessions / Chat                             │
└───────────────────────────────────────────────────────────────────────┘
```

### Why a Kotlin port instead of a JNI `.so`?

Crux normally compiles the Rust core to a native library and the shell talks to
it over an FFI bridge. That requires an Android cross-compilation toolchain
(NDK + a Rust `std` for the `*-linux-android` targets). This project's dev
environment intentionally ships **without** an NDK, so instead:

- The **Rust core is the canonical specification** of behaviour and is fully
  unit-tested (`cd shared && cargo test`).
- The **Android shell runs `OpencodeCore`**, a faithful Kotlin port of the same
  reducer, driving an identical `UiState`/`ViewModel`.

The FFI boundary is kept clean, so the day an NDK is available the Kotlin port
can be swapped for the compiled `.so` behind the same seam. The crate already
builds as a `cdylib`/`staticlib` and a `codegen` binary is wired for Crux
type-generation.

## Project layout

```
flake.nix                     Reproducible dev shell (JDK 17, Gradle, Rust, Android SDK 35)
shared/                       Crux core (Rust)
  src/app.rs                  App: Model, Event, Effect, ViewModel, update, view, tests
  src/protocol.rs             Lenient serde wire types for the opencode HTTP + SSE API
  src/bin/codegen.rs          Type-generation entry point (feature = "typegen")
generated/                    Crux-generated FFI-shared JVM types (from `codegen`)
app/                          Android app (Kotlin + Compose)
  src/main/java/soy/iko/opencode/
    core/                     OpencodeCore (reducer port), Protocol, HttpClient, SseClient
    ui/                       Compose screens (Connect/Sessions/Chat), theme, components
    MainActivity.kt, CoreViewModel.kt
```

## Getting started

### Dev shell

Everything you need is provided by the Nix flake:

```sh
nix develop          # or: direnv allow  (an .envrc with `use flake` is included)
```

This puts JDK 17, Gradle, `rustc`/`cargo`, and the Android SDK (platform 35,
build-tools 35.0.0) on your `PATH`.

### Build & test

```sh
# Rust core — logic + streaming unit tests
cd shared && cargo test

# Android app — debug APK
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk

# Android unit tests + lint
./gradlew testDebugUnitTest lintDebug
```

### Run against a server

Start opencode's server on your machine or LAN:

```sh
opencode serve --port 4096       # optionally behind OPENCODE_SERVER_PASSWORD
```

Install the APK, launch the app, and enter the server URL (e.g.
`http://192.168.1.10:4096`). If the server was started with a password the app
detects the `401`, shows credential fields, and connects with HTTP Basic auth.

> Cleartext HTTP is enabled so you can reach a `http://` server on your LAN.

## The opencode API this client speaks

- **Connect / auth** — `GET /global/health` probes reachability; a `401` triggers
  HTTP Basic auth.
- **Sessions** — `GET /session`, `POST /session`, `DELETE /session/{id}`.
- **Messages** — `GET /session/{id}/message` (each item is `{ info, parts }`).
- **Send** — `POST /session/{id}/prompt_async` (returns immediately; the reply
  streams over SSE).
- **Cancel** — `POST /session/{id}/abort`.
- **Stream** — `GET /event` (SSE). The client applies `message.updated`,
  `message.part.updated` (text/reasoning/tool, with deltas), `message.removed`,
  `session.idle` (clears the "generating" state), `session.error`, and
  `session.{created,updated,deleted}` incrementally. Unknown event types are
  ignored so new server versions don't break the stream.

## Continuous integration & releases

- **CI** (`.github/workflows/build.yml`) runs on pushes to `master` and PRs: it
  unit-tests the Crux core (`cargo test`), runs the Android unit tests + lint,
  and builds the debug APK (uploaded as an artifact).
- **Releases** (`.github/workflows/release.yml`): push a version tag to cut a
  release — it builds a minified, signed release APK and publishes a GitHub
  Release with the APK attached and a signed build-provenance attestation.

  ```sh
  git tag v1.0.0 && git push origin v1.0.0     # or run the workflow manually
  ```

  The tag name becomes the version (a manual run uses a `YYYY.MM.DD.HHMM`
  timestamp), stamped into the APK via `-PversionName/-PversionCode`.

  **Signing:** set the repo secrets `OPENCODE_STORE_BASE64` (base64 of your
  keystore), `OPENCODE_STORE_PASSWORD`, `OPENCODE_KEY_ALIAS`, and
  `OPENCODE_KEY_PASSWORD` to sign with your own key. Without them the release
  build falls back to the debug key, so the APK is still installable.

## Notes & limitations

- Rendering covers text, reasoning, and tool-call parts. Interactive tool
  **permission** prompts (`permission.updated`) are not yet surfaced.
- Model/agent selection uses the server's configured default (no picker yet).
- No `.so` is built in this environment (see *Architecture* above).

## License

Apache-2.0.
