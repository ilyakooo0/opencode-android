package soy.iko.opencode.shared;


public final class SessionView {
    public final String id;
    public final String title;

    public SessionView(String id, String title) {
        java.util.Objects.requireNonNull(id, "id must not be null");
        java.util.Objects.requireNonNull(title, "title must not be null");
        this.id = id;
        this.title = title;
    }

    public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        serializer.increase_container_depth();
        serializer.serialize_str(id);
        serializer.serialize_str(title);
        serializer.decrease_container_depth();
    }

    public byte[] bincodeSerialize() throws com.novi.serde.SerializationError {
        com.novi.serde.Serializer serializer = new com.novi.bincode.BincodeSerializer();
        serialize(serializer);
        return serializer.get_bytes();
    }

    public static SessionView deserialize(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        deserializer.increase_container_depth();
        Builder builder = new Builder();
        builder.id = deserializer.deserialize_str();
        builder.title = deserializer.deserialize_str();
        deserializer.decrease_container_depth();
        return builder.build();
    }

    public static SessionView bincodeDeserialize(byte[] input) throws com.novi.serde.DeserializationError {
        if (input == null) {
             throw new com.novi.serde.DeserializationError("Cannot deserialize null array");
        }
        com.novi.serde.Deserializer deserializer = new com.novi.bincode.BincodeDeserializer(input);
        SessionView value = deserialize(deserializer);
        if (deserializer.get_buffer_offset() < input.length) {
             throw new com.novi.serde.DeserializationError("Some input bytes were not read");
        }
        return value;
    }

    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        SessionView other = (SessionView) obj;
        if (!java.util.Objects.equals(this.id, other.id)) { return false; }
        if (!java.util.Objects.equals(this.title, other.title)) { return false; }
        return true;
    }

    public int hashCode() {
        int value = 7;
        value = 31 * value + (this.id != null ? this.id.hashCode() : 0);
        value = 31 * value + (this.title != null ? this.title.hashCode() : 0);
        return value;
    }

    public static final class Builder {
        public String id;
        public String title;

        public SessionView build() {
            return new SessionView(
                id,
                title
            );
        }
    }
}
