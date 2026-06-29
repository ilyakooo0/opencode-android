# opencode-android

A native Android client (Kotlin + Jetpack Compose) for the [`opencode serve`](https://opencode.ai/docs/server/) HTTP API.

Application id: `soy.iko.opencode`

## Status

Milestones M0–M12 complete:

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
- **M8:** **Enter-to-send** (hardware-keyboard Enter sends, Shift+Enter for newline);
  **per-session draft persistence** — typed text survives back-navigation and process
  death; **message timestamps** (relative time on every bubble); **VCS status in the file
  browser** — `GET /file/status` powers added/modified/deleted badges with `+N/−N` line
  counts on tracked files; **connectivity-aware SSE reconnect** — a `ConnectivityManager`
  callback triggers an immediate reconnect (skipping backoff) when the device regains
  network; cleaner **token/cost formatting** (`1,234 in · 5,678 out`, precision-aware cost);
  and **bounded preview loading** (concurrency-capped, cancelled on refresh) so the session
  list never floods the server with parallel requests.

- **M9:** **Live session list** — `session.updated` / `session.deleted` SSE events now
  drive incremental list updates (new sessions, renames, deletions appear without a
  manual refresh); **pull-to-refresh** on the session list; **keep-screen-on** while an
  agent is running so long tasks aren't interrupted by screen-off; **chat loading
  state** — a spinner replaces the empty-conversation flash before the first message
  load; **reconnect button** when the connection is lost mid-chat; **live session
  title** in the chat header (updates when the agent renames the session);
  **connection-aware run indicator** — the spinner resets if the SSE stream drops
  mid-run instead of spinning forever; **retry-on-transient-failure** for all read
  endpoints (sessions, messages, files, providers); and **per-code-block copy
  buttons** in rendered markdown.

- **M10:** **Reliability & UX hardening pass.** Fixed a latent crash where a diff in a
  tool output nested a second `LazyColumn` inside the message list (now a bounded
  column); **inline image rendering** for `file` parts — attachments are decoded
  (data URIs) or fetched with Basic auth via the already-present Coil dependency;
  **connection banner on the session list** so a dropped SSE stream is visible before
  the list goes stale; **unread activity badges** — background sessions that receive a
  reply get a dot until opened; **conflated message stream** so per-token snapshots no
  longer back-pressure the event reducer on long replies; **typed error reporting** —
  network/timeout/HTTP-status failures are classified by concrete exception type (with
  a unit-tested classifier) instead of string-matching class names; **swipe-to-delete**
  on session cards; **conversation export/share** (Markdown via the system share
  sheet); and an **auto-incrementing `versionCode`** derived from the git commit count
  so each release is strictly newer.

- **M11:** **Reliability, a11y & tooling pass.**
  - **Completion notifications** — when a background session finishes a run it posts a
    system notification; tapping it opens the conversation. POST_NOTIFICATIONS is
    requested on Android 13+ and respected if denied.
  - **Foreground SSE service** — while an agent is running a low-priority foreground
    service holds process priority so Doze can't choke the long-lived `/event` stream
    mid-run when the app is backgrounded.
  - **Crash reporting + Diagnostics** — an uncaught-exception handler writes crash
    reports (stack trace + device/app metadata) to app-private storage, surfaced in a
    new Settings → Diagnostics screen where they can be viewed, shared, or cleared. No
    hosted backend required.
  - **Adaptive large-screen layout** — on wide screens (tablets / unfolded foldables,
    ≥ 840dp) the session list and the open conversation show side by side; compact
    widths keep the single-pane back stack.
  - **Deep linking** — `opencode://session/{id}` opens a conversation from any app, and
    notification taps / share-in reuse the same open-session path.
  - **Accessibility** — every clickable row is announced as a button to TalkBack
    (`role` semantics); all icon buttons carry content descriptions.
  - **Crash bug fix** — `StateFlow.value` was read inside composition (server switch
    wouldn't recompose the image context); now collected as state.
  - **App icon** — an adaptive launcher icon was added (lint flagged its absence).
  - **Tooling/CI:** Android **Lint** and **detekt** now run in CI (detekt with a
    baseline so only new findings block); a **Renovate** config keeps dependencies
    current (grouped, weekly, AndroidX majors held for review); the deprecated
    `kotlinOptions` DSL was replaced; and the release-artifact upload matches the
    signed-output path. Instrumented tests (androidTest) cover the crash logger and
    app bootstrap.

- **M12:** **Reliability, a11y & test-coverage pass.**
  - **Diagnostics disk I/O off the main thread** — crash report reads in the
    Diagnostics screen were moved from synchronous `remember` blocks (composition
    / main thread) into coroutines with `Dispatchers.IO`, eliminating potential
    jank on large reports.
  - **Orphaned coroutine scope fix** — `DraftStore` no longer creates an
    unscoped `CoroutineScope` that leaks; it now uses the app-scoped
    `CoroutineScope` from `AppContainer`.
  - **Predictive back** — `android:enableOnBackInvokedCallback="true"` added to
    the manifest so the Android 14+ predictive back gesture integrates properly
    with the app's `BackHandler` usage.
  - **Crash report filename uniqueness** — crash report timestamps now include
    milliseconds so two crashes within the same second don't overwrite each other.
  - **Permission response retry** — `POST /session/:id/permissions/:permissionID`
    now uses the same retry-with-backoff as other REST calls, so a transient
    network failure no longer leaves a tool run stuck without automatic retry.
  - **Accessibility: space-prefix text patterns** — six `"  " + text` patterns
    that screen readers would announce with extra spaces were replaced with
    `Modifier.padding(start = ...)` on the Text composables.
  - **Accessibility: unread badge** — the unread activity dot on session cards
    now carries a `contentDescription` so TalkBack announces it.
  - **Test coverage** — added `OpencodeApiClientTest` (3 tests covering the
    permission-response retry path) and `DraftStoreTest` (instrumented, covering
    set/get/remove/blank-clears round-trip).

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
