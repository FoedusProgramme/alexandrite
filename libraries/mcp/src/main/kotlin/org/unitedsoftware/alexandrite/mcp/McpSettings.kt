package org.unitedsoftware.alexandrite.mcp

import org.unitedsoftware.alexandrite.mcp.client.LaunchCheck
import org.unitedsoftware.alexandrite.mcp.client.McpTool
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.runtime.HostPaths
import org.unitedsoftware.alexandrite.sdk.sandbox.ExecutionEnvironment
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How the plugin reaches a server. */
internal sealed interface Transport {
    class Stdio(
        val command: String,
        val args: List<String>,
        val env: Map<String, String>,
        val cwd: Path?,
        /** Null for none. */
        val sandbox: SandboxSpec? = null,
    ) : Transport

    class Http(val url: URI, val headers: Map<String, String>, val oauth: OAuthSpec?) : Transport
}

/** A configured server, checked. */
internal class ServerSpec(
    val name: String,
    val transport: Transport,
    private val allow: List<Glob>,
    private val deny: List<Glob>,
    private val risks: List<Pair<Glob, ToolRisk>>,
    val trustAnnotations: Boolean,
    val trusted: Boolean,
    val timeout: Duration,
    val enabled: Boolean,
    /** What is risky about how the server is launched, null when nothing is. */
    val warning: String? = null,
) {
    /** Whether the server's tool [name] is offered. */
    fun offers(name: String): Boolean = allow.any { it.matches(name) } && deny.none { it.matches(name) }

    /** The risk of [tool]: its first matching `risk` glob, else its trusted `readOnlyHint`, else EXEC. */
    fun risk(tool: McpTool): ToolRisk {
        risks.firstOrNull { (glob, _) -> glob.matches(tool.name) }?.let { return it.second }
        if (trustAnnotations && tool.annotations?.readOnlyHint == true) {
            return if (transport is Transport.Http) ToolRisk.NETWORK_READ else ToolRisk.READ_ONLY
        }
        return ToolRisk.EXEC
    }
}

/** The configured OAuth client of a server, [clientId] null for one registered dynamically. */
internal class OAuthSpec(val clientId: String?, val clientSecret: String?, val scopes: List<String>)

/** Where authorization servers send the browser back: under [publicOrigin] when set, else to a loopback address. */
internal class CallbackSpec(val publicOrigin: URI?, val port: Int?)

/** The servers of the config, checked at the start. */
@Singleton
internal class McpSettings(
    config: McpConfig,
    private val environments: List<ExecutionEnvironment>,
    private val paths: HostPaths,
) {
    val servers: List<ServerSpec> = config.servers.map { (name, server) -> spec(name, server) }

    val callback: CallbackSpec = callback(config)

    private fun callback(config: McpConfig): CallbackSpec {
        val port = config.callbackPort
        if (port != null && port !in 1..MAX_PORT) {
            throw ConfigException("$ROOT.callbackPort", "must be a port from 1 to $MAX_PORT.")
        }
        val origin = config.publicOrigin ?: return CallbackSpec(null, port)
        val uri = try {
            URI(origin.removeSuffix("/"))
        } catch (e: URISyntaxException) {
            null
        }
        val scheme = uri?.scheme?.lowercase()
        val valid = uri != null && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
            uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
            (scheme == "https" || (scheme == "http" && isLoopback(uri.host)))
        if (!valid) {
            throw ConfigException(
                "$ROOT.publicOrigin",
                "must be an https origin such as \"https://bot.example.com\", or an http one on 127.0.0.1 or " +
                    "localhost, with no path.",
            )
        }
        if (port == null) {
            throw ConfigException(
                "$ROOT.callbackPort",
                "is needed with publicOrigin: the port on 127.0.0.1 that <publicOrigin>$PUBLIC_PREFIX reaches.",
            )
        }
        val canonical = URI(scheme, null, uri.host.lowercase(), uri.port, null, null, null)
        return CallbackSpec(canonical, port)
    }

    private fun spec(name: String, server: ServerConfig): ServerSpec {
        val path = "$ROOT.servers.$name"
        if (!NAME.matches(name)) {
            throw ConfigException(
                path,
                "'$name' is no server name: a name is a lowercase letter followed by lowercase letters, digits and " +
                    "'_', $MAX_NAME characters at most, such as \"github\".",
            )
        }
        val transport = when {
            server.command != null && server.url != null ->
                throw ConfigException(path, "a server has either a command or a url, not both.")

            server.command != null -> stdio(path, server, server.command)

            server.url != null -> http(path, server, server.url)

            else -> throw ConfigException(path, "a server needs a command to run or a url to reach.")
        }
        if (server.timeoutSeconds <= 0) throw ConfigException("$path.timeoutSeconds", "must be positive.")
        val risks = server.risk.map { (glob, id) ->
            val risk = ToolRisk.entries.firstOrNull { it.name.lowercase() == id }
                ?: throw ConfigException(
                    "$path.risk.$glob",
                    "'$id' is no risk: write one of ${ToolRisk.entries.joinToString { it.name.lowercase() }}.",
                )
            Glob(glob) to risk
        }
        return ServerSpec(
            name,
            transport,
            server.tools.allow.map(::Glob),
            server.tools.deny.map(::Glob),
            risks,
            server.trustAnnotations,
            server.trusted,
            server.timeoutSeconds.seconds,
            server.enabled,
            (transport as? Transport.Stdio)?.let { LaunchCheck.warning(it.command, it.args) },
        )
    }

    private fun stdio(path: String, server: ServerConfig, command: String): Transport.Stdio {
        if (server.headers.isNotEmpty()) throw ConfigException("$path.headers", "only a server with a url has headers.")
        if (server.oauth != null) throw ConfigException("$path.oauth", "only a server with a url has oauth.")
        if (command.isBlank()) throw ConfigException("$path.command", "is empty: name the program to run.")
        val cwd = server.cwd?.let { cwd ->
            val directory = try {
                Paths.get(cwd)
            } catch (e: InvalidPathException) {
                null
            }
            directory?.takeIf { it.isAbsolute } ?: throw ConfigException("$path.cwd", "must be an absolute path.")
        }
        val sandbox = serverSandbox("$path.sandbox", server.sandbox, cwd, environments, paths)
        return Transport.Stdio(command, server.args, server.env.mapValues { it.value.reveal() }, cwd, sandbox)
    }

    private fun http(path: String, server: ServerConfig, url: String): Transport.Http {
        val misplaced = listOfNotNull(
            "args".takeIf { server.args.isNotEmpty() },
            "env".takeIf { server.env.isNotEmpty() },
            "cwd".takeIf { server.cwd != null },
        )
        misplaced.firstOrNull()?.let { throw ConfigException("$path.$it", "only a server with a command has $it.") }
        if (server.sandbox.type != ServerSandboxConfig.HOST) {
            throw ConfigException("$path.sandbox", "only a server with a command runs in a sandbox.")
        }
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            null
        }
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) {
            throw ConfigException("$path.url", "must be an http or https URL.")
        }
        val oauth = server.oauth?.let { oauth(path, server, it) }
        return Transport.Http(uri, server.headers.mapValues { it.value.reveal() }, oauth)
    }

    private fun oauth(path: String, server: ServerConfig, oauth: OAuthConfig): OAuthSpec {
        if (server.headers.keys.any { it.equals(AUTHORIZATION, ignoreCase = true) }) {
            throw ConfigException("$path.oauth", "a server has either an Authorization header or oauth, not both.")
        }
        val clientId = oauth.clientId
        if (clientId != null && clientId.isBlank()) throw ConfigException("$path.oauth.clientId", "may not be blank.")
        if (oauth.clientSecret != null && clientId == null) {
            throw ConfigException("$path.oauth.clientSecret", "needs the clientId it belongs to.")
        }
        oauth.scopes.forEachIndexed { index, scope ->
            if (!SCOPE.matches(scope)) throw ConfigException("$path.oauth.scopes[$index]", "'$scope' is no scope.")
        }
        return OAuthSpec(clientId, oauth.clientSecret?.reveal(), oauth.scopes.distinct())
    }

    companion object {
        const val ROOT: String = "mcp"
        const val MAX_NAME: Int = 32
        val NAME: Regex = Regex("[a-z][a-z0-9_]{0,${MAX_NAME - 1}}")
        const val MAX_PORT: Int = 65535
        const val AUTHORIZATION: String = "Authorization"

        /** The path under a public origin that the host serves its callback and its client document at. */
        const val PUBLIC_PREFIX: String = "/oauth/mcp/"

        /** A scope token of RFC 6749. */
        val SCOPE: Regex = Regex("[\\x21\\x23-\\x5b\\x5d-\\x7e]+")
    }
}

/** Whether [host] names the loopback interface. */
internal fun isLoopback(host: String?): Boolean =
    host != null && host.lowercase().removePrefix("[").removeSuffix("]") in setOf("127.0.0.1", "localhost", "::1")

/** A pattern over names where `*` stands for any run of characters and `?` for one. */
internal class Glob(pattern: String) {
    private val regex = Regex(
        buildString {
            for (char in pattern) {
                when (char) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(char.toString()))
                }
            }
        },
    )

    fun matches(name: String): Boolean = regex.matches(name)
}
