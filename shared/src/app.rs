use base64::Engine;
use crux_core::{
    App, Command,
    macros::effect,
    render::{RenderOperation, render},
};
use crux_http::command::{Http, RequestBuilder};
use crux_http::protocol::HttpRequest;
use facet::Facet;
use serde::{Deserialize, Serialize};

// ─── Events ────────────────────────────────────────────────────────────────

#[derive(Facet, Serialize, Deserialize, Clone, Debug)]
#[repr(C)]
pub enum Event {
    // ── Type-generated events (sent from Shell → Core).
    //    These MUST be contiguous starting at index 0 to match the
    //    generated Kotlin/Swift/TS enum indices. Do not interleave
    //    skipped variants here. ──────────────────────────────────────

    // Lifecycle
    Start,
    ServerUrlChanged(String),
    UsernameChanged(String),
    PasswordChanged(String),
    Connect,
    CancelAuth,

    // Session list
    LoadSessions,
    SelectSession(String),
    CreateSession,

    // Chat
    LoadMessages(String),
    SendMessage(String),

    // SSE event received
    EventReceived(String),

    // Navigation
    NavigateToChat(String),
    NavigateToSessions,
    NavigateToConnect,

    // Errors
    DismissError,

    // ── Internal events (skipped from typegen, sent only via then_send).
    //    These occupy higher discriminant indices that are never
    //    produced by the shell, so the index gap is harmless. ─────────

    #[serde(skip)]
    #[facet(skip)]
    SessionsLoaded(#[facet(opaque)] HttpResult<String>),
    #[serde(skip)]
    #[facet(skip)]
    SessionCreated(#[facet(opaque)] HttpResult<String>),
    #[serde(skip)]
    #[facet(skip)]
    MessagesLoaded(#[facet(opaque)] HttpResult<String>),
    #[serde(skip)]
    #[facet(skip)]
    MessageSent(#[facet(opaque)] HttpResult<String>),
    #[serde(skip)]
    #[facet(skip)]
    CrashLog(String),
    #[serde(skip)]
    #[facet(skip)]
    HealthProbed(#[facet(opaque)] HttpResult<String>),
}

// ─── Effects ───────────────────────────────────────────────────────────────

#[effect(facet_typegen)]
#[derive(Debug)]
pub enum Effect {
    Render(RenderOperation),
    Http(HttpRequest),
}

// ─── HTTP result wrapper (opaque, not type-generated) ──────────────────────

pub type HttpResult<T> = Result<crux_http::Response<T>, crux_http::HttpError>;

// ─── Model ─────────────────────────────────────────────────────────────────

#[derive(Default)]
pub struct Model {
    server_url: String,
    username: String,
    password: String,
    /// Set once the server replies 401 to an unauthenticated probe.
    /// The view surfaces this so the shell can show credential fields.
    auth_required: bool,
    /// Set once a request with credentials succeeds. All subsequent
    /// requests carry the Authorization header.
    authed: bool,
    connected: bool,
    loading: bool,
    error: Option<String>,
    sessions: Vec<SessionSummary>,
    current_session_id: Option<String>,
    current_session_title: String,
    messages: Vec<MessageView>,
    draft_message: String,
    /// True between sending a message and the assistant reply arriving.
    generating: bool,
    /// Accumulated crash logs (sent from the shell's uncaught-exception handler)
    crash_logs: Vec<String>,
}

#[derive(Clone, Debug, Default)]
pub struct SessionSummary {
    pub id: String,
    pub title: String,
}

#[derive(Facet, Serialize, Deserialize, Clone, Debug, Default)]
pub struct MessageView {
    pub id: String,
    pub role: String,
    pub text: String,
    pub time: u64,
}

// ─── ViewModel ─────────────────────────────────────────────────────────────

#[derive(Facet, Serialize, Deserialize, Clone, Debug, Default)]
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
    pub draft_message: String,
    /// True while waiting for the assistant to reply after the user sends.
    pub generating: bool,
    pub crash_log_count: u32,
    pub latest_crash_log: Option<String>,
}

#[derive(Facet, Serialize, Deserialize, Clone, Debug, Default, PartialEq, Eq)]
#[repr(C)]
pub enum Screen {
    #[default]
    Connect,
    Sessions,
    Chat,
}

#[derive(Facet, Serialize, Deserialize, Clone, Debug, Default)]
pub struct SessionView {
    pub id: String,
    pub title: String,
}

// ─── App ───────────────────────────────────────────────────────────────────

#[derive(Default)]
pub struct OpencodeApp;

impl OpencodeApp {
    /// Build a GET request to `path`, adding the Basic-Auth header when the
    /// model has credentials. The header is always added once `authed` is true
    /// (i.e. after the server has accepted credentials), and also during the
    /// credential-retry attempt itself.
    fn get(&self, model: &Model, path: &str) -> RequestBuilder<Effect, Event, String> {
        let url = format!("{}{path}", model.server_url);
        let req = Http::get(&url).expect_string();
        self.with_auth(req, model)
    }

    /// Build a POST request to `path` with the given body bytes.
    fn post(&self, model: &Model, path: &str, body: Vec<u8>) -> RequestBuilder<Effect, Event, String> {
        let url = format!("{}{path}", model.server_url);
        let req = Http::post(&url).expect_string().body_bytes(body);
        self.with_auth(req, model)
    }

    /// Attach the Basic-Auth `Authorization` header if the model carries
    /// credentials. Uses RFC 7617 `Basic` scheme with base64-encoded
    /// `username:password`.
    fn with_auth<E>(
        &self,
        req: RequestBuilder<Effect, Event, E>,
        model: &Model,
    ) -> RequestBuilder<Effect, Event, E>
    where
        E: 'static,
    {
        if !model.username.is_empty() || !model.password.is_empty() {
            let creds = format!("{}:{}", model.username, model.password);
            let encoded = base64::engine::general_purpose::STANDARD.encode(creds.as_bytes());
            req.header("Authorization", format!("Basic {encoded}"))
        } else {
            req
        }
    }

    /// Render helper that also stamps the crash-log summary into the view.
    fn render_with_crashes(&self, _model: &Model) -> Command<Effect, Event> {
        // render() is the base; the view() function reads the model directly
        // for crash-log fields, so a plain render suffices.
        render()
    }
}

impl App for OpencodeApp {
    type Event = Event;
    type Model = Model;
    type ViewModel = ViewModel;
    type Effect = Effect;

    fn update(&self, event: Event, model: &mut Model) -> Command<Effect, Event> {
        match event {
            Event::Start => {
                // Don't reset server_url here — the shell restores it from
                // persisted storage via ServerUrlChanged before any connect.
                render()
            }

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
                model.loading = true;
                model.error = None;
                // Probe health. `with_auth` attaches credentials if present,
                // so a retry after a 401 carries the Authorization header.
                let req = self.get(model, "/global/health");
                req.build().then_send(|result| Event::HealthProbed(result))
            }

            Event::CancelAuth => {
                model.auth_required = false;
                model.username.clear();
                model.password.clear();
                model.loading = false;
                render()
            }

            Event::LoadSessions => {
                model.loading = true;
                self.get(model, "/session").build().then_send(Event::SessionsLoaded)
            }

            Event::SessionsLoaded(result) => {
                model.loading = false;
                match result {
                    Ok(mut response) => {
                        let body = response.take_body().unwrap_or_default();
                        model.sessions = parse_sessions(&body);
                        model.connected = true;
                        // Refresh the current session's title in case it was
                        // populated after creation.
                        if let Some(id) = model.current_session_id.clone() {
                            if let Some(s) = model.sessions.iter().find(|s| s.id == id) {
                                model.current_session_title = s.title.clone();
                            }
                        }
                        render()
                    }
                    Err(e) => {
                        model.error = Some(format!("Failed to load sessions: {e}"));
                        render()
                    }
                }
            }

            Event::SelectSession(id) => {
                model.current_session_id = Some(id.clone());
                model.current_session_title = model
                    .sessions
                    .iter()
                    .find(|s| s.id == id)
                    .map(|s| s.title.clone())
                    .unwrap_or_default();
                Command::event(Event::NavigateToChat(id))
            }

            Event::NavigateToChat(id) => {
                model.current_session_id = Some(id.clone());
                model.current_session_title = model
                    .sessions
                    .iter()
                    .find(|s| s.id == id)
                    .map(|s| s.title.clone())
                    .unwrap_or_default();
                model.messages.clear();
                render().and(Command::event(Event::LoadMessages(id)))
            }

            Event::NavigateToSessions => {
                model.current_session_id = None;
                model.current_session_title.clear();
                model.messages.clear();
                model.generating = false;
                render()
            }

            Event::NavigateToConnect => {
                // Return to the connect screen to switch servers / credentials.
                // Keep the sessions list so a re-connect can refresh in place.
                model.current_session_id = None;
                model.current_session_title.clear();
                model.messages.clear();
                model.generating = false;
                model.connected = false;
                model.auth_required = false;
                model.loading = false;
                render()
            }

            Event::CreateSession => {
                model.loading = true;
                self.post(model, "/session", Vec::new())
                    .build()
                    .then_send(Event::SessionCreated)
            }

            Event::SessionCreated(result) => {
                model.loading = false;
                match result {
                    Ok(mut response) => {
                        let body = response.take_body().unwrap_or_default();
                        let id = parse_session_id(&body);
                        // Some servers echo the title in the created payload;
                        // fall back to "New session" if absent.
                        let title = extract_string(&body, "title")
                            .filter(|t| !t.is_empty())
                            .unwrap_or_else(|| "New session".to_string());
                        model.current_session_title = title.clone();
                        model.sessions.insert(
                            0,
                            SessionSummary { id: id.clone(), title },
                        );
                        Command::event(Event::NavigateToChat(id))
                    }
                    Err(e) => {
                        model.error = Some(format!("Failed to create session: {e}"));
                        render()
                    }
                }
            }

            Event::LoadMessages(id) => {
                model.loading = true;
                let path = format!("/session/{id}/message");
                self.get(model, &path).build().then_send(Event::MessagesLoaded)
            }

            Event::MessagesLoaded(result) => {
                model.loading = false;
                model.generating = false;
                match result {
                    Ok(mut response) => {
                        let body = response.take_body().unwrap_or_default();
                        model.messages = parse_messages(&body);
                        render()
                    }
                    Err(e) => {
                        model.error = Some(format!("Failed to load messages: {e}"));
                        render()
                    }
                }
            }

            Event::SendMessage(text) => {
                let Some(session_id) = model.current_session_id.clone() else {
                    return render();
                };
                model.draft_message.clear();
                model.generating = true;
                let body = serde_json::json!({
                    "sessionID": session_id,
                    "parts": [{ "type": "text", "text": text }]
                })
                .to_string()
                .into_bytes();
                let path = format!("/session/{session_id}/prompt_async");
                self.post(model, &path, body)
                    .build()
                    .then_send(Event::MessageSent)
            }

            Event::MessageSent(result) => {
                match result {
                    Ok(_) => {
                        // Reload messages to pick up the assistant reply.
                        if let Some(session_id) = model.current_session_id.clone() {
                            Command::event(Event::LoadMessages(session_id))
                        } else {
                            model.generating = false;
                            render()
                        }
                    }
                    Err(e) => {
                        model.generating = false;
                        model.error = Some(format!("Failed to send message: {e}"));
                        render()
                    }
                }
            }

            Event::EventReceived(_data) => {
                // SSE event — reload messages for the current session.
                if let Some(session_id) = model.current_session_id.clone() {
                    Command::event(Event::LoadMessages(session_id))
                } else {
                    render()
                }
            }

            Event::CrashLog(entry) => {
                model.crash_logs.push(entry);
                self.render_with_crashes(model)
            }

            Event::DismissError => {
                model.error = None;
                render()
            }

            // ── Internal events (not type-generated, sent via then_send) ───
            Event::HealthProbed(result) => handle_health_probed(self, model, result),
        }
    }

    fn view(&self, model: &Self::Model) -> Self::ViewModel {
        let screen = if model.current_session_id.is_some() {
            Screen::Chat
        } else if model.connected {
            Screen::Sessions
        } else {
            Screen::Connect
        };

        ViewModel {
            screen,
            server_url: model.server_url.clone(),
            username: model.username.clone(),
            password: model.password.clone(),
            auth_required: model.auth_required,
            connected: model.connected,
            loading: model.loading,
            error: model.error.clone(),
            sessions: model
                .sessions
                .iter()
                .map(|s| SessionView {
                    id: s.id.clone(),
                    title: s.title.clone(),
                })
                .collect(),
            current_session_id: model.current_session_id.clone(),
            current_session_title: model.current_session_title.clone(),
            messages: model.messages.clone(),
            draft_message: model.draft_message.clone(),
            generating: model.generating,
            crash_log_count: model.crash_logs.len() as u32,
            latest_crash_log: model.crash_logs.last().cloned(),
        }
    }
}

// ─── Basic-auth detection ──────────────────────────────────────────────────
//
// The opencode server uses HTTP Basic Auth when OPENCODE_SERVER_PASSWORD is set
// (username defaults to "opencode"). We detect this by probing /global/health
// without credentials:
//
//   - 200  → server is open; proceed to load sessions.
//   - 401  → server is behind basic auth; surface `auth_required` and wait for
//            the user to supply credentials. The user then re-issues Connect,
//            which now attaches the Authorization header to the same probe.
//            A 200 on that retry flips `authed` and proceeds.
//   - Other non-2xx → surface as a connection error.
//   - Transport error → surface as a connection error.

fn handle_health_probed(
    app: &OpencodeApp,
    model: &mut Model,
    result: HttpResult<String>,
) -> Command<Effect, Event> {
    let has_creds = !model.username.is_empty() || !model.password.is_empty();
    match result {
        Ok(response) => {
            let status = response.status().as_u16();
            if status == 401 {                model.loading = false;
                if has_creds {
                    // We sent credentials but the server still rejected them.
                    model.error = Some("Invalid credentials".to_string());
                } else {
                    // No credentials were sent — server is behind basic auth.
                    // Surface the credential fields and wait for the user.
                    model.auth_required = true;
                }
                return render();
            }
            if (200..300).contains(&status) {
                if has_creds {
                    model.authed = true;
                }
                model.loading = true;
                return app
                    .get(model, "/session")
                    .build()
                    .then_send(Event::SessionsLoaded);
            }
            // Other status codes — treat as connection error.
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

// ─── JSON parsing (minimal, avoids pulling a JSON dep into the core) ───────

fn parse_sessions(body: &str) -> Vec<SessionSummary> {
    let mut sessions = Vec::new();
    let mut depth = 0i32;
    let mut start = 0usize;
    for (i, ch) in body.char_indices() {
        match ch {
            '{' => {
                if depth == 0 {
                    start = i;
                }
                depth += 1;
            }
            '}' => {
                depth -= 1;
                if depth == 0 {
                    let obj = &body[start..=i];
                    let id = extract_string(obj, "id").unwrap_or_default();
                    let title = extract_string(obj, "title").unwrap_or_default();
                    if !id.is_empty() {
                        sessions.push(SessionSummary {
                            id,
                            title: if title.is_empty() {
                                "Untitled".to_string()
                            } else {
                                title
                            },
                        });
                    }
                }
            }
            _ => {}
        }
    }
    sessions
}

fn parse_session_id(body: &str) -> String {
    extract_string(body, "id").unwrap_or_default()
}

fn parse_messages(body: &str) -> Vec<MessageView> {
    let mut messages = Vec::new();
    let mut depth = 0i32;
    let mut start = 0usize;
    let mut i = 0usize;
    let bytes = body.as_bytes();
    while i < body.len() {
        let ch = bytes[i] as char;
        match ch {
            '{' => {
                if depth == 0 {
                    start = i;
                }
                depth += 1;
            }
            '}' => {
                depth -= 1;
                if depth == 0 {
                    let obj = &body[start..=i];
                    let id = extract_string(obj, "id").unwrap_or_default();
                    let role = extract_string(obj, "role").unwrap_or_default();
                    let time = extract_number(obj, "created").unwrap_or(0);
                    if !id.is_empty() {
                        let text = extract_message_text(obj);
                        messages.push(MessageView { id, role, text, time });
                    }
                }
            }
            _ => {}
        }
        i += 1;
    }
    messages
}

/// Extract the text from a message part. opencode messages nest the user/assistant
/// text inside a `parts` array; we do a shallow scan for `"text":"..."` after the
/// first `"parts"` key.
fn extract_message_text(obj: &str) -> String {
    if let Some(parts_idx) = obj.find("\"parts\"") {
        let rest = &obj[parts_idx..];
        if let Some(text) = extract_string(rest, "text") {
            return text;
        }
    }
    extract_string(obj, "text").unwrap_or_default()
}

fn extract_string(json: &str, key: &str) -> Option<String> {
    let pattern = format!("\"{key}\"");
    let idx = json.find(&pattern)?;
    let rest = &json[idx + pattern.len()..];
    let colon = rest.find(':')?;
    let rest = &rest[colon + 1..];
    let quote = rest.find('"')?;
    let rest = &rest[quote + 1..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

fn extract_number(json: &str, key: &str) -> Option<u64> {
    let pattern = format!("\"{key}\"");
    let idx = json.find(&pattern)?;
    let rest = &json[idx + pattern.len()..];
    let colon = rest.find(':')?;
    let rest = &rest[colon + 1..].trim_start();
    let end = rest
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(rest.len());
    rest[..end].parse().ok()
}

// ─── Tests ─────────────────────────────────────────────────────────────────

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn start_does_not_reset_server_url() {
        // Start no longer overwrites server_url — the shell restores the
        // persisted value via ServerUrlChanged so the user's last server
        // survives a relaunch.
        let app = OpencodeApp;
        let mut model = Model::default();
        model.server_url = "http://10.0.0.1:4096".to_string();
        app.update(Event::Start, &mut model).expect_only_render();
        assert_eq!(model.server_url, "http://10.0.0.1:4096");
    }

    #[test]
    fn server_url_change_updates_model() {
        let app = OpencodeApp;
        let mut model = Model::default();
        app.update(Event::Start, &mut model);
        app.update(
            Event::ServerUrlChanged("http://10.0.0.1:4096".to_string()),
            &mut model,
        )
        .expect_only_render();
        assert_eq!(model.server_url, "http://10.0.0.1:4096");
    }

    #[test]
    fn connect_emits_http_effect() {
        let app = OpencodeApp;
        let mut model = Model::default();
        model.server_url = "http://localhost:4096".to_string();
        app.update(Event::Start, &mut model);
        let mut cmd = app.update(Event::Connect, &mut model);
        assert!(cmd.effects().any(|e| matches!(e, Effect::Http(_))));
        assert!(model.loading);
    }

    #[test]
    fn parse_sessions_extracts_id_and_title() {
        let json = r#"[{"id":"abc123","title":"My Session"},{"id":"def456","title":"Test"}]"#;
        let sessions = parse_sessions(json);
        assert_eq!(sessions.len(), 2);
        assert_eq!(sessions[0].id, "abc123");
        assert_eq!(sessions[0].title, "My Session");
        assert_eq!(sessions[1].id, "def456");
    }

    #[test]
    fn parse_session_id_extracts_id() {
        let json = r#"{"id":"xyz789","title":"New"}"#;
        let id = parse_session_id(json);
        assert_eq!(id, "xyz789");
    }

    #[test]
    fn extract_string_finds_nested_keys() {
        let json = r#"{"a":"b","id":"target","c":"d"}"#;
        assert_eq!(extract_string(json, "id"), Some("target".to_string()));
        assert_eq!(extract_string(json, "missing"), None);
    }

    #[test]
    fn extract_number_parses_values() {
        let json = r#"{"created":1234567890}"#;
        assert_eq!(extract_number(json, "created"), Some(1234567890));
    }

    #[test]
    fn navigate_to_sessions_clears_state() {
        let app = OpencodeApp;
        let mut model = Model::default();
        model.current_session_id = Some("test".to_string());
        model.current_session_title = "Test".to_string();
        model.generating = true;
        model.messages.push(MessageView {
            id: "m1".to_string(),
            role: "user".to_string(),
            text: "hello".to_string(),
            time: 0,
        });
        app.update(Event::NavigateToSessions, &mut model);
        assert!(model.current_session_id.is_none());
        assert!(model.current_session_title.is_empty());
        assert!(model.messages.is_empty());
        assert!(!model.generating);
    }

    #[test]
    fn select_session_records_title_from_sessions_list() {
        let app = OpencodeApp;
        let mut model = Model::default();
        model.sessions = vec![
            SessionSummary { id: "s1".to_string(), title: "First".to_string() },
            SessionSummary { id: "s2".to_string(), title: "Second".to_string() },
        ];
        app.update(Event::SelectSession("s2".to_string()), &mut model);
        assert_eq!(model.current_session_id.as_deref(), Some("s2"));
        assert_eq!(model.current_session_title, "Second");
        let view = app.view(&model);
        assert_eq!(view.current_session_title, "Second");
    }

    #[test]
    fn send_message_sets_generating_until_messages_loaded() {
        let app = OpencodeApp;
        let mut model = Model::default();
        model.server_url = "http://localhost:4096".to_string();
        model.current_session_id = Some("s1".to_string());
        app.update(Event::SendMessage("hello".to_string()), &mut model);
        assert!(model.generating);
        // Simulate the MessagesLoaded internal event clearing it.
        let ok_resp = crux_http::testing::ResponseBuilder::ok().body("[]".to_string()).build();
        let result: HttpResult<String> = Ok(ok_resp);
        app.update(Event::MessagesLoaded(result), &mut model);
        assert!(!model.generating);
    }

    #[test]
    fn failed_send_clears_generating() {
        let app = OpencodeApp;
        let mut model = Model::default();
        model.server_url = "http://localhost:4096".to_string();
        model.current_session_id = Some("s1".to_string());
        app.update(Event::SendMessage("hello".to_string()), &mut model);
        assert!(model.generating);
        let err = crux_http::HttpError::Io("boom".to_string());
        app.update(Event::MessageSent(Err(err)), &mut model);
        assert!(!model.generating);
        assert!(model.error.is_some());
    }

    #[test]
    fn auth_header_built_from_credentials() {
        let app = OpencodeApp;
        let mut model = Model::default();
        model.server_url = "http://localhost:4096".to_string();
        model.username = "opencode".to_string();
        model.password = "secret".to_string();
        // Verify the base64 encoding matches RFC 7617.
        let creds = format!("{}:{}", model.username, model.password);
        let encoded = base64::engine::general_purpose::STANDARD.encode(creds.as_bytes());
        assert_eq!(encoded, "b3BlbmNvZGU6c2VjcmV0");
        // Building a request should not panic.
        let _ = app.get(&model, "/global/health");
    }

    #[test]
    fn crash_log_event_accumulates() {
        let app = OpencodeApp;
        let mut model = Model::default();
        app.update(Event::CrashLog("boom".to_string()), &mut model);
        assert_eq!(model.crash_logs.len(), 1);
        let view = app.view(&model);
        assert_eq!(view.crash_log_count, 1);
        assert_eq!(view.latest_crash_log.as_deref(), Some("boom"));
    }

    /// Verify that the bincode discriminant indices the shell sends match
    /// the Rust enum variant positions — the type generator skips
    /// `#[facet(skip)]` variants and renumbers the remaining ones, so the
    /// shell's index 7 (SelectSession) must land on Rust's SelectSession,
    /// not on a skipped variant.
    #[test]
    fn event_discriminants_match_generated_indices() {
        // The generated Kotlin Event enum (see generated/.../Core.kt) maps:
        //   0=Start, 1=ServerUrlChanged, 2=UsernameChanged, 3=PasswordChanged,
        //   4=Connect, 5=CancelAuth, 6=LoadSessions, 7=SelectSession,
        //   8=CreateSession, 9=LoadMessages, 10=SendMessage, 11=EventReceived,
        //   12=NavigateToChat, 13=NavigateToSessions, 14=NavigateToConnect,
        //   15=DismissError
        //
        // Serialize each Rust variant and check the leading u32 index.
        let cases: &[(Event, u32)] = &[
            (Event::Start, 0),
            (Event::ServerUrlChanged(String::new()), 1),
            (Event::UsernameChanged(String::new()), 2),
            (Event::PasswordChanged(String::new()), 3),
            (Event::Connect, 4),
            (Event::CancelAuth, 5),
            (Event::LoadSessions, 6),
            (Event::SelectSession(String::new()), 7),
            (Event::CreateSession, 8),
            (Event::LoadMessages(String::new()), 9),
            (Event::SendMessage(String::new()), 10),
            (Event::EventReceived(String::new()), 11),
            (Event::NavigateToChat(String::new()), 12),
            (Event::NavigateToSessions, 13),
            (Event::NavigateToConnect, 14),
            (Event::DismissError, 15),
        ];
        for (event, expected) in cases {
            let bytes = bincode::serialize(event).unwrap();
            let idx = u32::from_le_bytes(bytes[0..4].try_into().unwrap());
            assert_eq!(
                idx, *expected,
                "Event discriminant mismatch for {event:?}: got {idx}, expected {expected}"
            );
        }
    }
}
