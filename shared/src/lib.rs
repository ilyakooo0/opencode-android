//! Crux core for the opencode Android client.
//!
//! The core owns all state and logic (connect/auth, session list, chat, and the
//! streaming of assistant replies from the SSE event stream). The platform shell
//! (Android / Jetpack Compose) is a thin driver: it renders [`ViewModel`], turns
//! user actions into [`Event`]s, performs the effects the core asks for (HTTP
//! requests, and — outside the core — the `/event` SSE subscription), and feeds
//! server-sent events back in as [`Event::ServerEvent`].
//!
//! Because HTTP request/response is the only network capability Crux models,
//! the long-lived SSE connection lives in the shell; each decoded event is
//! handed to the core verbatim as a JSON string.

mod app;
mod protocol;

pub use app::*;
