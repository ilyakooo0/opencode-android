package soy.iko.opencode.core

import com.novi.bincode.BincodeDeserializer
import com.novi.bincode.BincodeSerializer
import com.novi.serde.Bytes
import com.novi.serde.DeserializationError
import com.novi.serde.Deserializer
import com.novi.serde.Serializer

fun <T> List<T>.serialize(
    serializer: Serializer,
    serializeElement: Serializer.(T) -> Unit,
) {
    serializer.serialize_len(size.toLong())
    forEach { element ->
        serializer.serializeElement(element)
    }
}

fun <T> Deserializer.deserializeListOf(deserializeElement: (Deserializer) -> T): List<T> {
    val length = deserialize_len()
    val list = mutableListOf<T>()
    repeat(length.toInt()) {
        list.add(deserializeElement(this))
    }
    return list
}

fun <T> T?.serializeOptionOf(
    serializer: Serializer,
    serializeElement: Serializer.(T) -> Unit,
) {
    if (this != null) {
        serializer.serialize_option_tag(true)
        serializer.serializeElement(this)
    } else {
        serializer.serialize_option_tag(false)
    }
}

fun <T> Deserializer.deserializeOptionOf(deserializeElement: (Deserializer) -> T): T? {
    val tag = deserialize_option_tag()
    return if (tag) {
        deserializeElement(this)
    } else {
        null
    }
}

sealed interface Effect {
    fun serialize(serializer: Serializer)

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    data class Render(
        val value: soy.iko.opencode.core.RenderOperation,
    ) : Effect {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(0)
            value.serialize(serializer)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): Render {
                deserializer.increase_container_depth()
                val value = soy.iko.opencode.core.RenderOperation.deserialize(deserializer)
                deserializer.decrease_container_depth()
                return Render(value)
            }
        }
    }

    data class Http(
        val value: soy.iko.opencode.core.HttpRequest,
    ) : Effect {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(1)
            value.serialize(serializer)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): Http {
                deserializer.increase_container_depth()
                val value = soy.iko.opencode.core.HttpRequest.deserialize(deserializer)
                deserializer.decrease_container_depth()
                return Http(value)
            }
        }
    }

    companion object {
        @Throws(DeserializationError::class)
        fun deserialize(deserializer: Deserializer): Effect {
            val index = deserializer.deserialize_variant_index()
            return when (index) {
                0 -> Render.deserialize(deserializer)
                1 -> Http.deserialize(deserializer)
                else -> throw DeserializationError("Unknown variant index for Effect: $index")
            }
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): Effect {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

sealed interface Event {
    fun serialize(serializer: Serializer)

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    data object Start: Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(0)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): Start {
            return Start
        }
    }

    data class ServerUrlChanged(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(1)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): ServerUrlChanged {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return ServerUrlChanged(value)
            }
        }
    }

    data class UsernameChanged(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(2)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): UsernameChanged {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return UsernameChanged(value)
            }
        }
    }

    data class PasswordChanged(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(3)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): PasswordChanged {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return PasswordChanged(value)
            }
        }
    }

    data object Connect: Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(4)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): Connect {
            return Connect
        }
    }

    data object CancelAuth: Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(5)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): CancelAuth {
            return CancelAuth
        }
    }

    data object LoadSessions: Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(6)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): LoadSessions {
            return LoadSessions
        }
    }

    data class SelectSession(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(7)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): SelectSession {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return SelectSession(value)
            }
        }
    }

    data object CreateSession: Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(8)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): CreateSession {
            return CreateSession
        }
    }

    data class LoadMessages(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(9)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): LoadMessages {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return LoadMessages(value)
            }
        }
    }

    data class SendMessage(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(10)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): SendMessage {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return SendMessage(value)
            }
        }
    }

    data class EventReceived(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(11)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): EventReceived {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return EventReceived(value)
            }
        }
    }

    data class NavigateToChat(
        val value: String,
    ) : Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(12)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): NavigateToChat {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return NavigateToChat(value)
            }
        }
    }

    data object NavigateToSessions: Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(13)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): NavigateToSessions {
            return NavigateToSessions
        }
    }

    data object DismissError: Event {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(14)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): DismissError {
            return DismissError
        }
    }

    companion object {
        @Throws(DeserializationError::class)
        fun deserialize(deserializer: Deserializer): Event {
            val index = deserializer.deserialize_variant_index()
            return when (index) {
                0 -> Start.deserialize(deserializer)
                1 -> ServerUrlChanged.deserialize(deserializer)
                2 -> UsernameChanged.deserialize(deserializer)
                3 -> PasswordChanged.deserialize(deserializer)
                4 -> Connect.deserialize(deserializer)
                5 -> CancelAuth.deserialize(deserializer)
                6 -> LoadSessions.deserialize(deserializer)
                7 -> SelectSession.deserialize(deserializer)
                8 -> CreateSession.deserialize(deserializer)
                9 -> LoadMessages.deserialize(deserializer)
                10 -> SendMessage.deserialize(deserializer)
                11 -> EventReceived.deserialize(deserializer)
                12 -> NavigateToChat.deserialize(deserializer)
                13 -> NavigateToSessions.deserialize(deserializer)
                14 -> DismissError.deserialize(deserializer)
                else -> throw DeserializationError("Unknown variant index for Event: $index")
            }
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): Event {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

/// An error produced when an HTTP request fails.
/// 
/// Variants fall into two groups:
/// 
/// **Transport errors** — generated by the shell when it cannot complete the HTTP
/// exchange. These cross the FFI boundary and are serialized in the protocol:
/// [`Url`](HttpError::Url), [`Io`](HttpError::Io), [`Timeout`](HttpError::Timeout).
/// 
/// **Processing errors** — generated on the Rust side after a response arrives.
/// These are never serialized or visible to shells:
/// 
/// - [`Http`](HttpError::Http) — produced by `Response::new()` when the server returns
/// a 4xx or 5xx status. At the *protocol* level these arrive as
/// [`HttpResult::Ok`](crate::protocol::HttpResult::Ok); `Response::new()` converts
/// them here, so app code using `crux_http::Result<Response<T>>` will see them as
/// `Err(HttpError::Http { code, .. })`.
/// - [`Json`](HttpError::Json) — produced when response body deserialisation fails.
sealed interface HttpError {
    fun serialize(serializer: Serializer)

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    /// The request URL could not be parsed.
    data class Url(
        val value: String,
    ) : HttpError {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(0)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): Url {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return Url(value)
            }
        }
    }

    /// An IO error prevented the request from completing.
    data class Io(
        val value: String,
    ) : HttpError {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(1)
            serializer.serialize_str(value)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): Io {
                deserializer.increase_container_depth()
                val value = deserializer.deserialize_str()
                deserializer.decrease_container_depth()
                return Io(value)
            }
        }
    }

    /// The request timed out before a response was received.
    data object Timeout: HttpError {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(2)
            serializer.decrease_container_depth()
        }

        fun deserialize(deserializer: Deserializer): Timeout {
            return Timeout
        }
    }

    companion object {
        @Throws(DeserializationError::class)
        fun deserialize(deserializer: Deserializer): HttpError {
            val index = deserializer.deserialize_variant_index()
            return when (index) {
                0 -> Url.deserialize(deserializer)
                1 -> Io.deserialize(deserializer)
                2 -> Timeout.deserialize(deserializer)
                else -> throw DeserializationError("Unknown variant index for HttpError: $index")
            }
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): HttpError {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

data class HttpHeader(
    val name: String,
    val value: String,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        serializer.serialize_str(name)
        serializer.serialize_str(value)
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): HttpHeader {
            deserializer.increase_container_depth()
            val name = deserializer.deserialize_str()
            val value = deserializer.deserialize_str()
            deserializer.decrease_container_depth()
            return HttpHeader(name, value)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): HttpHeader {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

/// A raw HTTP request, as sent to the shell over the protocol boundary.
/// 
/// # No header validation
/// 
/// All fields are plain strings. Header names and values are carried as-is with no
/// validation against the HTTP specification. This is intentional: `HttpRequest` is a
/// cross-language data carrier deserialised by Swift, Kotlin, and TypeScript shells;
/// Rust's `http`-crate validation rules cannot be enforced on the other side of that
/// boundary.
/// 
/// **Shell authors must not assume that header names or values are well-formed.**
/// Pass them to your underlying HTTP client as-is — it will apply its own rules.
/// 
/// For the ergonomic Rust-side builder that *does* validate header values (and panics
/// on invalid input), see [`crate::command::RequestBuilder`].
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<soy.iko.opencode.core.HttpHeader>,
    val body: Bytes,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        serializer.serialize_str(method)
        serializer.serialize_str(url)
        headers.serialize(serializer) {
            it.serialize(serializer)
        }
        serializer.serialize_bytes(body)
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): HttpRequest {
            deserializer.increase_container_depth()
            val method = deserializer.deserialize_str()
            val url = deserializer.deserialize_str()
            val headers =
                deserializer.deserializeListOf {
                    soy.iko.opencode.core.HttpHeader.deserialize(deserializer)
                }
            val body = deserializer.deserialize_bytes()
            deserializer.decrease_container_depth()
            return HttpRequest(method, url, headers, body)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): HttpRequest {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

data class HttpResponse(
    val status: UShort,
    val headers: List<soy.iko.opencode.core.HttpHeader>,
    val body: Bytes,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        serializer.serialize_u16(status)
        headers.serialize(serializer) {
            it.serialize(serializer)
        }
        serializer.serialize_bytes(body)
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): HttpResponse {
            deserializer.increase_container_depth()
            val status = deserializer.deserialize_u16()
            val headers =
                deserializer.deserializeListOf {
                    soy.iko.opencode.core.HttpHeader.deserialize(deserializer)
                }
            val body = deserializer.deserialize_bytes()
            deserializer.decrease_container_depth()
            return HttpResponse(status, headers, body)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): HttpResponse {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

/// The result of an HTTP request, as returned by the shell over the protocol boundary.
/// 
/// # Status codes are not errors
/// 
/// Any completed HTTP exchange — including responses with 4xx or 5xx status codes — is
/// returned as [`HttpResult::Ok`]. Only *transport-level* failures (the shell could not
/// reach the server at all) produce [`HttpResult::Err`].
/// 
/// To act on an error status, inspect [`HttpResponse::status`]:
/// 
/// ```
/// # use crux_http::protocol::{HttpResult, HttpResponse};
/// # use crux_http::HttpError;
/// # fn handle(result: HttpResult) {
/// match result {
/// HttpResult::Ok(response) if response.status == 200 => { /* success */ }
/// HttpResult::Ok(response) if response.status == 404 => { /* not found */ }
/// HttpResult::Ok(response) if response.status >= 500 => { /* server error */ }
/// HttpResult::Ok(_) => { /* other status */ }
/// HttpResult::Err(e) => { /* transport failure: bad URL, IO error, or timeout */ }
/// }
/// # }
/// ```
sealed interface HttpResult {
    fun serialize(serializer: Serializer)

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    /// The shell completed the HTTP exchange. The response may carry any status code,
    /// including 4xx and 5xx — inspect [`HttpResponse::status`] to distinguish them.
    data class Ok(
        val value: soy.iko.opencode.core.HttpResponse,
    ) : HttpResult {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(0)
            value.serialize(serializer)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): Ok {
                deserializer.increase_container_depth()
                val value = soy.iko.opencode.core.HttpResponse.deserialize(deserializer)
                deserializer.decrease_container_depth()
                return Ok(value)
            }
        }
    }

    /// The shell could not complete the HTTP exchange due to a transport-level failure.
    /// See [`HttpError`] for the possible causes.
    data class Err(
        val value: soy.iko.opencode.core.HttpError,
    ) : HttpResult {
        override fun serialize(serializer: Serializer) {
            serializer.increase_container_depth()
            serializer.serialize_variant_index(1)
            value.serialize(serializer)
            serializer.decrease_container_depth()
        }

        companion object {
            fun deserialize(deserializer: Deserializer): Err {
                deserializer.increase_container_depth()
                val value = soy.iko.opencode.core.HttpError.deserialize(deserializer)
                deserializer.decrease_container_depth()
                return Err(value)
            }
        }
    }

    companion object {
        @Throws(DeserializationError::class)
        fun deserialize(deserializer: Deserializer): HttpResult {
            val index = deserializer.deserialize_variant_index()
            return when (index) {
                0 -> Ok.deserialize(deserializer)
                1 -> Err.deserialize(deserializer)
                else -> throw DeserializationError("Unknown variant index for HttpResult: $index")
            }
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): HttpResult {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

data class MessageView(
    val id: String,
    val role: String,
    val text: String,
    val time: ULong,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        serializer.serialize_str(id)
        serializer.serialize_str(role)
        serializer.serialize_str(text)
        serializer.serialize_u64(time)
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): MessageView {
            deserializer.increase_container_depth()
            val id = deserializer.deserialize_str()
            val role = deserializer.deserialize_str()
            val text = deserializer.deserialize_str()
            val time = deserializer.deserialize_u64()
            deserializer.decrease_container_depth()
            return MessageView(id, role, text, time)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): MessageView {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

/// The single operation `Render` implements.
data object RenderOperation {
    fun serialize(serializer: Serializer) {}

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    fun deserialize(deserializer: Deserializer): RenderOperation {
        return RenderOperation
    }

    @Throws(DeserializationError::class)
    fun bincodeDeserialize(input: ByteArray?): RenderOperation {
        if (input == null) {
            throw DeserializationError("Cannot deserialize null array")
        }
        val deserializer = BincodeDeserializer(input)
        val value = deserialize(deserializer)
        if (deserializer.get_buffer_offset() < input.size) {
            throw DeserializationError("Some input bytes were not read")
        }
        return value
    }
}

/// Request for a side-effect passed from the Core to the Shell.
/// 
/// The `EffectId` links the `Request` with the corresponding call to [`Core::resolve`] to pass the data back
/// to the [`App::update`] function (wrapped in the event provided to the capability originating the effect).
data class Request(
    val id: UInt,
    val effect: soy.iko.opencode.core.Effect,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        serializer.serialize_u32(id)
        effect.serialize(serializer)
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): Request {
            deserializer.increase_container_depth()
            val id = deserializer.deserialize_u32()
            val effect = soy.iko.opencode.core.Effect.deserialize(deserializer)
            deserializer.decrease_container_depth()
            return Request(id, effect)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): Request {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

/// A batch of effect requests from the Core to the Shell, as serialised by
/// [`Bridge::update`] and [`Bridge::resolve`].
/// 
/// The wire format is identical to `Vec<Request<Eff>>` (the newtype is
/// `serde(transparent)`), so existing shell code that already deserialises
/// a `Vec<Request>` remains binary-compatible.
/// 
/// Registering this type with the type-generation system causes the code
/// generators to emit a `Requests` type (with a `value` field containing the
/// list) together with a top-level `bincodeDeserialize` / `BincodeDeserialize`
/// helper, replacing the hand-written extension files that were previously
/// appended by `add_extensions()`.
data class Requests(
    val value: List<soy.iko.opencode.core.Request>,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        value.serialize(serializer) {
            it.serialize(serializer)
        }
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): Requests {
            deserializer.increase_container_depth()
            val value =
                deserializer.deserializeListOf {
                    soy.iko.opencode.core.Request.deserialize(deserializer)
                }
            deserializer.decrease_container_depth()
            return Requests(value)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): Requests {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

enum class Screen {
    CONNECT,
    SESSIONS,
    CHAT;

    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        serializer.serialize_variant_index(ordinal)
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        @Throws(DeserializationError::class)
        fun deserialize(deserializer: Deserializer): Screen {
            deserializer.increase_container_depth()
            val index = deserializer.deserialize_variant_index()
            deserializer.decrease_container_depth()
            return when (index) {
                0 -> CONNECT
                1 -> SESSIONS
                2 -> CHAT
                else -> throw DeserializationError("Unknown variant index for Screen: $index")
            }
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): Screen {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

data class SessionView(
    val id: String,
    val title: String,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        serializer.serialize_str(id)
        serializer.serialize_str(title)
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): SessionView {
            deserializer.increase_container_depth()
            val id = deserializer.deserialize_str()
            val title = deserializer.deserialize_str()
            deserializer.decrease_container_depth()
            return SessionView(id, title)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): SessionView {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}

data class ViewModel(
    val screen: soy.iko.opencode.core.Screen,
    val serverUrl: String,
    val username: String,
    val password: String,
    val authRequired: Boolean,
    val connected: Boolean,
    val loading: Boolean,
    val error: String? = null,
    val sessions: List<soy.iko.opencode.core.SessionView>,
    val currentSessionId: String? = null,
    val messages: List<soy.iko.opencode.core.MessageView>,
    val draftMessage: String,
    val crashLogCount: UInt,
    val latestCrashLog: String? = null,
) {
    fun serialize(serializer: Serializer) {
        serializer.increase_container_depth()
        screen.serialize(serializer)
        serializer.serialize_str(serverUrl)
        serializer.serialize_str(username)
        serializer.serialize_str(password)
        serializer.serialize_bool(authRequired)
        serializer.serialize_bool(connected)
        serializer.serialize_bool(loading)
        error.serializeOptionOf(serializer) {
            serializer.serialize_str(it)
        }
        sessions.serialize(serializer) {
            it.serialize(serializer)
        }
        currentSessionId.serializeOptionOf(serializer) {
            serializer.serialize_str(it)
        }
        messages.serialize(serializer) {
            it.serialize(serializer)
        }
        serializer.serialize_str(draftMessage)
        serializer.serialize_u32(crashLogCount)
        latestCrashLog.serializeOptionOf(serializer) {
            serializer.serialize_str(it)
        }
        serializer.decrease_container_depth()
    }

    fun bincodeSerialize(): ByteArray {
        val serializer = BincodeSerializer()
        serialize(serializer)
        return serializer.get_bytes()
    }

    companion object {
        fun deserialize(deserializer: Deserializer): ViewModel {
            deserializer.increase_container_depth()
            val screen = soy.iko.opencode.core.Screen.deserialize(deserializer)
            val serverUrl = deserializer.deserialize_str()
            val username = deserializer.deserialize_str()
            val password = deserializer.deserialize_str()
            val authRequired = deserializer.deserialize_bool()
            val connected = deserializer.deserialize_bool()
            val loading = deserializer.deserialize_bool()
            val error =
                deserializer.deserializeOptionOf {
                    deserializer.deserialize_str()
                }
            val sessions =
                deserializer.deserializeListOf {
                    soy.iko.opencode.core.SessionView.deserialize(deserializer)
                }
            val currentSessionId =
                deserializer.deserializeOptionOf {
                    deserializer.deserialize_str()
                }
            val messages =
                deserializer.deserializeListOf {
                    soy.iko.opencode.core.MessageView.deserialize(deserializer)
                }
            val draftMessage = deserializer.deserialize_str()
            val crashLogCount = deserializer.deserialize_u32()
            val latestCrashLog =
                deserializer.deserializeOptionOf {
                    deserializer.deserialize_str()
                }
            deserializer.decrease_container_depth()
            return ViewModel(screen, serverUrl, username, password, authRequired, connected, loading, error, sessions, currentSessionId, messages, draftMessage, crashLogCount, latestCrashLog)
        }

        @Throws(DeserializationError::class)
        fun bincodeDeserialize(input: ByteArray?): ViewModel {
            if (input == null) {
                throw DeserializationError("Cannot deserialize null array")
            }
            val deserializer = BincodeDeserializer(input)
            val value = deserialize(deserializer)
            if (deserializer.get_buffer_offset() < input.size) {
                throw DeserializationError("Some input bytes were not read")
            }
            return value
        }
    }
}
