package soy.iko.opencode.shared;

final class TraitHelpers {
    static void serialize_option_str(java.util.Optional<String> value, com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        if (value.isPresent()) {
            serializer.serialize_option_tag(true);
            serializer.serialize_str(value.get());
        } else {
            serializer.serialize_option_tag(false);
        }
    }

    static java.util.Optional<String> deserialize_option_str(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        boolean tag = deserializer.deserialize_option_tag();
        if (!tag) {
            return java.util.Optional.empty();
        } else {
            return java.util.Optional.of(deserializer.deserialize_str());
        }
    }

    static void serialize_vector_MessageView(java.util.List<MessageView> value, com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        serializer.serialize_len(value.size());
        for (MessageView item : value) {
            item.serialize(serializer);
        }
    }

    static java.util.List<MessageView> deserialize_vector_MessageView(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        long length = deserializer.deserialize_len();
        java.util.List<MessageView> obj = new java.util.ArrayList<MessageView>((int) length);
        for (long i = 0; i < length; i++) {
            obj.add(MessageView.deserialize(deserializer));
        }
        return obj;
    }

    static void serialize_vector_SessionView(java.util.List<SessionView> value, com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        serializer.serialize_len(value.size());
        for (SessionView item : value) {
            item.serialize(serializer);
        }
    }

    static java.util.List<SessionView> deserialize_vector_SessionView(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        long length = deserializer.deserialize_len();
        java.util.List<SessionView> obj = new java.util.ArrayList<SessionView>((int) length);
        for (long i = 0; i < length; i++) {
            obj.add(SessionView.deserialize(deserializer));
        }
        return obj;
    }

    static void serialize_vector_ToolView(java.util.List<ToolView> value, com.novi.serde.Serializer serializer) throws com.novi.serde.SerializationError {
        serializer.serialize_len(value.size());
        for (ToolView item : value) {
            item.serialize(serializer);
        }
    }

    static java.util.List<ToolView> deserialize_vector_ToolView(com.novi.serde.Deserializer deserializer) throws com.novi.serde.DeserializationError {
        long length = deserializer.deserialize_len();
        java.util.List<ToolView> obj = new java.util.ArrayList<ToolView>((int) length);
        for (long i = 0; i < length; i++) {
            obj.add(ToolView.deserialize(deserializer));
        }
        return obj;
    }

}

