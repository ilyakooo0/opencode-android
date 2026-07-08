//! Wire types for the opencode HTTP + SSE API.
//!
//! These mirror the JSON shapes the opencode server emits (`sst/opencode`).
//! They are deliberately lenient: every unknown field is ignored and every
//! discriminated union has a catch-all `Other` arm so that new server versions
//! (which add event / part / tool-state variants over time) never break the
//! stream parser.

use serde::Deserialize;

// ─── Sessions ────────────────────────────────────────────────────────────────

#[derive(Deserialize, Debug, Clone)]
pub struct WireSession {
    pub id: String,
    #[serde(default)]
    pub title: String,
}

// ─── Messages ────────────────────────────────────────────────────────────────

/// One element of `GET /session/{id}/message`: `{ info, parts }`.
#[derive(Deserialize, Debug)]
pub struct WireMessageEnvelope {
    pub info: WireMessageInfo,
    #[serde(default)]
    pub parts: Vec<WirePart>,
}

#[derive(Deserialize, Debug, Clone)]
pub struct WireMessageInfo {
    pub id: String,
    #[serde(default)]
    pub role: String,
    #[serde(default, rename = "sessionID")]
    pub session_id: String,
    #[serde(default)]
    pub time: WireTime,
    #[serde(default)]
    pub error: Option<WireError>,
}

#[derive(Deserialize, Debug, Clone, Default)]
pub struct WireTime {
    #[serde(default)]
    pub created: u64,
    /// Present once an assistant message has finished generating.
    #[serde(default)]
    pub completed: Option<u64>,
}

/// Message / part content. Only the variants a chat UI renders are modelled;
/// everything else (`file`, `agent`, `step-start`, `step-finish`, `snapshot`,
/// `patch`, …) falls through to `Other`.
#[derive(Deserialize, Debug, Clone)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum WirePart {
    Text {
        #[serde(default)]
        id: String,
        #[serde(default, rename = "messageID")]
        message_id: String,
        #[serde(default)]
        text: String,
        #[serde(default)]
        synthetic: bool,
    },
    Reasoning {
        #[serde(default)]
        id: String,
        #[serde(default, rename = "messageID")]
        message_id: String,
        #[serde(default)]
        text: String,
    },
    Tool {
        #[serde(default)]
        id: String,
        #[serde(default, rename = "messageID")]
        message_id: String,
        #[serde(default)]
        tool: String,
        #[serde(default)]
        state: serde_json::Value,
    },
    #[serde(other)]
    Other,
}

impl WirePart {
    pub fn message_id(&self) -> &str {
        match self {
            WirePart::Text { message_id, .. }
            | WirePart::Reasoning { message_id, .. }
            | WirePart::Tool { message_id, .. } => message_id,
            WirePart::Other => "",
        }
    }
}

/// Compact view of a `tool` part's state (the union keyed on `status`).
pub struct ToolStateView {
    pub status: String,
    pub title: Option<String>,
}

/// Pull the `status` and a human title/output out of a tool part's `state` blob.
pub fn tool_state_view(state: &serde_json::Value) -> ToolStateView {
    let status = state
        .get("status")
        .and_then(|v| v.as_str())
        .unwrap_or("pending")
        .to_string();
    let title = state
        .get("title")
        .and_then(|v| v.as_str())
        .or_else(|| state.get("error").and_then(|v| v.as_str()))
        .map(str::to_string);
    ToolStateView { status, title }
}

// ─── Errors ──────────────────────────────────────────────────────────────────

#[derive(Deserialize, Debug, Clone)]
pub struct WireError {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub data: serde_json::Value,
}

impl WireError {
    /// Best human-readable message: `data.message` if present, else the error name.
    pub fn message(&self) -> String {
        self.data
            .get("message")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| {
                if self.name.is_empty() {
                    "Unknown error".to_string()
                } else {
                    self.name.clone()
                }
            })
    }
}

// ─── SSE `/event` stream ─────────────────────────────────────────────────────

/// The SSE envelope: `{ "type": "...", "properties": { ... } }`. Unknown event
/// types decode to `Other` and are ignored.
#[derive(Deserialize, Debug)]
#[serde(tag = "type")]
pub enum ServerEvent {
    #[serde(rename = "message.updated")]
    MessageUpdated { properties: MessageUpdatedProps },
    #[serde(rename = "message.part.updated")]
    MessagePartUpdated { properties: PartUpdatedProps },
    #[serde(rename = "message.part.removed")]
    MessagePartRemoved { properties: PartRemovedProps },
    #[serde(rename = "message.removed")]
    MessageRemoved { properties: MessageRemovedProps },
    #[serde(rename = "session.idle")]
    SessionIdle { properties: SessionIdProps },
    #[serde(rename = "session.error")]
    SessionError { properties: SessionErrorProps },
    #[serde(rename = "session.updated")]
    SessionUpdated { properties: SessionInfoProps },
    #[serde(rename = "session.created")]
    SessionCreated { properties: SessionInfoProps },
    #[serde(rename = "session.deleted")]
    SessionDeleted { properties: SessionDeletedProps },
    #[serde(other)]
    Other,
}

#[derive(Deserialize, Debug)]
pub struct MessageUpdatedProps {
    pub info: WireMessageInfo,
}

#[derive(Deserialize, Debug)]
pub struct PartUpdatedProps {
    pub part: WirePart,
    #[serde(default)]
    pub delta: Option<String>,
}

#[derive(Deserialize, Debug)]
pub struct PartRemovedProps {
    #[serde(default, rename = "messageID")]
    pub message_id: String,
    #[serde(default, rename = "partID")]
    pub part_id: String,
}

#[derive(Deserialize, Debug)]
pub struct MessageRemovedProps {
    #[serde(default, rename = "messageID")]
    pub message_id: String,
}

#[derive(Deserialize, Debug)]
pub struct SessionIdProps {
    #[serde(default, rename = "sessionID")]
    pub session_id: String,
}

#[derive(Deserialize, Debug)]
pub struct SessionErrorProps {
    #[serde(default, rename = "sessionID")]
    pub session_id: Option<String>,
    #[serde(default)]
    pub error: Option<WireError>,
}

#[derive(Deserialize, Debug)]
pub struct SessionInfoProps {
    pub info: WireSession,
}

#[derive(Deserialize, Debug)]
pub struct SessionDeletedProps {
    #[serde(default, rename = "sessionID")]
    pub session_id: Option<String>,
    #[serde(default)]
    pub info: Option<WireSession>,
}

// ─── Parse helpers ───────────────────────────────────────────────────────────

pub fn parse_sessions(body: &str) -> Vec<WireSession> {
    serde_json::from_str(body).unwrap_or_default()
}

pub fn parse_session(body: &str) -> Option<WireSession> {
    serde_json::from_str(body).ok()
}

pub fn parse_messages(body: &str) -> Vec<WireMessageEnvelope> {
    serde_json::from_str(body).unwrap_or_default()
}

pub fn parse_event(json: &str) -> Option<ServerEvent> {
    serde_json::from_str(json).ok()
}
