package soy.iko.opencode.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class TimeInfo(
    val created: Long? = null,
    val updated: Long? = null,
    val completed: Long? = null,
)

/** Timing carried by message parts and tool states. The opencode server sends `time: { start,
 *  end?, compacted? }` for these (unlike Session/MessageInfo/Permission, which use the
 *  created/updated/completed shape of [TimeInfo]). With `ignoreUnknownKeys`, modelling these
 *  fields with [TimeInfo] would silently discard start/end/compacted — every part/tool time
 *  would decode to all-nulls — so this separate shape exists for them. */
@Immutable
@Serializable
data class PartTimeInfo(
    val start: Long? = null,
    val end: Long? = null,
    val compacted: Long? = null,
)

@Immutable
@Serializable
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: Cache = Cache(),
) {
    @Serializable
    @Immutable
    data class Cache(
        val read: Long = 0,
        val write: Long = 0,
    )
}
