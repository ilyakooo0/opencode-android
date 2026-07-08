use crux_core::{
    App, Command,
    macros::effect,
    render::{RenderOperation, render},
};
use crux_http::protocol::HttpRequest;
use facet::Facet;
use serde::{Deserialize, Serialize};

// ─── Events ────────────────────────────────────────────────────────────────

#[derive(Facet, Serialize, Deserialize, Clone, Debug)]
#[repr(C)]
pub enum Event {
    // Lifecycle
    Start,
    ServerUrlChanged(String),
    Connect,

    // Session list
    LoadSessions,
    // events local to the core
    #[serde(skip)]
    #[facet(skip)]
    SessionsLoaded(#[facet(opaque)] HttpResult<String>),
    SelectSession(String),
    CreateSession,
    SessionCreated(#[facet(opaque)] HttpResult<String>),

    // Chat
    LoadMessages(String),
    MessagesLoaded(#[facet(opaque)] HttpResult<String>),
    SendMessage(String),
    MessageSent(#[facet(opaque)] HttpResult<String>),

    // SSE event received
    EventReceived(String),

    // Navigation
    NavigateToChat(String),
    NavigateToSessions,

    // Errors
    DismissError,
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
    connected: bool,
    loading: bool,
    error: Option<String>,
    sessions: Vec<SessionSummary>,
    current_session_id: Option<String>,
    messages: Vec<MessageView>,
    draft_message: String,
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
    pub connected: bool,
    pub loading: bool,
    pub error: Option<String>,
    pub sessions: Vec<SessionView>,
    pub current_session_id: Option<String>,
    pub messages: Vec<MessageView>,
    pub draft_message: String,
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

impl App for OpencodeApp {
    type Event = Event;
    type Model = Model;
    type ViewModel = ViewModel;
    type Effect = Effect;

    fn update(&self, event: Event, model: &mut Model) -> Command<Effect, Event> {
        match event {
            Event::Start => {
                model.server_url = "http://localhost:4096".to_string();
                render()
            }

            Event::ServerUrlChanged(url) => {
                model.server_url = url;
                render()
            }

            Event::Connect => {
                model.loading = true;
                model.error = None;
                let url = format!("{}/global/health", model.server_url);
                Http::get(&url).expect_string().build().then_send(|_| Event::LoadSessions)
            }

            Event::LoadSessions => {
                model.loading = true;
                let url = format!("{}/session", model.server_url);
                Http::get(&url).expect_string().build().then_send(Event::SessionsLoaded)
            }

            Event::SessionsLoaded(result) => {
                model.loading = false;
                match result {
                    Ok(mut response) => {
                        let body = response.take_body().unwrap_or_default();
                        model.sessions = parse_sessions(&body);
                        model.connected = true;
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
                Command::event(Event::NavigateToChat(id))
            }

            Event::NavigateToChat(id) => {
                model.current_session_id = Some(id.clone());
                model.messages.clear();
                render().and(Command::event(Event::LoadMessages(id)))
            }

            Event::NavigateToSessions => {
                model.current_session_id = None;
                model.messages.clear();
                render()
            }

            Event::CreateSession => {
                model.loading = true;
                let url = format!("{}/session", model.server_url);
                Http::post(&url).expect_string().build().then_send(Event::SessionCreated)
            }

            Event::SessionCreated(result) => {
                model.loading = false;
                match result {
                    Ok(mut response) => {
                        let body = response.take_body().unwrap_or_default();
                        let id = parse_session_id(&body);
                        Command::event(Event::NavigateToChat(id))
                    }
                    Err(e) => {
                        model.error = Some(format!("Failed to create session: {e}"));
                        render()
                    }
                }
            }

            Event::LoadMessages(session_id) => {
                model.loading = true;
                let url = format!("{}/session/{}/message", model.server_url, session_id);
                Http::get(&url).expect_string().build().then_send(Event::MessagesLoaded)
            }

            Event::MessagesLoaded(result) => {
                model.loading = false;
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
                let session_id = match &model.current_session_id {
                    Some(id) => id.clone(),
                    None => return render(),
                };
                model.draft_message.clear();
                let url = format!(
                    "{}/session/{}/prompt_async",
                    model.server_url, session_id
                );
                let body = format!(
                    r#"{{"sessionID":"{}","parts":[{{"type":"text","text":"{}"}}]}}"#,
                    session_id, text
                );
                Http::post(&url)
                    .header("Content-Type", "application/json")
                    .body_bytes(body.into_bytes())
                    .expect_string()
                    .build()
                    .then_send(Event::MessageSent)
            }

            Event::MessageSent(result) => {
                match result {
                    Ok(_) => {
                        // Reload messages to get the response
                        match &model.current_session_id {
                            Some(id) => Command::event(Event::LoadMessages(id.clone())),
                            None => render(),
                        }
                    }
                    Err(e) => {
                        model.error = Some(format!("Failed to send message: {e}"));
                        render()
                    }
                }
            }

            Event::EventReceived(_data) => {
                // SSE event — reload messages to show updates
                match &model.current_session_id {
                    Some(id) => Command::event(Event::LoadMessages(id.clone())),
                    None => render(),
                }
            }

            Event::DismissError => {
                model.error = None;
                render()
            }
        }
    }

    fn view(&self, model: &Model) -> ViewModel {
        ViewModel {
            screen: if model.current_session_id.is_some() {
                Screen::Chat
            } else if model.connected {
                Screen::Sessions
            } else {
                Screen::Connect
            },
            server_url: model.server_url.clone(),
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
            messages: model.messages.clone(),
            draft_message: model.draft_message.clone(),
        }
    }
}

// ─── JSON parsing helpers (naive — the core stays pure) ────────────────────

fn parse_sessions(json: &str) -> Vec<SessionSummary> {
    let mut sessions = Vec::new();
    let mut depth = 0;
    let mut start = 0;
    let bytes = json.as_bytes();

    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'{' if depth == 0 => {
                depth = 1;
                start = i;
            }
            b'{' => depth += 1,
            b'}' => {
                depth -= 1;
                if depth == 0 {
                    let obj = &json[start..=i];
                    let id = extract_string(obj, "id").unwrap_or_default();
                    let title = extract_string(obj, "title").unwrap_or_default();
                    if !id.is_empty() {
                        sessions.push(SessionSummary { id, title });
                    }
                }
            }
            _ => {}
        }
        i += 1;
    }
    sessions
}

fn parse_session_id(json: &str) -> String {
    extract_string(json, "id").unwrap_or_default()
}

fn parse_messages(json: &str) -> Vec<MessageView> {
    let mut messages = Vec::new();
    let mut depth = 0;
    let mut start = 0;
    let bytes = json.as_bytes();

    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'{' if depth == 0 => {
                depth = 1;
                start = i;
            }
            b'{' => depth += 1,
            b'}' => {
                depth -= 1;
                if depth == 0 {
                    let obj = &json[start..=i];
                    let id = extract_string(obj, "id").unwrap_or_default();
                    let role = extract_string(obj, "role").unwrap_or_default();
                    let time = extract_number(obj, "created").unwrap_or(0);
                    if !id.is_empty() && !role.is_empty() {
                        messages.push(MessageView {
                            id,
                            role,
                            text: String::new(),
                            time,
                        });
                    }
                }
            }
            _ => {}
        }
        i += 1;
    }
    messages
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
    let rest = rest[colon + 1..].trim_start();
    let end = rest
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(rest.len());
    rest[..end].parse().ok()
}

// ─── HTTP type alias for the command builder ───────────────────────────────

use crux_http::command::Http;

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn start_sets_default_server_url() {
        let app = OpencodeApp;
        let mut model = Model::default();
        app.update(Event::Start, &mut model).expect_only_render();
        assert_eq!(model.server_url, "http://localhost:4096");
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
        model.messages.push(MessageView {
            id: "m1".to_string(),
            role: "user".to_string(),
            text: "hello".to_string(),
            time: 0,
        });
        app.update(Event::NavigateToSessions, &mut model);
        assert!(model.current_session_id.is_none());
        assert!(model.messages.is_empty());
    }
}
