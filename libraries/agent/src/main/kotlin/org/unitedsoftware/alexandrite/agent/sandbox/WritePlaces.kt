package org.unitedsoftware.alexandrite.agent.sandbox

import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.config.SandboxConfig
import org.unitedsoftware.alexandrite.agent.config.configuredPath
import org.unitedsoftware.alexandrite.agent.directory.ConversationDirectories
import org.unitedsoftware.alexandrite.agent.permission.EditPlaces
import org.unitedsoftware.alexandrite.agent.trust.FolderTrust
import org.unitedsoftware.alexandrite.agent.workspace.Workspaces
import org.unitedsoftware.alexandrite.internal.path.PathCanonicalizer
import org.unitedsoftware.alexandrite.internal.path.expandHome
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.di.Binds
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a turn's writes may land, the same for a sandbox's commands and for the file writes that `allowedit` runs: the
 * agent's workspace, the conversation's directory, the folders the agent trusts, the temp directory and the paths that
 * the sandbox section of a sandboxed agent allows writing.
 */
@Singleton
@Binds(EditPlaces::class)
internal class WritePlaces(
    directory: AgentDirectory,
    paths: HostPaths,
    private val workspaces: Workspaces,
    private val directories: ConversationDirectories,
    private val trust: FolderTrust,
) : EditPlaces {
    private val canonicalizer = PathCanonicalizer()

    private val allowWrite: Map<AgentId, List<Path>> = directory.agents.filter {
        it.config.sandbox.type != SandboxConfig.HOST
    }.associate { agent ->
        val key = "agent.agents.${agent.id}.sandbox.allowWrite"
        agent.id to agent.config.sandbox.allowWrite.map {
            configuredPath(paths, key, "the writable path", expandHome(it))
        }
    }

    /** The real places where [turn]'s writes may land, with the trusted folders that cover [folders]. */
    fun writable(turn: TurnInfo, folders: List<Path>): List<Path> {
        val agent = turn.agent
        val trusted = folders.mapNotNull { trust.covering(agent, real(it))?.path }
        val own = listOfNotNull(workspaces.paths[agent], directories.path(turn))
        return (own + trusted + temporary() + allowWrite[agent].orEmpty()).map(::real).distinct()
    }

    override fun covers(turn: TurnInfo, file: Path): Boolean {
        val target = real(file)
        return writable(turn, listOfNotNull(target.parent)).any(target::startsWith)
    }

    private fun real(path: Path): Path = canonicalizer.canonicalize(path.toAbsolutePath().normalize()).real
}

/** The per-user temp directory. */
private fun temporary(): List<Path> =
    listOfNotNull(System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) })
