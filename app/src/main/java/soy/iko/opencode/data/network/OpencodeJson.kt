package soy.iko.opencode.data.network

import soy.iko.opencode.data.model.BusEvent
import soy.iko.opencode.data.model.MessageInfo
import soy.iko.opencode.data.model.Part
import soy.iko.opencode.data.model.ToolState
import soy.iko.opencode.data.model.UnknownEvent
import soy.iko.opencode.data.model.UnknownMessage
import soy.iko.opencode.data.model.UnknownPart
import soy.iko.opencode.data.model.ToolUnknown
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Single shared Json for the whole app.
 *
 * The polymorphic default deserializers are the resilience layer: when the server
 * sends a `type` / `role` / `status` we don't model, decoding falls back to the
 * Unknown variant instead of throwing — so a new opencode release can't break the
 * event stream or message decoding.
 */
@OptIn(ExperimentalSerializationApi::class)
val OpencodeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    classDiscriminator = "type"
    serializersModule = SerializersModule {
        polymorphicDefaultDeserializer(Part::class) { UnknownPart.serializer() }
        polymorphicDefaultDeserializer(BusEvent::class) { UnknownEvent.serializer() }
        polymorphicDefaultDeserializer(MessageInfo::class) { UnknownMessage.serializer() }
        polymorphicDefaultDeserializer(ToolState::class) { ToolUnknown.serializer() }
    }
}
