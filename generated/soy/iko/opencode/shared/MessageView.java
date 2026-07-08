package soy.iko.opencode.shared;


public final class MessageView {
    public final String id;
    public final String role;
    public final String text;
    public final java.util.Optional<String> reasoning;
    public final java.util.List<ToolView> tools;
    public final @com.novi.serde.Unsigned Long time;
    public final MessageStatus status;
    public final Boolean streaming;

    public MessageView(String id, String role, String text, java.util.Optional<String> reasoning, java.util.List<ToolView> tools, @com.novi.serde.Unsigned Long time, MessageStatus status, Boolean streaming) {
        java.util.Objects.requireNonNull(id, "id must not be null");
        java.util.Objects.requireNonNull(role, "role must not be null");
        java.util.Objects.requireNonNull(text, "text must not be null");
        java.util.Objects.requireNonNull(reasoning, "reasoning must not be null");
        java.util.Objects.requireNonNull(tools, "tools must not be null");
        java.util.Objects.requireNonNull(time, "time must not be null");
        java.util.Objects.requireNonNull(status, "status must not be null");
        java.util.Objects.requireNonNull(streaming, "streaming must not be null");
        this.id = id;
        this.role = role;
        this.text = text;
        this.reasoning = reasoning;
        this.tools = tools;
        this.time = time;
        this.status = status;
        this.streaming = streaming;
    }

    public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        serializer.increase_container_depth();
        serializer.serialize_str(id);
        serializer.serialize_str(role);
        serializer.serialize_str(text);
        TraitHelpers.serialize_option_str(reasoning, serializer);
        TraitHelpers.serialize_vector_ToolView(tools, serializer);
        serializer.serialize_u64(time);
        status.serialize(serializer);
        serializer.serialize_bool(streaming);
        serializer.decrease_container_depth();
    }

    public byte[] bincodeSerialize() throws com.novi.serde.SerializationError {
        com.novi.serde.Serializer serializer = new com.novi.bincode.BincodeSerializer();
        serialize(serializer);
        return serializer.get_bytes();
    }

    public static MessageView deserialize(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        deserializer.increase_container_depth();
        Builder builder = new Builder();
        builder.id = deserializer.deserialize_str();
        builder.role = deserializer.deserialize_str();
        builder.text = deserializer.deserialize_str();
        builder.reasoning = TraitHelpers.deserialize_option_str(deserializer);
        builder.tools = TraitHelpers.deserialize_vector_ToolView(deserializer);
        builder.time = deserializer.deserialize_u64();
        builder.status = MessageStatus.deserialize(deserializer);
        builder.streaming = deserializer.deserialize_bool();
        deserializer.decrease_container_depth();
        return builder.build();
    }

    public static MessageView bincodeDeserialize(byte[] input) throws com.novi.serde.DeserializationError {
        if (input == null) {
             throw new com.novi.serde.DeserializationError("Cannot deserialize null array");
        }
        com.novi.serde.Deserializer deserializer = new com.novi.bincode.BincodeDeserializer(input);
        MessageView value = deserialize(deserializer);
        if (deserializer.get_buffer_offset() < input.length) {
             throw new com.novi.serde.DeserializationError("Some input bytes were not read");
        }
        return value;
    }

    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        MessageView other = (MessageView) obj;
        if (!java.util.Objects.equals(this.id, other.id)) { return false; }
        if (!java.util.Objects.equals(this.role, other.role)) { return false; }
        if (!java.util.Objects.equals(this.text, other.text)) { return false; }
        if (!java.util.Objects.equals(this.reasoning, other.reasoning)) { return false; }
        if (!java.util.Objects.equals(this.tools, other.tools)) { return false; }
        if (!java.util.Objects.equals(this.time, other.time)) { return false; }
        if (!java.util.Objects.equals(this.status, other.status)) { return false; }
        if (!java.util.Objects.equals(this.streaming, other.streaming)) { return false; }
        return true;
    }

    public int hashCode() {
        int value = 7;
        value = 31 * value + (this.id != null ? this.id.hashCode() : 0);
        value = 31 * value + (this.role != null ? this.role.hashCode() : 0);
        value = 31 * value + (this.text != null ? this.text.hashCode() : 0);
        value = 31 * value + (this.reasoning != null ? this.reasoning.hashCode() : 0);
        value = 31 * value + (this.tools != null ? this.tools.hashCode() : 0);
        value = 31 * value + (this.time != null ? this.time.hashCode() : 0);
        value = 31 * value + (this.status != null ? this.status.hashCode() : 0);
        value = 31 * value + (this.streaming != null ? this.streaming.hashCode() : 0);
        return value;
    }

    public static final class Builder {
        public String id;
        public String role;
        public String text;
        public java.util.Optional<String> reasoning;
        public java.util.List<ToolView> tools;
        public @com.novi.serde.Unsigned Long time;
        public MessageStatus status;
        public Boolean streaming;

        public MessageView build() {
            return new MessageView(
                id,
                role,
                text,
                reasoning,
                tools,
                time,
                status,
                streaming
            );
        }
    }
}
