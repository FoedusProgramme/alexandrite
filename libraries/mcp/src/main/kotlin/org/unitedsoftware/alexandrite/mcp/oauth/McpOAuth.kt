package org.unitedsoftware.alexandrite.mcp.oauth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.mcp.McpSettings
import org.unitedsoftware.alexandrite.mcp.OAuthSpec
import org.unitedsoftware.alexandrite.mcp.Transport
import org.unitedsoftware.alexandrite.mcp.client.McpConnectionException
import org.unitedsoftware.alexandrite.mcp.isLoopback
import org.unitedsoftware.alexandrite.sdk.store.Records
import org.unitedsoftware.alexandrite.sdk.store.collection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** A login that started, waiting for its authorization response. */
internal class LoginStart(val url: String, val loopback: Boolean)

/** The OAuth logins of the servers reached over HTTP, and their tokens kept in [records] and refreshed before expiry. */
internal class McpOAuth(
    private val settings: McpSettings,
    records: Records,
    transport: HttpTransport,
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** Whether this host can listen for the browser's return from a login. */
    val callbacks: Boolean,
    private val reconnect: (String) -> Unit,
) {
    private val http = OAuthHttp(transport)
    private val discovery = Discovery(http)
    private val requests = TokenRequests(http)
    private val stored = records.collection<StoredTokens>(TOKENS)
    private val clients = records.collection<StoredClient>(CLIENTS)
    private val held = ConcurrentHashMap<String, Held>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val logins = ConcurrentHashMap<String, Login>()
    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val wider = ConcurrentHashMap<String, List<String>>()
    private val strikes = ConcurrentHashMap<String, Int>()
    private val listening = Mutex()
    private val loopbacks = HashMap<Int, CallbackServer>()

    @Volatile
    private var public: CallbackServer? = null

    /** Opens the public callback when the config names one. */
    fun open() {
        if (settings.callback.publicOrigin == null) return
        if (!callbacks) {
            logger.warn("MCP servers cannot log in: {}", NO_CALLBACK)
            return
        }
        try {
            publicListener()
        } catch (e: OAuthException) {
            logger.warn("MCP logins cannot complete by themselves for now: {}", e.message)
        }
    }

    fun close() {
        logins.clear()
        synchronized(loopbacks) {
            loopbacks.values.forEach(CallbackServer::close)
            loopbacks.clear()
            public?.close()
            public = null
        }
    }

    /** The access token that requests to [server] carry, refreshed first when it is about to expire. */
    suspend fun bearer(server: String): String? {
        val tokens = tokens(server) ?: return null
        if (fresh(tokens)) return tokens.accessToken
        return lock(server).withLock {
            val current = tokens(server) ?: return@withLock null
            if (fresh(current)) current.accessToken else refresh(current)
        }
    }

    /**
     * Notes that [server] refused a request with [status] and the challenge [header]: a token it refuses twice in a row
     * is forgotten, a narrow one marks the scopes a new login asks for.
     */
    suspend fun refused(server: String, status: Int, header: String?, authorized: Boolean) {
        val challenge = Challenge.parse(header)
        if (challenge != null) challenges[server] = challenge
        if (status == FORBIDDEN && challenge?.insufficientScope == true) {
            wider[server] = challenge.scopes
            logger.warn(
                "MCP server {} asks for a login with the wider scope '{}'",
                server,
                challenge.scopes.joinToString(" "),
            )
            return
        }
        if (status != UNAUTHORIZED || !authorized) return
        if (strikes.merge(server, 1, Int::plus)!! >= MAX_STRIKES) {
            strikes.remove(server)
            lock(server).withLock { forget(server) }
            logger.warn("MCP server {} keeps refusing its access token, which is forgotten: it needs a login", server)
        } else {
            lock(server).withLock { tokens(server)?.let { held[server] = Held(it.stale()) } }
        }
    }

    /** Notes that [server] accepted its token. */
    fun connected(server: String) {
        strikes.remove(server)
    }

    /** The scopes [server] last asked a wider login for, null when it asked for none. */
    fun widerScopes(server: String): List<String>? = wider[server]

    /** The transport that a login to [server] goes over, which throws [OAuthException] when it has none. */
    fun loginTransport(server: String): Transport.Http =
        settings.servers.firstOrNull { it.name == server }?.transport as? Transport.Http
            ?: throw OAuthException("MCP server $server is not reached over HTTP, so it has no login.")

    /** Starts a login to [server], whose authorization link the operator opens. */
    suspend fun start(server: String): LoginStart {
        val transport = loginTransport(server)
        if (!callbacks) throw OAuthException("MCP server $server cannot log in: $NO_CALLBACK")
        val resourceMetadata = discovery.resource(transport.url, challenges[server])
        val resource = resource(transport.url, resourceMetadata)
        val metadata = discovery.authorizationServer(resourceMetadata.authorizationServers.first())
        val registered = if (transport.oauth?.clientId == null) clients.get(metadata.issuer)?.value else null
        return listening.withLock {
            expire()
            try {
                val redirect = redirect(registered)
                val client = client(server, transport.oauth, metadata, registered, redirect)
                val scopes = scopes(server, transport.oauth, resourceMetadata, metadata)
                val pkce = Pkce.create()
                val state = randomToken()
                logins.values.removeIf { it.server == server }
                logins[state] =
                    Login(server, pkce, metadata, client, redirect, resource, scopes, clock.instant())
                scope.launch {
                    delay(LOGIN_TTL)
                    listening.withLock { expire() }
                }
                logger.info("MCP server {}: a login at {} started", server, metadata.issuer)
                val url = authorizationUrl(metadata, client, redirect, state, pkce, resource, scopes)
                LoginStart(url, redirect.port != null)
            } finally {
                release()
            }
        }
    }

    /**
     * Completes the login that [response] answers, the address the browser was sent back to or its query, for
     * [server] when given, and returns the server's name.
     */
    suspend fun complete(server: String?, response: String): String {
        val params = parse(response)
        val state = params["state"] ?: throw OAuthException("The address carries no state: copy the whole address.")
        val login = logins[state]?.takeIf { server == null || it.server == server }
        if (login == null || !logins.remove(state, login)) {
            throw OAuthException("No login waits for this answer: it expired or another one replaced it.")
        }
        try {
            val issuer = login.metadata.issuer
            if (Duration.between(login.started, clock.instant()) > LOGIN_TTL.toJavaDuration()) {
                throw OAuthException("The login expired after ${LOGIN_TTL.inWholeMinutes} minutes.")
            }
            val iss = params["iss"]
            if (iss != null && iss != issuer) {
                val quoted = cutWithEllipsis(iss, MAX_QUOTE)
                throw OAuthException("The answer comes from the issuer '$quoted', not $issuer.")
            }
            if (iss == null && login.metadata.issParameter) {
                throw OAuthException("The answer names no issuer, though $issuer promises to.")
            }
            params["error"]?.let {
                throw OAuthException("$issuer refused the login: ${cutWithEllipsis(it, MAX_QUOTE)}.")
            }
            val code = params["code"] ?: throw OAuthException("The answer carries no code.")
            val grant = requests.exchange(
                login.metadata.tokenEndpoint,
                login.client,
                code,
                login.pkce.verifier,
                login.redirect.uri,
                login.resource,
            )
            val tokens = StoredTokens(
                login.server,
                issuer,
                login.metadata.tokenEndpoint.toString(),
                login.client.id,
                login.client.method,
                login.resource,
                grant.accessToken,
                grant.refreshToken,
                grant.expiresAt(clock.instant()),
                grant.scopes ?: login.scopes,
            )
            lock(login.server).withLock { save(tokens) }
            strikes.remove(login.server)
            wider.remove(login.server)
            challenges.remove(login.server)
            logger.info("MCP server {} is logged in at {}", login.server, issuer)
            reconnect(login.server)
            return login.server
        } finally {
            listening.withLock { release() }
        }
    }

    /** Forgets the tokens of [server] and its pending login, false when it had neither. */
    suspend fun logout(server: String): Boolean {
        val pending = logins.values.removeIf { it.server == server }
        listening.withLock { release() }
        val had = lock(server).withLock { forget(server) }
        wider.remove(server)
        strikes.remove(server)
        if (had) {
            logger.info("MCP server {} is logged out", server)
            reconnect(server)
        }
        return had || pending
    }

    // Tokens.

    private suspend fun tokens(server: String): StoredTokens? = held[server]?.tokens ?: run {
        if (held.containsKey(server)) return null
        val found = stored.list().map { it.value }.firstOrNull { it.server == server }
        held.putIfAbsent(server, Held(found))
        held[server]?.tokens
    }

    private fun fresh(tokens: StoredTokens): Boolean {
        val expiresAt = tokens.expiresAt ?: return true
        return clock.millis() < expiresAt - REFRESH_AHEAD.inWholeMilliseconds
    }

    /** The access token of [tokens] refreshed, null when the authorization server refused and they are forgotten. */
    private suspend fun refresh(tokens: StoredTokens): String? {
        val server = tokens.server
        val refreshToken = tokens.refreshToken
        if (refreshToken == null) {
            forget(server)
            logger.warn("MCP server {}'s access token expired and cannot be refreshed: it needs a new login", server)
            return null
        }
        val grant = try {
            requests.refresh(URI(tokens.tokenEndpoint), auth(tokens), refreshToken, tokens.resource)
        } catch (e: OAuthErrorException) {
            forget(server)
            logger.warn(
                "MCP server {}: {} refused to refresh the access token ({}): it needs a new login",
                server,
                tokens.issuer,
                e.error,
            )
            return null
        } catch (e: OAuthException) {
            throw McpConnectionException("Cannot refresh the access token: ${e.message}")
        }
        save(tokens.refreshed(grant, clock.instant()))
        logger.info("MCP server {}'s access token was refreshed", server)
        return grant.accessToken
    }

    private suspend fun auth(tokens: StoredTokens): ClientAuth {
        val transport = settings.servers.firstOrNull { it.name == tokens.server }?.transport
        val configured = (transport as? Transport.Http)?.oauth
        val secret = if (configured?.clientId == tokens.clientId) {
            configured.clientSecret
        } else {
            clients.get(tokens.issuer)?.value?.takeIf { it.clientId == tokens.clientId }?.clientSecret
        }
        return ClientAuth(tokens.clientId, secret, tokens.authMethod)
    }

    private suspend fun save(tokens: StoredTokens) {
        val key = key(tokens.server, tokens.issuer)
        for (record in stored.list()) {
            if (record.value.server == tokens.server && record.key != key) stored.remove(record.key)
        }
        stored.put(key, tokens, null)
        held[tokens.server] = Held(tokens)
    }

    private suspend fun forget(server: String): Boolean {
        var had = false
        for (record in stored.list()) {
            if (record.value.server == server) had = stored.remove(record.key) || had
        }
        held[server] = Held(null)
        return had
    }

    private fun lock(server: String): Mutex = locks.computeIfAbsent(server) { Mutex() }

    // Logins.

    /** The canonical URI of [server], or the resource its metadata names when that covers it. */
    private fun resource(server: URI, metadata: ResourceMetadata): String {
        val path = server.rawPath.orEmpty().let { if (it == "/") "" else it }
        val canonical = Discovery.origin(server) + path
        val named = metadata.resource ?: return canonical
        val uri = try {
            URI(named)
        } catch (e: URISyntaxException) {
            null
        }
        val covers = uri?.scheme != null && uri.host != null && uri.rawFragment == null &&
            Discovery.origin(uri) == Discovery.origin(server) && port(uri) == port(server) &&
            "$path/".startsWith(uri.rawPath.orEmpty().trimEnd('/') + "/")
        if (!covers) throw OAuthException("The server's resource metadata is for '$named', not for $canonical.")
        return named
    }

    private fun port(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.lowercase() == "https" -> HTTPS_PORT
        else -> HTTP_PORT
    }

    private fun redirect(registered: StoredClient?): Redirect {
        val origin = settings.callback.publicOrigin
        if (origin != null) {
            publicListener()
            return Redirect("$origin${McpSettings.PUBLIC_PREFIX}$CALLBACK", null)
        }
        val fixed = settings.callback.port
        val preferred = fixed ?: registered?.redirectUris?.firstNotNullOfOrNull(::loopbackPort)
        val listener = loopback(preferred ?: 0) ?: preferred.takeIf { fixed == null }?.let { loopback(0) }
            ?: throw OAuthException("Cannot listen on ${CallbackServer.LOOPBACK}:${preferred ?: 0} for the login.")
        return Redirect("http://${CallbackServer.LOOPBACK}:${listener.port}/$CALLBACK", listener.port)
    }

    private fun loopback(port: Int): CallbackServer? = synchronized(loopbacks) {
        loopbacks[port]?.let { return it }
        val opened = CallbackServer.open(port, "/$CALLBACK", ::fromBrowser) ?: return null
        loopbacks[opened.port] = opened
        opened
    }

    private fun publicListener(): CallbackServer = synchronized(loopbacks) {
        public?.let { return it }
        val port = checkNotNull(settings.callback.port)
        val opened = CallbackServer.open(
            port,
            "${McpSettings.PUBLIC_PREFIX}$CALLBACK",
            ::fromBrowser,
            "${McpSettings.PUBLIC_PREFIX}$DOCUMENT",
            ::clientDocument,
        ) ?: throw OAuthException("Cannot listen on ${CallbackServer.LOOPBACK}:$port for the public callback.")
        public = opened
        opened
    }

    /** Closes the loopback listeners that no login waits on. */
    private fun release() {
        synchronized(loopbacks) {
            val used = logins.values.mapNotNull { it.redirect.port }.toSet()
            loopbacks.entries.removeIf { (port, listener) -> (port !in used).also { if (it) listener.close() } }
        }
    }

    private fun expire() {
        val now = clock.instant()
        logins.values.removeIf { Duration.between(it.started, now) > LOGIN_TTL.toJavaDuration() }
        release()
    }

    private suspend fun client(
        server: String,
        configured: OAuthSpec?,
        metadata: ServerMetadata,
        registered: StoredClient?,
        redirect: Redirect,
    ): ClientAuth {
        configured?.clientId?.let { id ->
            val secret = configured.clientSecret
            val methods = metadata.tokenAuthMethods
            val method = when {
                secret == null -> NONE

                methods != null && CLIENT_SECRET_BASIC !in methods && CLIENT_SECRET_POST in methods ->
                    CLIENT_SECRET_POST

                else -> CLIENT_SECRET_BASIC
            }
            return ClientAuth(id, secret, method)
        }
        if (registered != null && redirect.uri in registered.redirectUris) {
            return ClientAuth(registered.clientId, registered.clientSecret, registered.authMethod)
        }
        val origin = settings.callback.publicOrigin
        metadata.registrationEndpoint?.let { endpoint ->
            val native = origin == null || isLoopback(origin.host)
            val client = requests.register(endpoint, metadata.issuer, CLIENT_NAME, redirect.uri, native)
            clients.put(metadata.issuer, client, null)
            logger.info("MCP server {}: {} registered a client for the host", server, metadata.issuer)
            return ClientAuth(client.clientId, client.clientSecret, client.authMethod)
        }
        if (metadata.clientIdMetadataDocuments && origin?.scheme == "https") {
            return ClientAuth(documentUrl(origin), null, NONE)
        }
        throw OAuthException(
            "${metadata.issuer} registers no clients by itself, so MCP server $server needs a client of its own: " +
                "register one there and set mcp.servers.$server.oauth.clientId (and clientSecret if it has one).",
        )
    }

    /** What the configured scopes, the granted ones and the server's challenges ask for, else what it supports. */
    private fun scopes(
        server: String,
        configured: OAuthSpec?,
        resource: ResourceMetadata,
        metadata: ServerMetadata,
    ): List<String> {
        val granted = held[server]?.tokens?.scopes.orEmpty()
        val asked = (configured?.scopes.orEmpty() + granted + challenges[server]?.scopes.orEmpty()).distinct()
        val scopes = asked.ifEmpty { resource.scopesSupported.orEmpty() }
        val offline = metadata.scopesSupported?.contains(OFFLINE_ACCESS) == true && scopes.isNotEmpty()
        return if (offline && OFFLINE_ACCESS !in scopes) scopes + OFFLINE_ACCESS else scopes
    }

    private fun authorizationUrl(
        metadata: ServerMetadata,
        client: ClientAuth,
        redirect: Redirect,
        state: String,
        pkce: Pkce,
        resource: String,
        scopes: List<String>,
    ): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to client.id)
            add("redirect_uri" to redirect.uri)
            if (scopes.isNotEmpty()) add("scope" to scopes.joinToString(" "))
            add("state" to state)
            add("code_challenge" to pkce.challenge)
            add("code_challenge_method" to Discovery.S256)
            add("resource" to resource)
        }
        val endpoint = metadata.authorizationEndpoint.toString()
        val separator = if (metadata.authorizationEndpoint.rawQuery == null) "?" else "&"
        return endpoint + separator + params.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    }

    private suspend fun fromBrowser(query: String): CallbackPage = try {
        val server = complete(null, query)
        CallbackPage(CallbackServer.OK, "MCP server $server is logged in. You can close this page.")
    } catch (e: OAuthException) {
        CallbackPage(CallbackServer.BAD_REQUEST, "The login failed: ${e.message}")
    }

    private fun clientDocument(): String {
        val origin = checkNotNull(settings.callback.publicOrigin)
        val document = buildJsonObject {
            put("client_id", documentUrl(origin))
            put("client_name", CLIENT_NAME)
            put("redirect_uris", JsonArray(listOf(JsonPrimitive("$origin${McpSettings.PUBLIC_PREFIX}$CALLBACK"))))
            put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
            put("token_endpoint_auth_method", NONE)
            put("application_type", "web")
        }
        return OAuthHttp.JSON_FORMAT.encodeToString(JsonObject.serializer(), document)
    }

    private fun documentUrl(origin: URI): String = "$origin${McpSettings.PUBLIC_PREFIX}$DOCUMENT"

    private class Held(val tokens: StoredTokens?)

    private class Redirect(val uri: String, val port: Int?)

    private class Login(
        val server: String,
        val pkce: Pkce,
        val metadata: ServerMetadata,
        val client: ClientAuth,
        val redirect: Redirect,
        val resource: String,
        val scopes: List<String>,
        val started: Instant,
    )

    companion object {
        const val CLIENT_NAME: String = "Alexandrite"
        const val CALLBACK: String = "callback"
        const val DOCUMENT: String = "client.json"
        const val NO_CALLBACK: String = "this host has no HTTP server for the OAuth callback."
        val LOGIN_TTL: kotlin.time.Duration = 10.minutes
        private val REFRESH_AHEAD = 1.minutes
        private const val TOKENS = "oauth.tokens"
        private const val CLIENTS = "oauth.clients"
        private const val OFFLINE_ACCESS = "offline_access"
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val MAX_STRIKES = 2
        private const val MAX_QUOTE = 100
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80

        private fun key(server: String, issuer: String) = "$server $issuer"

        private fun loopbackPort(uri: String): Int? = try {
            URI(uri).takeIf { it.host == CallbackServer.LOOPBACK }?.port?.takeIf { it > 0 }
        } catch (e: URISyntaxException) {
            null
        }

        /** The parameters of an authorization response, given as the address it went to or as its query. */
        fun parse(response: String): Map<String, String> {
            val text = response.trim()
            val query = (if ('?' in text) text.substringAfter('?') else text).substringBefore('#')
            return query.split('&').filter { '=' in it }.associate { pair ->
                val (name, value) = pair.split('=', limit = 2)
                decode(name) to decode(value)
            }
        }

        private fun decode(text: String): String = try {
            URLDecoder.decode(text, Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw OAuthException("The address is not encoded properly: copy the whole address.")
        }
    }
}

private fun StoredTokens.stale(): StoredTokens = StoredTokens(
    server,
    issuer,
    tokenEndpoint,
    clientId,
    authMethod,
    resource,
    accessToken,
    refreshToken,
    0,
    scopes,
)

private val logger = LoggerFactory.getLogger(McpOAuth::class.java)
