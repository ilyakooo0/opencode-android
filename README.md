# opencode-android

A native Android client (Kotlin + Jetpack Compose) for the [`opencode serve`](https://opencode.ai/docs/server/) HTTP API.

Application id: `soy.iko.opencode`

## Set up a server and connect to it

This app is a client for the [`opencode serve`](https://opencode.ai/docs/server/) HTTP API, so you
need an opencode server running somewhere reachable from your device.

### 1. Start the server

On the host that has your project and opencode installed:

```bash
opencode serve --hostname 0.0.0.0 --port 4096
```

Useful flags:

| Flag | Default | Description |
| --- | --- | --- |
| `--port` | `4096` | Port to listen on |
| `--hostname` | `127.0.0.1` | Hostname/interface to bind (`0.0.0.0` exposes it on your LAN) |
| `--mdns` | `false` | Enable mDNS discovery |
| `--cors` | `[]` | Extra browser origins to allow (repeatable) |

The server publishes an OpenAPI 3.1 spec at `http://<hostname>:<port>/doc`.

### 2. (Optional) Add authentication

opencode has no auth by default. To protect the server with HTTP Basic auth:

```bash
OPENCODE_SERVER_PASSWORD=your-password opencode serve --hostname 0.0.0.0 --port 4096
```

The username defaults to `opencode` (override with `OPENCODE_SERVER_USERNAME`). Enter these in the
app's add-server dialog.

### 3. Connect from the app

**Over Wi-Fi / LAN** — add a server with base URL `http://<host-LAN-ip>:4096`.

**Over USB only** — forward the port to the device and point the app at localhost:

```bash
adb reverse tcp:4096 tcp:4096
```

Then use base URL `http://127.0.0.1:4096` in the app.

> The app permits cleartext HTTP because opencode serves plain `http://`. If you terminate TLS with
> a reverse proxy, use an `https://` base URL instead.

## Architecture

- **Ktor 3 (OkHttp engine)** for REST + the long-lived `GET /event` SSE stream.
- **kotlinx.serialization** with sealed polymorphism for opencode's discriminated unions
  (`Part`/`type`, `MessageInfo`/`role`, `ToolState`/`status`, `BusEvent`/`type`). Unknown
  discriminator values fall back to `Unknown*` variants (`OpencodeJson`) so a new server release
  can't break decoding.
- **Manual DI** (`AppContainer` on the `Application`) — no Hilt/KSP.
- **Unidirectional state**: `EventStreamClient` exposes the SSE stream as a hot `SharedFlow`;
  `SessionRepository` reduces events into per-session message state; ViewModels expose `StateFlow`;
  Compose collects with `collectAsStateWithLifecycle`.
- The repository subscribes to `/event` **before** the prompt POST so no early streamed part is missed.

Source map: `data/network` (Ktor, SSE, JSON), `data/model` (wire models), `data/repo`
(reducer + profile persistence), `di` (container/connection), `ui/<feature>` (Compose screens).

## Build

```bash
nix develop              # JDK 17, Gradle 8.14.4, Android SDK 35, aapt2 override
./gradlew assembleDebug  # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # R8-minified + resource-shrunk release APK
./gradlew testDebugUnitTest   # JVM tests for the JSON/serialization layer
./gradlew detekt              # static analysis (config: config/detekt/detekt.yml)
./gradlew lintDebug           # Android Lint
./gradlew connectedAndroidTest # device/emulator tests (needs an AVD)
```

## Install & run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n soy.iko.opencode/.MainActivity
adb logcat --pid=$(adb shell pidof soy.iko.opencode)
```