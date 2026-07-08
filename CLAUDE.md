# opencode-android

Native Android client for the opencode server, built with Crux (Rust core), Kotlin, and Jetpack Compose.

## Architecture

- **shared/** — Rust Crux core (canonical spec, unit-tested with `cargo test`)
- **app/** — Android shell with Kotlin port of the reducer (`OpencodeCore`)
- All app logic in `OpencodeCore.kt`; UI is a thin Compose shell
- Three screens: Connect → Sessions → Chat

## Key Files

- `app/src/main/java/soy/iko/opencode/core/OpencodeCore.kt` — reducer, all state management
- `app/src/main/java/soy/iko/opencode/core/ViewModel.kt` — UiState, Event, data classes
- `app/src/main/java/soy/iko/opencode/core/Protocol.kt` — wire types, SSE event parser
- `app/src/main/java/soy/iko/opencode/core/HttpClient.kt` — OkHttp wrapper
- `app/src/main/java/soy/iko/opencode/core/SseClient.kt` — SSE stream client
- `app/src/main/java/soy/iko/opencode/ui/screens/` — ConnectScreen, SessionsScreen, ChatScreen
- `app/src/main/java/soy/iko/opencode/ui/components/MessageBubble.kt` — message rendering

## Build

```sh
source .buildenv.sh
nix develop -c ./gradlew assembleDebug
```

## Code Conventions

- Kotlin + Jetpack Compose (Material 3)
- State flows through `MutableStateFlow<UiState>` → `collectAsStateWithLifecycle`
- All state mutations on main dispatcher (viewModelScope)
- Network I/O on `Dispatchers.IO` inside HttpClient/SseClient
- Keep the Rust core and Kotlin port behaviourally identical

## Version Control

- Uses `jj` (jujutsu) colocated with git
- Push to master: `jj git push --change @` to create a bookmark, or commit then push
