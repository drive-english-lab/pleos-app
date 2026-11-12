package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*

/** Mapping port for a verified project schema. Registration/receiver and real media are T021. */
class GleoMediaGateway(private val commands: ListeningCommandPort,
    private val verifiedSchema: Capability = Capability.Unavailable("app_schema_not_verified"),
    private val approvedCommands: Map<String, ListeningMediaCommand> = emptyMap()) {
    suspend fun handle(owner: OwnerContext, command: String): ListeningCommandResult {
        if (verifiedSchema !is Capability.Verified) return ListeningCommandResult.Unavailable("app_schema_not_verified")
        val action = approvedCommands[command] ?: return ListeningCommandResult.Unavailable("unsupported_command")
        return commands.execute(owner,action)
    }
}
