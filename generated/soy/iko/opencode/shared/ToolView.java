package soy.iko.opencode.shared;


public final class ToolView {
    public final String name;
    public final String status;
    public final java.util.Optional<String> title;

    public ToolView(String name, String status, java.util.Optional<String> title) {
        java.util.Objects.requireNonNull(name, "name must not be null");
        java.util.Objects.requireNonNull(status, "status must not be null");
        java.util.Objects.requireNonNull(title, "title must not be null");
        this.name = name;
        this.status = status;
        this.title = title;
    }

    public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        serializer.increase_container_depth();
        serializer.serialize_str(name);
        serializer.serialize_str(status);
        TraitHelpers.serialize_option_str(title, serializer);
        serializer.decrease_container_depth();
    }

    public byte[] bincodeSerialize() throws com.novi.serde.SerializationError {
        com.novi.serde.Serializer serializer = new com.novi.bincode.BincodeSerializer();
        serialize(serializer);
        return serializer.get_bytes();
    }

    public static ToolView deserialize(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        deserializer.increase_container_depth();
        Builder builder = new Builder();
        builder.name = deserializer.deserialize_str();
        builder.status = deserializer.deserialize_str();
        builder.title = TraitHelpers.deserialize_option_str(deserializer);
        deserializer.decrease_container_depth();
        return builder.build();
    }

    public static ToolView bincodeDeserialize(byte[] input) throws com.novi.serde.DeserializationError {
        if (input == null) {
             throw new com.novi.serde.DeserializationError("Cannot deserialize null array");
        }
        com.novi.serde.Deserializer deserializer = new com.novi.bincode.BincodeDeserializer(input);
        ToolView value = deserialize(deserializer);
        if (deserializer.get_buffer_offset() < input.length) {
             throw new com.novi.serde.DeserializationError("Some input bytes were not read");
        }
        return value;
    }

    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        ToolView other = (ToolView) obj;
        if (!java.util.Objects.equals(this.name, other.name)) { return false; }
        if (!java.util.Objects.equals(this.status, other.status)) { return false; }
        if (!java.util.Objects.equals(this.title, other.title)) { return false; }
        return true;
    }

    public int hashCode() {
        int value = 7;
        value = 31 * value + (this.name != null ? this.name.hashCode() : 0);
        value = 31 * value + (this.status != null ? this.status.hashCode() : 0);
        value = 31 * value + (this.title != null ? this.title.hashCode() : 0);
        return value;
    }

    public static final class Builder {
        public String name;
        public String status;
        public java.util.Optional<String> title;

        public ToolView build() {
            return new ToolView(
                name,
                status,
                title
            );
        }
    }
}
