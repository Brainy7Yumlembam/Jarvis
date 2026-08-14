package co.aura.actions

import kotlinx.serialization.Serializable

@Serializable
sealed interface ActionResult {
    val message: String

    @Serializable
    data class Success(override val message: String) : ActionResult

    @Serializable
    data class Failure(override val message: String) : ActionResult

    @Serializable
    data class Ambiguity(
        override val message: String,
        val candidates: List<InstalledApp>
    ) : ActionResult
}
