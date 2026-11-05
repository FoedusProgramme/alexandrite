package org.unitedsoftware.alexandrite.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.runtime.HostApi
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.InitiatedTurn
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnInitiator
import org.unitedsoftware.alexandrite.sdk.turn.TurnInput
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** How long after the start the first check waits. */
private val FIRST_CHECK = 2.minutes

private val TICK = 5.minutes

private val logger = LoggerFactory.getLogger("org.unitedsoftware.alexandrite.app.Updates")

/** When the last message from a person came in, across all chats. */
@Contribute(Hook::class)
internal class LastMessage(private val clock: Clock) : InterceptorHook<TurnInput> {
    override val point: InterceptorPoint<TurnInput> = TurnPoints.TURN_INPUT

    /** Null when none came since the start. */
    @Volatile
    var at: Instant? = null
        private set

    override suspend fun intercept(payload: TurnInput): HookDecision<TurnInput> {
        if (payload.message != null) at = clock.instant()
        return HookDecision.Continue
    }
}

/** What the update watch reads of the agents. */
internal interface AgentActivity {
    /** When the last message from a person came in, null when none did since the start. */
    val lastMessage: Instant?

    /** The agent that is told of updates, null when there is none. */
    fun defaultAgent(): AgentId?

    /** Whether a turn or a background job runs or waits. */
    suspend fun busy(): Boolean
}

/** What the update watch reaches of the running app. */
@HostApi
@Singleton
internal class UpdateDesk(
    val records: Records,
    val initiator: TurnInitiator,
    private val messages: LastMessage,
    private val turns: TurnControl?,
    private val catalog: AgentCatalog?,
    private val channels: ChannelDirectory,
) : AgentActivity {
    override val lastMessage: Instant? get() = messages.at

    /** The first agent that is the default of a channel instance, preferring one with a home chat. */
    override fun defaultAgent(): AgentId? {
        val catalog = catalog ?: return null
        val agents = channels.instances.filter { it != ChatAddress.AGENTS }.sortedBy { "$it" }.flatMap(catalog::agents)
        val chosen = agents.firstOrNull { it.default && it.home != null } ?: agents.firstOrNull { it.default }
            ?: agents.firstOrNull()
        return chosen?.id
    }

    override suspend fun busy(): Boolean =
        turns != null && (turns.turns().isNotEmpty() || turns.jobs(null).isNotEmpty())
}

@Serializable
internal class Announced(val agent: String)

/** An automatic update from [from], reported once the app started after it. */
@Serializable
internal class Attempt(val from: String, val reported: Boolean = false)

/**
 * Asks for the latest release while the app [version] runs, tells the default agent of a newer one once, and installs
 * it through [update] when [settings] ask for that.
 */
internal class UpdateWatch(
    private val version: String,
    private val settings: UpdatesConfig,
    /** The latest release, null when there is none or no answer. */
    private val latest: () -> String?,
    private val base: String,
    private val agents: AgentActivity,
    private val initiator: TurnInitiator,
    records: Records,
    /** Starts the update to a release, false when it does not; null for an app the install script did not install. */
    private val update: ((String) -> Boolean)?,
    private val clock: Clock,
    private val channel: ReleaseChannel = ReleaseChannel.STABLE,
) {
    private val announced = records.collection<Announced>("announced")
    private val attempts = records.collection<Attempt>("updates")
    private val startedAt = clock.instant()

    /** The releases this run tried to install. */
    private val attempted = mutableSetOf<String>()
    private var unscriptedLogged = false

    suspend fun run() {
        delay(FIRST_CHECK)
        while (true) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("The check for a newer release failed", e)
            }
            delay(TICK)
        }
    }

    suspend fun tick() {
        reportUpdates()
        val release = withContext(Dispatchers.IO) { latest() } ?: return
        if (!isNewer(release, version)) return
        announce(release)
        if (settings.install) install(release)
    }

    private suspend fun reportUpdates() {
        for (record in attempts.list()) {
            val attempt = record.value
            if (attempt.reported || record.key in attempted) continue
            if (attempt.from == version) {
                logger.warn("The automatic update to {} did not take, so Alexandrite {} runs on", record.key, version)
            } else if (!tell(updatedPrompt(attempt.from))) {
                continue
            }
            attempts.put(record.key, Attempt(attempt.from, reported = true), null)
        }
    }

    private suspend fun announce(release: String) {
        if (announced.get(release) != null) return
        val agent = agents.defaultAgent()
        if (agent == null || !tell(availablePrompt(release), agent)) return
        announced.put(release, Announced("$agent"), null)
        logger.info("Told agent '{}' that Alexandrite {} is available", agent, release)
    }

    private suspend fun install(release: String) {
        val update = update ?: run {
            if (!unscriptedLogged) {
                logger.info("app.updates.install is on, but the install script did not install this Alexandrite")
                unscriptedLogged = true
            }
            return
        }
        if (release in attempted || attempts.get(release) != null) return
        val idle = Duration.between(agents.lastMessage ?: startedAt, clock.instant())
        if (idle < Duration.ofMinutes(settings.idleMinutes) || agents.busy()) return
        attempted += release
        attempts.put(release, Attempt(version), null)
        logger.info("Updating Alexandrite {} to {}, as nobody wrote for {} minutes", version, release, idle.toMinutes())
        if (!withContext(Dispatchers.IO) { update(release) }) logger.warn("The update to {} did not start", release)
    }

    private fun tell(prompt: String, agent: AgentId? = agents.defaultAgent()): Boolean {
        if (agent == null) {
            logger.debug("No agent can be told of an update")
            return false
        }
        val turn = InitiatedTurn.heartbeat(agent, prompt).trust(Trust.TRUSTED).build()
        return when (val admission = initiator.initiate(turn)) {
            is Admission.Accepted -> true

            else -> {
                logger.debug(
                    "Agent '{}' was not told of an update: {}",
                    agent,
                    (admission as? Admission.Refused)?.reason,
                )
                false
            }
        }
    }

    private fun availablePrompt(release: String): String {
        val itself = if (settings.install && update != null) {
            " This host installs it by itself once nobody has written for ${settings.idleMinutes} minutes and no " +
                "turn or job runs."
        } else {
            ""
        }
        return "Alexandrite $release is available; this host runs $version. Its release notes, for the admins: " +
            "${releasePage(base, release, channel)}. The operator updates with the command `alexandrite update` on " +
            "the host.$itself $TELL"
    }

    private fun updatedPrompt(from: String): String =
        "Alexandrite updated itself from $from to $version, as app.updates.install asks. Its release notes, for " +
            "the admins: ${releasePage(base, version, channel)}. $TELL"

    private companion object {
        const val TELL = "Tell the admins briefly when it suits, with chat.send to \"home\" or to a chat from " +
            "chat.list, and pass the link on without opening it: what a page says makes your conversation untrusted, " +
            "and a person must then approve the message. This turn belongs to no chat, and your reply reaches nobody."
    }
}

/** The install script's update of the app at [locations] to a release, started apart from the app. */
internal fun scriptedUpdate(
    environment: Map<String, String>,
    platform: Platform,
    locations: Locations,
    channel: ReleaseChannel,
): ((String) -> Boolean)? {
    updateCommand(environment, platform, locations, null, channel) ?: return null
    val input = File(if (platform == Platform.WINDOWS) "NUL" else "/dev/null")
    val log = serviceLog(locations).resolveSibling("update.log")
    return { release ->
        val update = checkNotNull(updateCommand(environment, platform, locations, release, channel))
        val command = detached(update, platform, environment)
        try {
            Files.createDirectories(log.parent)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectInput(ProcessBuilder.Redirect.from(input))
                .start()
            platform == Platform.WINDOWS || (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0)
        } catch (e: IOException) {
            logger.warn("Cannot run {}: {}", command.first(), "$e")
            false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/** [command] wrapped to outlive the app: a transient systemd unit under systemd, else a process group of its own. */
internal fun detached(command: List<String>, platform: Platform, environment: Map<String, String>): List<String> =
    when {
        platform == Platform.WINDOWS -> command
        !environment["INVOCATION_ID"].isNullOrEmpty() -> SYSTEMD_RUN + command
        else -> OWN_GROUP + command
    }

private val SYSTEMD_RUN = listOf("systemd-run", "--user", "--collect", "--quiet")

private val OWN_GROUP = listOf("sh", "-c", "set -m; \"\$@\" &", "sh")
