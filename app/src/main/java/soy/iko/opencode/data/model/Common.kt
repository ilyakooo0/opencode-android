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
