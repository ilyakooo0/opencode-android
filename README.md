# opencode-android

A native Android client (Kotlin + Jetpack Compose) for the [`opencode serve`](https://opencode.ai/docs/server/) HTTP API.

Application id: `soy.iko.opencode`

## Status

Milestones M0–M7 complete:

- **M0–M4:** connect to a server → list/create/delete sessions → chat with **live SSE streaming**
  of the assistant's reply. Tool calls, reasoning, and token/cost are rendered.
- **M5:** per-session **model picker** (`GET /config/providers`); **permission prompts** — the
  agent's `permission.updated` asks surface as an allow-once / always / reject dialog, answered via
  `POST /session/:id/permissions/:permissionID`, so tool runs don't stall; **file browser + viewer**
  (`/find/file` fuzzy search, `/file` directory listing, `/file/content` read-only view); **reconnect
  banner** (live SSE connection state) and a real **Settings** screen (theme mode, persisted).
- **M6:** **markdown rendering** of assistant text (headings, code blocks, lists, blockquotes, inline
  bold/italic/code/links/strikethrough); **rich diff/patch rendering** — tool outputs and file-viewer
  content that contain unified diffs are syntax-highlighted (added lines green, removed lines red,
  hunk headers); **agents & commands UI** — `GET /agent` powers a per-message agent picker (sent via
  the `agent` field on the prompt body); `GET /command` powers a slash-command runner that sends the
  command template with its configured agent; **multi-server quick-switch** — the session list title
  is a dropdown that lists all saved servers and switches instantly without navigating back.
- **M7:** **UX polish pass.** Draft retention (failed sends restore your text with a Retry action);
  destructive-action confirmations (delete session / remove server); the app bar now shows the real
  session title; smart auto-scroll that won't fight a reader who scrolled up, with a jump-to-latest
  FAB; long-press to copy assistant markdown, plus copy buttons on tool output and diffs; expandable
  tool output (Show more / Show less) instead of a hard truncation; **session list** with relative
  timestamps, last-message previews, and a search filter; tappable **file-browser breadcrumbs**;
  a "connected" marker on the active server; permission dialog with clear action hierarchy;
  dynamic "Thinking…" → "Thoughts" reasoning label; back-press guard while an agent is running;
  polished empty states; richer **Settings** (Dynamic Color / Material You toggle, version info,
  manage-servers link); haptic feedback; and a file viewer with line numbers, copy, and share.
  The session list also supports **renaming** a session (`PATCH /session/:id`).

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
```

## Install & run

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n soy.iko.opencode/.MainActivity
adb logcat --pid=$(adb shell pidof soy.iko.opencode)
```

## Connect to a server

On the dev host:

```bash
opencode serve --hostname 0.0.0.0 --port 4096
```

In the app, add a server with base URL `http://<host-lan-ip>:4096`. If the host is reachable only
over USB, use `adb reverse tcp:4096 tcp:4096` and point the app at `http://127.0.0.1:4096`.

**Auth** is optional: opencode has no auth by default. If the server runs with
`OPENCODE_SERVER_PASSWORD=...`, enter username `opencode` (the default) and that password — the app
sends HTTP Basic auth. The app permits cleartext HTTP (opencode serves plain `http://`).
