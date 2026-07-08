package soy.iko.opencode.shared;


public final class ViewModel {
    public final Screen screen;
    public final String server_url;
    public final String username;
    public final String password;
    public final Boolean auth_required;
    public final Boolean connected;
    public final Boolean loading;
    public final java.util.Optional<String> error;
    public final java.util.List<SessionView> sessions;
    public final java.util.Optional<String> current_session_id;
    public final String current_session_title;
    public final java.util.List<MessageView> messages;
    public final String draft;
    public final Boolean generating;

    public ViewModel(Screen screen, String server_url, String username, String password, Boolean auth_required, Boolean connected, Boolean loading, java.util.Optional<String> error, java.util.List<SessionView> sessions, java.util.Optional<String> current_session_id, String current_session_title, java.util.List<MessageView> messages, String draft, Boolean generating) {
        java.util.Objects.requireNonNull(screen, "screen must not be null");
        java.util.Objects.requireNonNull(server_url, "server_url must not be null");
        java.util.Objects.requireNonNull(username, "username must not be null");
        java.util.Objects.requireNonNull(password, "password must not be null");
        java.util.Objects.requireNonNull(auth_required, "auth_required must not be null");
        java.util.Objects.requireNonNull(connected, "connected must not be null");
        java.util.Objects.requireNonNull(loading, "loading must not be null");
        java.util.Objects.requireNonNull(error, "error must not be null");
        java.util.Objects.requireNonNull(sessions, "sessions must not be null");
        java.util.Objects.requireNonNull(current_session_id, "current_session_id must not be null");
        java.util.Objects.requireNonNull(current_session_title, "current_session_title must not be null");
        java.util.Objects.requireNonNull(messages, "messages must not be null");
        java.util.Objects.requireNonNull(draft, "draft must not be null");
        java.util.Objects.requireNonNull(generating, "generating must not be null");
        this.screen = screen;
        this.server_url = server_url;
        this.username = username;
        this.password = password;
        this.auth_required = auth_required;
        this.connected = connected;
        this.loading = loading;
        this.error = error;
        this.sessions = sessions;
        this.current_session_id = current_session_id;
        this.current_session_title = current_session_title;
        this.messages = messages;
        this.draft = draft;
        this.generating = generating;
    }

    public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        serializer.increase_container_depth();
        screen.serialize(serializer);
        serializer.serialize_str(server_url);
        serializer.serialize_str(username);
        serializer.serialize_str(password);
        serializer.serialize_bool(auth_required);
        serializer.serialize_bool(connected);
        serializer.serialize_bool(loading);
        TraitHelpers.serialize_option_str(error, serializer);
        TraitHelpers.serialize_vector_SessionView(sessions, serializer);
        TraitHelpers.serialize_option_str(current_session_id, serializer);
        serializer.serialize_str(current_session_title);
        TraitHelpers.serialize_vector_MessageView(messages, serializer);
        serializer.serialize_str(draft);
        serializer.serialize_bool(generating);
        serializer.decrease_container_depth();
    }

    public byte[] bincodeSerialize() throws com.novi.serde.SerializationError {
        com.novi.serde.Serializer serializer = new com.novi.bincode.BincodeSerializer();
        serialize(serializer);
        return serializer.get_bytes();
    }

    public static ViewModel deserialize(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        deserializer.increase_container_depth();
        Builder builder = new Builder();
        builder.screen = Screen.deserialize(deserializer);
        builder.server_url = deserializer.deserialize_str();
        builder.username = deserializer.deserialize_str();
        builder.password = deserializer.deserialize_str();
        builder.auth_required = deserializer.deserialize_bool();
        builder.connected = deserializer.deserialize_bool();
        builder.loading = deserializer.deserialize_bool();
        builder.error = TraitHelpers.deserialize_option_str(deserializer);
        builder.sessions = TraitHelpers.deserialize_vector_SessionView(deserializer);
        builder.current_session_id = TraitHelpers.deserialize_option_str(deserializer);
        builder.current_session_title = deserializer.deserialize_str();
        builder.messages = TraitHelpers.deserialize_vector_MessageView(deserializer);
        builder.draft = deserializer.deserialize_str();
        builder.generating = deserializer.deserialize_bool();
        deserializer.decrease_container_depth();
        return builder.build();
    }

    public static ViewModel bincodeDeserialize(byte[] input) throws com.novi.serde.DeserializationError {
        if (input == null) {
             throw new com.novi.serde.DeserializationError("Cannot deserialize null array");
        }
        com.novi.serde.Deserializer deserializer = new com.novi.bincode.BincodeDeserializer(input);
        ViewModel value = deserialize(deserializer);
        if (deserializer.get_buffer_offset() < input.length) {
             throw new com.novi.serde.DeserializationError("Some input bytes were not read");
        }
        return value;
    }

    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        ViewModel other = (ViewModel) obj;
        if (!java.util.Objects.equals(this.screen, other.screen)) { return false; }
        if (!java.util.Objects.equals(this.server_url, other.server_url)) { return false; }
        if (!java.util.Objects.equals(this.username, other.username)) { return false; }
        if (!java.util.Objects.equals(this.password, other.password)) { return false; }
        if (!java.util.Objects.equals(this.auth_required, other.auth_required)) { return false; }
        if (!java.util.Objects.equals(this.connected, other.connected)) { return false; }
        if (!java.util.Objects.equals(this.loading, other.loading)) { return false; }
        if (!java.util.Objects.equals(this.error, other.error)) { return false; }
        if (!java.util.Objects.equals(this.sessions, other.sessions)) { return false; }
        if (!java.util.Objects.equals(this.current_session_id, other.current_session_id)) { return false; }
        if (!java.util.Objects.equals(this.current_session_title, other.current_session_title)) { return false; }
        if (!java.util.Objects.equals(this.messages, other.messages)) { return false; }
        if (!java.util.Objects.equals(this.draft, other.draft)) { return false; }
        if (!java.util.Objects.equals(this.generating, other.generating)) { return false; }
        return true;
    }

    public int hashCode() {
        int value = 7;
        value = 31 * value + (this.screen != null ? this.screen.hashCode() : 0);
        value = 31 * value + (this.server_url != null ? this.server_url.hashCode() : 0);
        value = 31 * value + (this.username != null ? this.username.hashCode() : 0);
        value = 31 * value + (this.password != null ? this.password.hashCode() : 0);
        value = 31 * value + (this.auth_required != null ? this.auth_required.hashCode() : 0);
        value = 31 * value + (this.connected != null ? this.connected.hashCode() : 0);
        value = 31 * value + (this.loading != null ? this.loading.hashCode() : 0);
        value = 31 * value + (this.error != null ? this.error.hashCode() : 0);
        value = 31 * value + (this.sessions != null ? this.sessions.hashCode() : 0);
        value = 31 * value + (this.current_session_id != null ? this.current_session_id.hashCode() : 0);
        value = 31 * value + (this.current_session_title != null ? this.current_session_title.hashCode() : 0);
        value = 31 * value + (this.messages != null ? this.messages.hashCode() : 0);
        value = 31 * value + (this.draft != null ? this.draft.hashCode() : 0);
        value = 31 * value + (this.generating != null ? this.generating.hashCode() : 0);
        return value;
    }

    public static final class Builder {
        public Screen screen;
        public String server_url;
        public String username;
        public String password;
        public Boolean auth_required;
        public Boolean connected;
        public Boolean loading;
        public java.util.Optional<String> error;
        public java.util.List<SessionView> sessions;
        public java.util.Optional<String> current_session_id;
        public String current_session_title;
        public java.util.List<MessageView> messages;
        public String draft;
        public Boolean generating;

        public ViewModel build() {
            return new ViewModel(
                screen,
                server_url,
                username,
                password,
                auth_required,
                connected,
                loading,
                error,
                sessions,
                current_session_id,
                current_session_title,
                messages,
                draft,
                generating
            );
        }
    }
}
