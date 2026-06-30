# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

## Project

opencode-android is a native Android client (Kotlin + Jetpack Compose) for the
[`opencode serve`](https://opencode.ai/docs/server/) HTTP API. It talks to a
remote opencode server over REST plus a long-lived Server-Sent Events stream,
and renders conversations (markdown, diffs, tool calls, reasoning, images) with
an adaptive single-pane / two-pane Material 3 UI.

- Application id / namespace: `soy.iko.opencode`
- Package root: `app/src/main/java/soy/iko/opencode`
- minSdk 26, compileSdk/targetSdk 35, JDK 17, Kotlin 2.0.x, AGP 8.7.x
- Single Gradle module (`:app`); no dynamic feature modules.

## Build & environment

A Nix flake provides the exact toolchain (JDK 17, Gradle, Android SDK 35, and a
Nix-built `aapt2` override because Gradle's bundled aapt2 segfaults on NixOS).

```bash
nix develop                    # enter the dev shell (sets ANDROID_HOME, JAVA_HOME, GRADLE_OPTS)
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # R8-minified + resource-shrunk; signed only when OPENCODE_STORE_* env vars are set
```

Outside Nix, you need JDK 17 and the Android SDK (platform 35, build-tools
35.0.0) installed manually; `ANDROID_HOME` / `ANDROID_SDK_ROOT` must point at it.

Install on a connected device/emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n soy.iko.opencode/.MainActivity
```

## Test, lint, and static analysis

```bash
./gradlew testDebugUnitTest        # JVM unit tests (JSON/reducer/ViewModel logic)
./gradlew detekt                   # static analysis; config: config/detekt/detekt.yml, baseline: app/detekt-baseline.xml
./gradlew lintDebug                # Android Lint (abortOnError = true)
./gradlew connectedAndroidTest     # instrumented tests; needs a running AVD or device
```

- Unit tests live in `app/src/test/...` and run on the JVM. They use fakes from
  `app/src/test/java/soy/iko/opencode/ui/testing/TestFakes.kt` (Fake* subclasses
  that override the `open` members of the real stores/clients). JUnit 4 +
  `kotlinx-coroutines-test`; no Robolectric — `testOptions.unitTests.isReturnDefaultValues = true`
  stubs Android framework calls so tests brushing `Log.d` etc. don't need it.
- Instrumented tests live in `app/src/androidTest/...` and need a device.
- detekt runs with a baseline (`app/detekt-baseline.xml`) so only **new**
  findings block CI; existing ones are grandfathered.
- CI (`.github/workflows/build.yml`) runs: `testDebugUnitTest` → `detekt` →
  `lintDebug` → `assembleDebug` → `assembleRelease`, then uploads both APKs.

## Running a server to develop against

```bash
opencode serve --hostname 0.0.0.0 --port 4096
```

Connect the app over LAN (`http://<host-ip>:4096`) or USB
(`adb reverse tcp:4096 tcp:4096` then `http://127.0.0.1:4096`). Auth is optional
(`OPENCODE_SERVER_PASSWORD`; username defaults to `opencode`). See the README's
"Set up a server and connect to it" section for full details.

## Architecture

Unidirectional data flow with manual DI, no annotation processing (no Hilt/KSP).

```
OpencodeApp (Application)
  └─ AppContainer (service locator: profile/settings/draft stores, active connection,
                   unread tracker, auto-reconnect, pending share / open-session signals)
       └─ OpencodeConnection (one per active server profile)
            ├─ HttpClient (Ktor + OkHttp, base URL + Basic auth + JSON + SSE)
            ├─ OpencodeApiClient   (REST: sessions, messages, files, providers, agents, commands, permissions)
            ├─ EventStreamClient   (long-lived GET /event SSE -> hot SharedFlow<BusEvent>)
            └─ SessionRepository   (reduces BusEvents + REST seeds -> Flow<List<MessageWithParts>>)
                  └─ MessageStore (in-memory reducer; internal, unit-tested)

ViewModels (ui/<feature>/<Feature>ViewModel.kt)
  └─ expose StateFlow; Compose collects with collectAsStateWithLifecycle
  └─ built via ui/VmFactory.kt (viewModelFactory { initializer { ... } }) from the AppContainer
```

Key invariants:

- **Subscribe before you prompt.** `SessionRepository.observeMessages` collects
  the SSE stream *before* the initial REST load and before any prompt POST, so
  early streamed parts aren't missed. Preserve this ordering if you touch it.
- **Resilient decoding.** `OpencodeJson` registers `polymorphicDefaultDeserializer`
  for `Part`, `BusEvent`, `MessageInfo`, and `ToolState`, so an unknown
  discriminator decodes to an `Unknown*` / `ToolUnknown` variant instead of
  throwing. Never add a `when (event)` that isn't exhaustive or that crashes on
  `UnknownEvent` — the whole point is that a new server release can't break the
  client. Add new modeled variants alongside the `Unknown` fallback.
- **Conflated message stream.** `observeMessages` is `.conflate()`d so a burst of
  per-token snapshots doesn't back-pressure the reducer. Don't remove this.
- **Re-seed on SSE reconnect.** When `EventStreamClient.state` returns to
  `Connected` after a prior connection, the repository re-fetches messages from
  REST and merges (without pruning) under a generation counter so a stale fetch
  can't clobber a newer one. See `SessionRepository.kt` for the contract.
- **One Ktor client per profile.** `HttpClientFactory.create(profile)` carries
  the base URL, JSON negotiation, SSE plugin, and auth. Basic auth is sent
  eagerly (no 401 challenge round-trip) and only installed for HTTPS profiles;
  for HTTP profiles the `Authorization` header is set proactively in
  `defaultRequest` instead, and the reactive Auth plugin is deliberately NOT
  installed so credentials can't be re-sent silently on a cleartext 401.
- **Process lifetime.** `OpencodeApp` installs a `CrashLogger`, builds the
  `AppContainer`, and registers a JVM shutdown hook (since `onTerminate()` isn't
  called on real devices) that cancels the app scope and closes the active
  connection. `AppContainer.shutdown()` is guarded against double-invocation.

## Source map

```
data/model      Wire models (kotlinx.serialization, sealed polymorphism, @Immutable)
data/network    Ktor: HttpClientFactory, OpencodeApiClient (REST), EventStreamClient (SSE),
                OpencodeJson (shared Json), NetworkConfig (all tuning constants)
data/repo       SessionRepository + MessageStore (reducer), ProfileStore (DataStore +
                EncryptedSharedPreferences), SettingsStore (theme/dynamic color),
                DraftStore (per-session draft persistence), CrashLogger, ErrorKind
di              AppContainer (service locator) + OpencodeConnection (per-server bundle)
notification    NotificationChannels, RunForegroundService (holds process priority
                during a run), SessionNotifications (completion notifications)
ui              OpencodeApp (NavHost), Routes, VmFactory, theme/
ui/chat         ChatScreen + ChatViewModel, message bubbles, part rendering,
                agent/model/command picker sheets, permission dialog, export
ui/session      SessionListScreen + ViewModel, TwoPaneSessionChat (≥840dp)
ui/server       ServerListScreen + ServerEditScreen (+ ViewModels)
ui/file         FileBrowserScreen + FileViewScreen (+ ViewModels)
ui/settings     SettingsScreen, DiagnosticsScreen (crash reports)
util            Coroutines.kt (runCatchingCancellable, safeExceptionSummary)
```

## Conventions

- **No comments unless asked** — existing code has extensive `//` comments
  explaining *why* (invariants, race conditions, security rationale). When you
  add code, match that style only for genuinely non-obvious invariants; don't
  narrate *what* the code does.
- **`runCatchingCancellable`** (`util/Coroutines.kt`) instead of `runCatching`
  inside coroutines — plain `runCatching` swallows `CancellationException` and
  breaks structured concurrency. Use it for any try/catch in a suspend context.
- **`safeExceptionSummary`** for logging Ktor exceptions — `ClientRequestException`
  messages embed the full request URL (may contain auth/paths); never log the
  raw exception from an HTTP call. Crash reports scrub URLs via a regex too.
- **`open` classes with protected no-arg test constructors.** Stores, the API
  client, `EventStreamClient`, `OpencodeConnection`, and `AppContainer` are
  `open` and have a `protected constructor()` so tests can subclass and override
  individual members without instantiating Android frameworks. Keep this pattern
  when adding new injectable components.
- **`NetworkConfig`** holds all tuning constants (timeouts, backoffs, buffer
  sizes, layout thresholds, debounce delays). Add new magic numbers here rather
  than scattering literals.
- **REST path encoding.** `OpencodeApiClient` URL-encodes path segments via
  `encode()` (e.g. `session/${encode(id)}`). Deep-link session ids are validated
  against `[A-Za-z0-9_-]+` in `MainActivity` to prevent path traversal.
- **Idempotency keys.** `sendPrompt` and `runCommand` generate the
  `Idempotency-Key` header *before* `withRetry` so all retry attempts share it.
  Preserve this when adding mutating endpoints.
- **`withRetry`** wraps all REST reads and the permission response. Transient
  failures back off with jitter; see `NetworkConfig` for the parameters.
- **Compose:** `collectAsStateWithLifecycle` everywhere; `WhileSubscribed` with a
  5s grace period (`stateFlowSubscriptionTimeoutMs`). `@Composable` functions
  use PascalCase (detekt `FunctionNaming` disabled for this reason). Long
  composables and long parameter lists are accepted (detekt `LongMethod` /
  `LongParameterList` disabled).
- **Manifest:** cleartext HTTP is permitted via `network_security_config.xml`
  (opencode serves plain `http://`). `enableOnBackInvokedCallback="true"` for
  Android 14 predictive back. Deep link scheme `opencode://session/{id}`.
- **`@Immutable`** on data models exposed to Compose so they skip recomposition
  correctly.
- **Kotlin code style: `official`** (see `gradle.properties`).

## Things to avoid

- Don't add Hilt/Dagger/KSP — DI is intentionally hand-written (`AppContainer`).
- Don't add Robolectric — JVM tests use `isReturnDefaultValues = true` stubs and
  fakes; only device-dependent code goes in `androidTest`.
- Don't run `nix flake check` manually (CI only — per repo convention).
- Don't read or query `/nix/store` (extremely large — per repo convention).
- Don't introduce new detekt findings; the baseline only grandfathers existing
  ones. Run `./gradlew detekt` before considering work done.
- Don't log raw Ktor/OkHttp exceptions — use `safeExceptionSummary`.
- Don't make a `when (BusEvent)` / `when (Part)` non-exhaustive; the `Unknown*`
  variants exist for forward compatibility and must remain reachable.

## Renovate

`renovate.json` keeps dependencies current: Kotlin+AGP and Compose are grouped;
Ktor is grouped; AndroidX majors are held for manual review (pinned to
compileSdk 35). Dependency upgrades land via Renovate PRs, not manual bumps.
