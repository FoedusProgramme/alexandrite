package org.unitedsoftware.alexandrite.agent.directory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.agent.config.AgentSettings
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.stream.Collectors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.days

/** Removes a conversation's directory once it has stayed sealed for `conversationDirectory.keepDays`. */
@Singleton
internal class ConversationDirectorySweep(
    private val directories: ConversationDirectories,
    private val settings: AgentSettings,
    private val conversations: ConversationStore,
    private val clock: Clock,
    scope: PluginScope,
) : Lifecycle {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var loop: Job? = null

    override suspend fun onOpen() {
        sweep()
        loop = scope.launch {
            while (isActive) {
                delay(INTERVAL)
                sweep()
            }
        }
    }

    override suspend fun onClose() {
        loop?.cancel()
    }

    /** Removes every conversation directory whose conversation has been sealed long enough. */
    @OptIn(ExperimentalPathApi::class)
    suspend fun sweep() {
        try {
            val cutoff = clock.instant().minus(Duration.ofDays(settings.conversationDirectory.keepDays.toLong()))
            for (agent in settings.agents.keys.map(::AgentId)) {
                val agentDirectory = directories.conversations(agent)
                if (!Files.isDirectory(agentDirectory)) continue
                for (conversationDirectory in children(agentDirectory)) {
                    val conversation = ConversationId(conversationDirectory.fileName.toString())
                    if (sealedBefore(conversation, cutoff)) {
                        withContext(Dispatchers.IO) { conversationDirectory.deleteRecursively() }
                        logger.info("Removed the conversation directory {} of agent '{}'", conversationDirectory, agent)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("The conversation directory sweep failed: {}", e.toString())
        }
    }

    /** Whether the conversation [conversation] was sealed before [cutoff], false when it could not be read. */
    private suspend fun sealedBefore(conversation: ConversationId, cutoff: Instant): Boolean {
        val info = conversations.conversation(conversation) ?: return false
        val sealedAt = info.sealedAt ?: return false
        return info.state == ConversationState.SEALED && sealedAt <= cutoff
    }

    private suspend fun children(directory: Path): List<Path> = withContext(Dispatchers.IO) {
        Files.list(directory).use { entries -> entries.filter(Files::isDirectory).collect(Collectors.toList()) }
    }
}

private val INTERVAL = 1.days

private val logger = LoggerFactory.getLogger(ConversationDirectorySweep::class.java)
