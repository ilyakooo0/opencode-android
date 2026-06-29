package soy.iko.opencode.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TimeInfo(
    val created: Long? = null,
    val updated: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: Cache = Cache(),
) {
    @Serializable
    data class Cache(
        val read: Long = 0,
        val write: Long = 0,
    )
}
