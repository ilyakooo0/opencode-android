//! The Crux `App`: model, events, effects, update logic and view.
//!
//! Streaming works like this: after a successful connect the shell opens the
//! `/event` SSE stream and forwards every decoded event as
//! [`Event::ServerEvent`]. The core parses those and mutates the chat
//! incrementally — creating the assistant message on `message.updated`, growing
//! its text on `message.part.updated`, and clearing the "generating" flag on
//! `session.idle`. User messages are shown optimistically the moment they're
//! sent, so the stream only ever *adds* assistant content.

use base64::Engine;
use crux_core::{
    macros::effect,
    render::{render, RenderOperation},
    App, Command,
};
use crux_http::command::{Http, RequestBuilder};
use crux_http::protocol::HttpRequest;
use serde::{Deserialize, Serialize};

use crate::protocol::{self, ServerEvent, WireMessageEnvelope, WirePart};

/// Result of an HTTP effect handed back to the core.
pub type HttpResult = Result<crux_http::Response<String>, crux_http::HttpError>;

// ─── Events ──────────────────────────────────────────────────────────────────

pub enum Event {
    // Lifecycle / connect
    Start,
    ServerUrlChanged(String),
    UsernameChanged(String),
    PasswordChanged(String),
    Connect,

    // Sessions
    LoadSessions,
    SelectSession(String),
    CreateSession,
    DeleteSession(String),

    // Chat
    LoadMessages(String),
    DraftChanged(String),
    SendMessage(String),
    CancelGeneration,

    // A raw event line from the shell's `/event` SSE subscription.
    ServerEvent(String),

    // Navigation / misc
    NavigateToSessions,
    NavigateToConnect,
    DismissError,

    // ── Internal: results of HTTP effects (never sent by the shell) ──────────
    HealthProbed(HttpResult),
    SessionsLoaded(HttpResult),
    SessionCreated(HttpResult),
    MessagesLoaded(String, HttpResult),
    PromptSent(HttpResult),
    SessionDeleted(HttpResult),
    Aborted(HttpResult),
}

// ─── Effects ─────────────────────────────────────────────────────────────────

#[effect]
#[derive(Debug)]
pub enum Effect {
    Render(RenderOperation),
    Http(HttpRequest),
}

// ─── Model ───────────────────────────────────────────────────────────────────

#[derive(Default)]
pub struct Model {
    server_url: String,
    username: String,
    password: String,
    /// The server answered 401 to an unauthenticated probe — show credentials.
    auth_required: bool,
    /// Credentials were accepted; attach the auth header to every request.
    authed: bool,
    connected: bool,
    loading: bool,
    error: Option<String>,
    screen: Screen,

    sessions: Vec<SessionView>,
    current_session_id: Option<String>,
    current_session_title: String,

    messages: Vec<MsgState>,
    draft: String,
    generating: bool,

    /// Monotonic counter for optimistic (client-side) user-message ids.
    local_seq: u64,
}

impl Model {
    fn message_mut(&mut self, id: &str) -> Option<&mut MsgState> {
        self.messages.iter_mut().find(|m| m.id == id)
    }

    fn has_credentials(&self) -> bool {
        !self.username.is_empty() || !self.password.is_empty()
    }

    fn is_current(&self, session_id: &str) -> bool {
        self.current_session_id.as_deref() == Some(session_id)
    }
}

/// Internal per-message state. Parts are tracked by id so streaming updates can
/// upsert them; the [`MessageView`] handed to the shell is derived from these.
struct MsgState {
    id: String,
    role: String,
    time: u64,
    status: MessageStatus,
    streaming: bool,
    text_parts: Vec<(String, String)>,
    reasoning_parts: Vec<(String, String)>,
    tool_parts: Vec<(String, ToolView)>,
}

impl MsgState {
    fn new(id: String, role: String, time: u64) -> Self {
        Self {
            id,
            role,
            time,
            status: MessageStatus::Sent,
            streaming: false,
            text_parts: Vec::new(),
            reasoning_parts: Vec::new(),
            tool_parts: Vec::new(),
        }
    }

    fn upsert_part(parts: &mut Vec<(String, String)>, id: &str, text: String) {
        if let Some(slot) = parts.iter_mut().find(|(pid, _)| pid == id) {
            slot.1 = text;
        } else {
            parts.push((id.to_string(), text));
        }
    }

    fn upsert_tool(&mut self, id: &str, view: ToolView) {
        if let Some(slot) = self.tool_parts.iter_mut().find(|(pid, _)| pid == id) {
            slot.1 = view;
        } else {
            self.tool_parts.push((id.to_string(), view));
        }
    }

    fn remove_part(&mut self, part_id: &str) {
        self.text_parts.retain(|(pid, _)| pid != part_id);
        self.reasoning_parts.retain(|(pid, _)| pid != part_id);
        self.tool_parts.retain(|(pid, _)| pid != part_id);
    }

    fn to_view(&self) -> MessageView {
        let join = |parts: &[(String, String)]| {
            parts.iter().map(|(_, t)| t.as_str()).collect::<String>()
        };
        let reasoning = join(&self.reasoning_parts);
        MessageView {
            id: self.id.clone(),
            role: self.role.clone(),
            text: join(&self.text_parts),
            reasoning: (!reasoning.is_empty()).then_some(reasoning),
            tools: self.tool_parts.iter().map(|(_, t)| t.clone()).collect(),
            time: self.time,
            status: self.status.clone(),
            streaming: self.streaming,
        }
    }
}

// ─── View model ──────────────────────────────────────────────────────────────

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
pub enum Screen {
    #[default]
    Connect,
    Sessions,
    Chat,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct SessionView {
    pub id: String,
    pub title: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
pub enum MessageStatus {
    #[default]
    Sent,
    Pending,
    Failed,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ToolView {
    pub name: String,
    pub status: String,
    pub title: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct MessageView {
    pub id: String,
    pub role: String,
    pub text: String,
    pub reasoning: Option<String>,
    pub tools: Vec<ToolView>,
    pub time: u64,
    pub status: MessageStatus,
    pub streaming: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ViewModel {
    pub screen: Screen,
    pub server_url: String,
    pub username: String,
    pub password: String,
    pub auth_required: bool,
    pub connected: bool,
    pub loading: bool,
    pub error: Option<String>,
    pub sessions: Vec<SessionView>,
    pub current_session_id: Option<String>,
    pub current_session_title: String,
    pub messages: Vec<MessageView>,
    pub draft: String,
    pub generating: bool,
}

// ─── App ─────────────────────────────────────────────────────────────────────

#[derive(Default)]
pub struct OpencodeApp;

impl OpencodeApp {
    fn get(&self, model: &Model, path: &str) -> RequestBuilder<Effect, Event, String> {
        let url = format!("{}{path}", model.server_url);
        self.with_auth(Http::get(&url).expect_string(), model)
    }

    fn post_json(&self, model: &Model, path: &str, body: &serde_json::Value) -> RequestBuilder<Effect, Event, String> {
        let url = format!("{}{path}", model.server_url);
        let bytes = serde_json::to_vec(body).unwrap_or_default();
        let req = Http::post(&url)
            .expect_string()
            .header("Content-Type", "application/json")
            .body_bytes(bytes);
        self.with_auth(req, model)
    }

    fn delete(&self, model: &Model, path: &str) -> RequestBuilder<Effect, Event, String> {
        let url = format!("{}{path}", model.server_url);
        self.with_auth(Http::delete(&url).expect_string(), model)
    }

    /// Attach an RFC 7617 Basic-Auth header when the model carries credentials.
    fn with_auth<E>(&self, req: RequestBuilder<Effect, Event, E>, model: &Model) -> RequestBuilder<Effect, Event, E>
    where
        E: 'static,
    {
        if model.has_credentials() {
            let creds = format!("{}:{}", model.username, model.password);
            let encoded = base64::engine::general_purpose::STANDARD.encode(creds.as_bytes());
            req.header("Authorization", format!("Basic {encoded}"))
        } else {
            req
        }
    }
}

impl App for OpencodeApp {
    type Event = Event;
    type Model = Model;
    type ViewModel = ViewModel;
    type Effect = Effect;

    fn update(&self, event: Event, model: &mut Model) -> Command<Effect, Event> {
        match event {
            // ── Lifecycle / connect ─────────────────────────────────────────
            Event::Start => render(),

            // Store the field verbatim while typing; normalizing here would fight
            // the user's edits (e.g. eat the "://" as they type it). The URL is
            // normalized once, on Connect.
            Event::ServerUrlChanged(url) => {
                model.server_url = url;
                render()
            }
            Event::UsernameChanged(u) => {
                model.username = u;
                render()
            }
            Event::PasswordChanged(p) => {
                model.password = p;
                render()
            }

            Event::Connect => {
                model.server_url = normalize_url(&model.server_url);
                if model.server_url.is_empty() {
                    model.error = Some("Enter a server URL".to_string());
                    return render();
                }
                model.loading = true;
                model.error = None;
                model.auth_required = false;
                render().and(self.get(model, "/global/health").build().then_send(Event::HealthProbed))
            }

            Event::HealthProbed(result) => self.on_health_probed(model, result),

            // ── Sessions ────────────────────────────────────────────────────
            Event::LoadSessions => {
                model.loading = true;
                render().and(self.get(model, "/session").build().then_send(Event::SessionsLoaded))
            }

            Event::SessionsLoaded(result) => {
                model.loading = false;
                match ok_body(result) {
                    Ok(body) => {
                        model.sessions = protocol::parse_sessions(&body)
                            .into_iter()
                            .map(|s| SessionView {
                                id: s.id,
                                title: display_title(&s.title),
                            })
                            .collect();
                    }
                    Err(e) => model.error = Some(e),
                }
                render()
            }

            Event::SelectSession(id) => {
                model.current_session_title = model
                    .sessions
                    .iter()
                    .find(|s| s.id == id)
                    .map(|s| s.title.clone())
                    .unwrap_or_default();
                model.current_session_id = Some(id.clone());
                model.messages.clear();
                model.screen = Screen::Chat;
                model.loading = true;
                model.error = None;
                render().and(Command::event(Event::LoadMessages(id)))
            }

            Event::CreateSession => {
                model.loading = true;
                render().and(
                    self.post_json(model, "/session", &serde_json::json!({}))
                        .build()
                        .then_send(Event::SessionCreated),
                )
            }

            Event::SessionCreated(result) => {
                model.loading = false;
                match ok_body(result) {
                    Ok(body) => {
                        if let Some(s) = protocol::parse_session(&body) {
                            let view = SessionView {
                                id: s.id.clone(),
                                title: display_title(&s.title),
                            };
                            if !model.sessions.iter().any(|e| e.id == view.id) {
                                model.sessions.insert(0, view);
                            }
                            return render().and(Command::event(Event::SelectSession(s.id)));
                        }
                        model.error = Some("Malformed session response".to_string());
                    }
                    Err(e) => model.error = Some(e),
                }
                render()
            }

            Event::DeleteSession(id) => {
                model.sessions.retain(|s| s.id != id);
                let mut cmd = self.delete(model, &format!("/session/{id}")).build().then_send(Event::SessionDeleted);
                if model.is_current(&id) {
                    model.current_session_id = None;
                    model.messages.clear();
                    model.screen = Screen::Sessions;
                }
                cmd = render().and(cmd);
                cmd
            }

            Event::SessionDeleted(result) => {
                if let Err(e) = ok_body(result) {
                    model.error = Some(e);
                }
                render()
            }

            // ── Chat ────────────────────────────────────────────────────────
            Event::LoadMessages(id) => {
                render().and(
                    self.get(model, &format!("/session/{id}/message"))
                        .build()
                        .then_send(move |result| Event::MessagesLoaded(id.clone(), result)),
                )
            }

            Event::MessagesLoaded(session_id, result) => {
                if !model.is_current(&session_id) {
                    return render();
                }
                model.loading = false;
                match ok_body(result) {
                    Ok(body) => {
                        model.messages = protocol::parse_messages(&body).iter().map(message_from_wire).collect();
                        model.generating = model.messages.iter().any(|m| m.streaming);
                    }
                    Err(e) => model.error = Some(e),
                }
                render()
            }

            Event::DraftChanged(text) => {
                model.draft = text;
                render()
            }

            Event::SendMessage(text) => {
                let text = text.trim().to_string();
                let Some(session_id) = model.current_session_id.clone() else {
                    return render();
                };
                if text.is_empty() {
                    return render();
                }
                model.local_seq += 1;
                let mut user = MsgState::new(format!("local-{}", model.local_seq), "user".to_string(), 0);
                user.status = MessageStatus::Pending;
                MsgState::upsert_part(&mut user.text_parts, "local", text.clone());
                model.messages.push(user);
                model.draft.clear();
                model.generating = true;
                model.error = None;

                let body = serde_json::json!({ "parts": [{ "type": "text", "text": text }] });
                render().and(
                    self.post_json(model, &format!("/session/{session_id}/prompt_async"), &body)
                        .build()
                        .then_send(Event::PromptSent),
                )
            }

            Event::PromptSent(result) => {
                match ok_body(result) {
                    Ok(_) => set_last_pending(model, MessageStatus::Sent),
                    Err(e) => {
                        set_last_pending(model, MessageStatus::Failed);
                        model.generating = false;
                        model.error = Some(e);
                    }
                }
                render()
            }

            Event::CancelGeneration => {
                model.generating = false;
                match model.current_session_id.clone() {
                    Some(id) => render().and(
                        self.post_json(model, &format!("/session/{id}/abort"), &serde_json::json!({}))
                            .build()
                            .then_send(Event::Aborted),
                    ),
                    None => render(),
                }
            }

            Event::Aborted(_) => render(),

            // ── SSE ─────────────────────────────────────────────────────────
            Event::ServerEvent(json) => {
                if let Some(event) = protocol::parse_event(&json) {
                    apply_server_event(model, event);
                }
                render()
            }

            // ── Navigation / misc ───────────────────────────────────────────
            Event::NavigateToSessions => {
                model.screen = Screen::Sessions;
                model.current_session_id = None;
                model.messages.clear();
                model.generating = false;
                render().and(self.get(model, "/session").build().then_send(Event::SessionsLoaded))
            }

            Event::NavigateToConnect => {
                model.screen = Screen::Connect;
                model.connected = false;
                model.sessions.clear();
                model.messages.clear();
                model.current_session_id = None;
                render()
            }

            Event::DismissError => {
                model.error = None;
                render()
            }
        }
    }

    fn view(&self, model: &Model) -> ViewModel {
        ViewModel {
            screen: model.screen.clone(),
            server_url: model.server_url.clone(),
            username: model.username.clone(),
            password: model.password.clone(),
            auth_required: model.auth_required,
            connected: model.connected,
            loading: model.loading,
            error: model.error.clone(),
            sessions: model.sessions.clone(),
            current_session_id: model.current_session_id.clone(),
            current_session_title: model.current_session_title.clone(),
            messages: model.messages.iter().map(MsgState::to_view).collect(),
            draft: model.draft.clone(),
            generating: model.generating,
        }
    }
}

impl OpencodeApp {
    fn on_health_probed(&self, model: &mut Model, result: HttpResult) -> Command<Effect, Event> {
        let has_creds = model.has_credentials();
        match result {
            Ok(response) => {
                let status = response.status().as_u16();
                if status == 401 {
                    model.loading = false;
                    if has_creds {
                        model.error = Some("Invalid credentials".to_string());
                    } else {
                        model.auth_required = true;
                    }
                    return render();
                }
                if (200..300).contains(&status) {
                    if has_creds {
                        model.authed = true;
                    }
                    model.connected = true;
                    model.auth_required = false;
                    model.error = None;
                    model.screen = Screen::Sessions;
                    model.loading = true;
                    return render().and(self.get(model, "/session").build().then_send(Event::SessionsLoaded));
                }
                model.loading = false;
                model.error = Some(format!("Server returned status {status}"));
                render()
            }
            Err(e) => {
                model.loading = false;
                model.error = Some(format!("Connection failed: {e}"));
                render()
            }
        }
    }
}

// ─── SSE application ─────────────────────────────────────────────────────────

fn apply_server_event(model: &mut Model, event: ServerEvent) {
    match event {
        // Only assistant messages are created from the stream — the user's own
        // message is already shown optimistically, so ignoring the server's copy
        // avoids a duplicate bubble.
        ServerEvent::MessageUpdated { properties } => {
            let info = properties.info;
            if info.role != "assistant" || !model.is_current(&info.session_id) {
                return;
            }
            let streaming = info.time.completed.is_none() && info.error.is_none();
            if let Some(msg) = model.message_mut(&info.id) {
                msg.streaming = streaming;
                if msg.time == 0 {
                    msg.time = info.time.created;
                }
            } else {
                let mut msg = MsgState::new(info.id.clone(), "assistant".to_string(), info.time.created);
                msg.streaming = streaming;
                model.messages.push(msg);
            }
            if let Some(err) = info.error {
                model.error = Some(err.message());
                model.generating = false;
            }
        }

        // Apply only to a message we already track (an assistant message from the
        // event above). Parts for unknown ids are ignored — see module docs.
        ServerEvent::MessagePartUpdated { properties } => {
            let part = properties.part;
            let delta = properties.delta;
            let msg_id = part.message_id().to_string();
            let Some(msg) = model.message_mut(&msg_id) else {
                return;
            };
            match part {
                WirePart::Text { id, text, synthetic, .. } => {
                    if synthetic {
                        return;
                    }
                    let value = pick_text(text, &delta, &msg.text_parts, &id);
                    MsgState::upsert_part(&mut msg.text_parts, &id, value);
                }
                WirePart::Reasoning { id, text, .. } => {
                    let value = pick_text(text, &delta, &msg.reasoning_parts, &id);
                    MsgState::upsert_part(&mut msg.reasoning_parts, &id, value);
                }
                WirePart::Tool { id, tool, state, .. } => {
                    let sv = protocol::tool_state_view(&state);
                    msg.upsert_tool(&id, ToolView { name: tool, status: sv.status, title: sv.title });
                }
                WirePart::Other => {}
            }
        }

        ServerEvent::MessagePartRemoved { properties } => {
            if let Some(msg) = model.message_mut(&properties.message_id) {
                msg.remove_part(&properties.part_id);
            }
        }

        ServerEvent::MessageRemoved { properties } => {
            model.messages.retain(|m| m.id != properties.message_id);
        }

        ServerEvent::SessionIdle { properties } => {
            if model.is_current(&properties.session_id) {
                model.generating = false;
                for m in &mut model.messages {
                    m.streaming = false;
                }
            }
        }

        ServerEvent::SessionError { properties } => {
            let scoped = properties.session_id.as_deref().map_or(true, |s| model.is_current(s));
            if scoped {
                if let Some(err) = properties.error {
                    model.error = Some(err.message());
                }
                model.generating = false;
            }
        }

        ServerEvent::SessionCreated { properties } | ServerEvent::SessionUpdated { properties } => {
            let s = properties.info;
            let view = SessionView { id: s.id.clone(), title: display_title(&s.title) };
            if let Some(existing) = model.sessions.iter_mut().find(|e| e.id == view.id) {
                existing.title = view.title.clone();
            } else {
                model.sessions.insert(0, view);
            }
            if model.is_current(&s.id) {
                model.current_session_title = display_title(&s.title);
            }
        }

        ServerEvent::SessionDeleted { properties } => {
            let id = properties.session_id.or(properties.info.map(|i| i.id));
            if let Some(id) = id {
                model.sessions.retain(|s| s.id != id);
                if model.is_current(&id) {
                    model.current_session_id = None;
                    model.messages.clear();
                    model.screen = Screen::Sessions;
                }
            }
        }

        ServerEvent::Other => {}
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/// `part.text` is the full current text when present; otherwise fall back to
/// accumulating the incremental `delta` onto whatever we already have.
fn pick_text(text: String, delta: &Option<String>, existing: &[(String, String)], part_id: &str) -> String {
    if !text.is_empty() {
        return text;
    }
    let prior = existing.iter().find(|(pid, _)| pid == part_id).map(|(_, t)| t.clone()).unwrap_or_default();
    match delta {
        Some(d) => format!("{prior}{d}"),
        None => prior,
    }
}

fn message_from_wire(env: &WireMessageEnvelope) -> MsgState {
    let info = &env.info;
    let mut msg = MsgState::new(info.id.clone(), info.role.clone(), info.time.created);
    msg.streaming = info.role == "assistant" && info.time.completed.is_none() && info.error.is_none();
    for part in &env.parts {
        match part {
            WirePart::Text { id, text, synthetic, .. } => {
                if !synthetic {
                    MsgState::upsert_part(&mut msg.text_parts, id, text.clone());
                }
            }
            WirePart::Reasoning { id, text, .. } => {
                MsgState::upsert_part(&mut msg.reasoning_parts, id, text.clone());
            }
            WirePart::Tool { id, tool, state, .. } => {
                let sv = protocol::tool_state_view(state);
                msg.upsert_tool(id, ToolView { name: tool.clone(), status: sv.status, title: sv.title });
            }
            WirePart::Other => {}
        }
    }
    msg
}

fn set_last_pending(model: &mut Model, status: MessageStatus) {
    if let Some(msg) = model.messages.iter_mut().rev().find(|m| m.status == MessageStatus::Pending) {
        msg.status = status;
    }
}

/// Trim whitespace and any trailing `/` so paths concatenate cleanly, and
/// default to an `http://` scheme when the user didn't type one (e.g.
/// "192.168.1.10:4096") — otherwise the shell's URL parser rejects it.
fn normalize_url(url: &str) -> String {
    let trimmed = url.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        String::new()
    } else if trimmed.contains("://") {
        trimmed.to_string()
    } else {
        format!("http://{trimmed}")
    }
}

fn display_title(title: &str) -> String {
    let t = title.trim();
    if t.is_empty() {
        "Untitled".to_string()
    } else {
        t.to_string()
    }
}

/// Reduce an [`HttpResult`] to `Ok(body)` for 2xx, or `Err(message)` otherwise.
fn ok_body(result: HttpResult) -> Result<String, String> {
    match result {
        Ok(mut response) => {
            let status = response.status().as_u16();
            if (200..300).contains(&status) {
                Ok(response.take_body().unwrap_or_default())
            } else {
                Err(format!("Server returned status {status}"))
            }
        }
        Err(e) => Err(format!("Request failed: {e}")),
    }
}

// ─── Tests ───────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn app() -> OpencodeApp {
        OpencodeApp::default()
    }

    /// Put the model into a connected Chat view for the given session.
    fn in_chat(session_id: &str) -> Model {
        let mut model = Model::default();
        model.server_url = "http://localhost:4096".to_string();
        model.connected = true;
        model.screen = Screen::Chat;
        model.current_session_id = Some(session_id.to_string());
        model
    }

    fn feed(app: &OpencodeApp, model: &mut Model, json: serde_json::Value) {
        let _ = app.update(Event::ServerEvent(json.to_string()), model);
    }

    #[test]
    fn server_url_is_kept_raw_while_typing() {
        let app = app();
        let mut model = Model::default();
        // Mid-scheme input must survive verbatim — normalizing per keystroke
        // would eat the "://" the user is typing and make the field unusable.
        let _ = app.update(Event::ServerUrlChanged("http:/".into()), &mut model);
        assert_eq!(app.view(&model).server_url, "http:/");
    }

    #[test]
    fn connect_normalizes_scheme_less_url() {
        let app = app();
        let mut model = Model::default();
        // A scheme-less host (what a user often types) must gain http:// on
        // connect, or the shell's URL parser throws and the attempt crashes.
        let _ = app.update(Event::ServerUrlChanged("192.168.1.10:4096".into()), &mut model);
        assert_eq!(app.view(&model).server_url, "192.168.1.10:4096");
        let _ = app.update(Event::Connect, &mut model);
        assert_eq!(app.view(&model).server_url, "http://192.168.1.10:4096");
    }

    #[test]
    fn connect_trims_and_keeps_explicit_scheme() {
        let app = app();
        let mut model = Model::default();
        let _ = app.update(Event::ServerUrlChanged("  https://host:4096/  ".into()), &mut model);
        let _ = app.update(Event::Connect, &mut model);
        assert_eq!(app.view(&model).server_url, "https://host:4096");
    }

    #[test]
    fn connect_requires_a_url() {
        let app = app();
        let mut model = Model::default();
        let _ = app.update(Event::Connect, &mut model);
        assert_eq!(app.view(&model).error.as_deref(), Some("Enter a server URL"));
    }

    #[test]
    fn sending_adds_optimistic_user_message_and_sets_generating() {
        let app = app();
        let mut model = in_chat("ses_1");
        let _ = app.update(Event::DraftChanged("hello there".into()), &mut model);
        let _ = app.update(Event::SendMessage("hello there".into()), &mut model);

        let view = app.view(&model);
        assert!(view.generating);
        assert!(view.draft.is_empty());
        assert_eq!(view.messages.len(), 1);
        assert_eq!(view.messages[0].role, "user");
        assert_eq!(view.messages[0].text, "hello there");
        assert_eq!(view.messages[0].status, MessageStatus::Pending);
    }

    #[test]
    fn empty_send_is_ignored() {
        let app = app();
        let mut model = in_chat("ses_1");
        let _ = app.update(Event::SendMessage("   ".into()), &mut model);
        assert!(app.view(&model).messages.is_empty());
        assert!(!app.view(&model).generating);
    }

    #[test]
    fn assistant_reply_streams_in_from_sse() {
        let app = app();
        let mut model = in_chat("ses_1");

        // Assistant message shell arrives first.
        feed(&app, &mut model, serde_json::json!({
            "type": "message.updated",
            "properties": { "info": {
                "id": "msg_a", "role": "assistant", "sessionID": "ses_1",
                "time": { "created": 100 }
            }}
        }));
        assert_eq!(app.view(&model).messages.len(), 1);
        assert!(app.view(&model).messages[0].streaming);

        // Two text deltas for the same part accumulate into full text.
        feed(&app, &mut model, serde_json::json!({
            "type": "message.part.updated",
            "properties": { "part": {
                "id": "prt_1", "messageID": "msg_a", "sessionID": "ses_1",
                "type": "text", "text": "Hello"
            }}
        }));
        feed(&app, &mut model, serde_json::json!({
            "type": "message.part.updated",
            "properties": { "part": {
                "id": "prt_1", "messageID": "msg_a", "sessionID": "ses_1",
                "type": "text", "text": "Hello, world"
            }}
        }));

        let view = app.view(&model);
        assert_eq!(view.messages[0].role, "assistant");
        assert_eq!(view.messages[0].text, "Hello, world");
    }

    #[test]
    fn text_delta_accumulates_when_full_text_absent() {
        let app = app();
        let mut model = in_chat("ses_1");
        feed(&app, &mut model, serde_json::json!({
            "type": "message.updated",
            "properties": { "info": { "id": "msg_a", "role": "assistant", "sessionID": "ses_1", "time": {"created": 1}}}
        }));
        for delta in ["Wor", "ld"] {
            feed(&app, &mut model, serde_json::json!({
                "type": "message.part.updated",
                "properties": {
                    "part": { "id": "p", "messageID": "msg_a", "sessionID": "ses_1", "type": "text", "text": "" },
                    "delta": delta
                }
            }));
        }
        assert_eq!(app.view(&model).messages[0].text, "World");
    }

    #[test]
    fn user_message_from_sse_is_not_duplicated() {
        let app = app();
        let mut model = in_chat("ses_1");
        let _ = app.update(Event::SendMessage("hi".into()), &mut model);
        // Server echoes the user message on the stream — must be ignored.
        feed(&app, &mut model, serde_json::json!({
            "type": "message.updated",
            "properties": { "info": { "id": "msg_user", "role": "user", "sessionID": "ses_1", "time": {"created": 5}}}
        }));
        let users = app.view(&model).messages.iter().filter(|m| m.role == "user").count();
        assert_eq!(users, 1);
    }

    #[test]
    fn session_idle_clears_generating() {
        let app = app();
        let mut model = in_chat("ses_1");
        let _ = app.update(Event::SendMessage("hi".into()), &mut model);
        assert!(app.view(&model).generating);
        feed(&app, &mut model, serde_json::json!({
            "type": "session.idle", "properties": { "sessionID": "ses_1" }
        }));
        assert!(!app.view(&model).generating);
    }

    #[test]
    fn idle_for_other_session_is_ignored() {
        let app = app();
        let mut model = in_chat("ses_1");
        let _ = app.update(Event::SendMessage("hi".into()), &mut model);
        feed(&app, &mut model, serde_json::json!({
            "type": "session.idle", "properties": { "sessionID": "ses_other" }
        }));
        assert!(app.view(&model).generating);
    }

    #[test]
    fn reasoning_and_tool_parts_are_surfaced() {
        let app = app();
        let mut model = in_chat("ses_1");
        feed(&app, &mut model, serde_json::json!({
            "type": "message.updated",
            "properties": { "info": { "id": "m", "role": "assistant", "sessionID": "ses_1", "time": {"created": 1}}}
        }));
        feed(&app, &mut model, serde_json::json!({
            "type": "message.part.updated",
            "properties": { "part": { "id": "r", "messageID": "m", "type": "reasoning", "text": "thinking..." }}
        }));
        feed(&app, &mut model, serde_json::json!({
            "type": "message.part.updated",
            "properties": { "part": {
                "id": "t", "messageID": "m", "type": "tool", "tool": "bash",
                "state": { "status": "completed", "title": "ls -la" }
            }}
        }));
        let msg = &app.view(&model).messages[0];
        assert_eq!(msg.reasoning.as_deref(), Some("thinking..."));
        assert_eq!(msg.tools.len(), 1);
        assert_eq!(msg.tools[0].name, "bash");
        assert_eq!(msg.tools[0].status, "completed");
        assert_eq!(msg.tools[0].title.as_deref(), Some("ls -la"));
    }

    #[test]
    fn session_created_event_updates_list() {
        let app = app();
        let mut model = in_chat("ses_1");
        feed(&app, &mut model, serde_json::json!({
            "type": "session.created",
            "properties": { "info": { "id": "ses_new", "title": "Fresh" }}
        }));
        assert!(app.view(&model).sessions.iter().any(|s| s.id == "ses_new" && s.title == "Fresh"));
    }

    #[test]
    fn unknown_event_types_are_ignored() {
        let app = app();
        let mut model = in_chat("ses_1");
        feed(&app, &mut model, serde_json::json!({
            "type": "pty.created", "properties": { "whatever": true }
        }));
        feed(&app, &mut model, serde_json::json!({ "type": "totally.new.event" }));
        // No panic, no state change.
        assert!(app.view(&model).messages.is_empty());
    }

    #[test]
    fn messages_loaded_parses_info_and_parts() {
        let app = app();
        let mut model = in_chat("ses_1");
        let body = serde_json::json!([
            { "info": { "id": "m1", "role": "user", "sessionID": "ses_1", "time": {"created": 1} },
              "parts": [{ "id": "p1", "type": "text", "text": "Question?" }] },
            { "info": { "id": "m2", "role": "assistant", "sessionID": "ses_1", "time": {"created": 2, "completed": 3} },
              "parts": [{ "id": "p2", "type": "text", "text": "Answer." }] }
        ]).to_string();
        let result: HttpResult = Ok(crux_http::testing::ResponseBuilder::ok().body(body).build());
        let _ = app.update(Event::MessagesLoaded("ses_1".into(), result), &mut model);

        let view = app.view(&model);
        assert_eq!(view.messages.len(), 2);
        assert_eq!(view.messages[0].text, "Question?");
        assert_eq!(view.messages[1].text, "Answer.");
        assert!(!view.messages[1].streaming);
        assert!(!view.generating);
    }
}
