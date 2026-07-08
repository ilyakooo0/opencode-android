package soy.iko.opencode.shared;


public abstract class MessageStatus {

    abstract public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError;

    public static MessageStatus deserialize(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        int index = deserializer.deserialize_variant_index();
        switch (index) {
            case 0: return Sent.load(deserializer);
            case 1: return Pending.load(deserializer);
            case 2: return Failed.load(deserializer);
            default: throw new com.novi.serde.DeserializationError("Unknown variant index for MessageStatus: " + index);
        }
    }

    public byte[] bincodeSerialize() throws com.novi.serde.SerializationError {
        com.novi.serde.Serializer serializer = new com.novi.bincode.BincodeSerializer();
        serialize(serializer);
        return serializer.get_bytes();
    }

    public static MessageStatus bincodeDeserialize(byte[] input) throws com.novi.serde.DeserializationError {
        if (input == null) {
             throw new com.novi.serde.DeserializationError("Cannot deserialize null array");
        }
        com.novi.serde.Deserializer deserializer = new com.novi.bincode.BincodeDeserializer(input);
        MessageStatus value = deserialize(deserializer);
        if (deserializer.get_buffer_offset() < input.length) {
             throw new com.novi.serde.DeserializationError("Some input bytes were not read");
        }
        return value;
    }

    public static final class Sent extends MessageStatus {
        public Sent() {
        }

        public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
            serializer.increase_container_depth();
            serializer.serialize_variant_index(0);
            serializer.decrease_container_depth();
        }

        static Sent load(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
            deserializer.increase_container_depth();
            Builder builder = new Builder();
            deserializer.decrease_container_depth();
            return builder.build();
        }

        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            Sent other = (Sent) obj;
            return true;
        }

        public int hashCode() {
            int value = 7;
            return value;
        }

        public static final class Builder {
            public Sent build() {
                return new Sent(
                );
            }
        }
    }

    public static final class Pending extends MessageStatus {
        public Pending() {
        }

        public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
            serializer.increase_container_depth();
            serializer.serialize_variant_index(1);
            serializer.decrease_container_depth();
        }

        static Pending load(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
            deserializer.increase_container_depth();
            Builder builder = new Builder();
            deserializer.decrease_container_depth();
            return builder.build();
        }

        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            Pending other = (Pending) obj;
            return true;
        }

        public int hashCode() {
            int value = 7;
            return value;
        }

        public static final class Builder {
            public Pending build() {
                return new Pending(
                );
            }
        }
    }

    public static final class Failed extends MessageStatus {
        public Failed() {
        }

        public void serialize(com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
            serializer.increase_container_depth();
            serializer.serialize_variant_index(2);
            serializer.decrease_container_depth();
        }

        static Failed load(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
            deserializer.increase_container_depth();
            Builder builder = new Builder();
            deserializer.decrease_container_depth();
            return builder.build();
        }

        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            Failed other = (Failed) obj;
            return true;
        }

        public int hashCode() {
            int value = 7;
            return value;
        }

        public static final class Builder {
            public Failed build() {
                return new Failed(
                );
            }
        }
    }
}

