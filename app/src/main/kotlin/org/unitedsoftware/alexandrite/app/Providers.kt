package org.unitedsoftware.alexandrite.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.account.pleochroic.PLEOCHROIC_API_BASE
import java.io.IOException

/**
 * A model provider: its config [id] below `providers`, the [endpoint] it is named by default, its [defaultUrl] (null
 * when the operator names one), the path of its models request and the variable of its key.
 */
internal class ProviderKind(
    val id: String,
    val endpoint: String,
    val defaultUrl: String?,
    val modelsPath: String,
    val keyVariable: String,
    /** Whether its config needs a key. */
    val keyRequired: Boolean,
    /** Whether its models request is signed as the Anthropic API signs it. */
    val anthropic: Boolean = false,
    /** The path of a request that only a valid key gets an answer to, where the models request needs none. */
    val keyPath: String? = null,
    /** Whether its models request gives the context windows that its endpoints list. */
    val listsWindows: Boolean = true,
)

/** The providers in the order `init` offers them: alphabetically by [title], Pleochroic a peer of the rest (its
 * own device login, in place of the others' key prompt, is `Setup.pleochroicModel`'s business, not this list's). */
internal val PROVIDER_KINDS: List<ProviderKind> = listOf(
    ProviderKind(
        "anthropic-compatible",
        "anthropic",
        "https://api.anthropic.com",
        "/v1/models",
        "ANTHROPIC_API_KEY",
        false,
        anthropic = true,
    ),
    ProviderKind("deepseek", "deepseek", "https://api.deepseek.com", "/models", "DEEPSEEK_API_KEY", true),
    ProviderKind(
        "lmstudio",
        "lmstudio",
        "http://localhost:1234/v1",
        "/models",
        "LMSTUDIO_API_KEY",
        false,
        listsWindows = false,
    ),
    ProviderKind(
        "openai-responses",
        "openai",
        "https://api.openai.com/v1",
        "/models",
        "OPENAI_API_KEY",
        false,
    ),
    ProviderKind(
        "openai-compatible",
        "compatible",
        null,
        "/models",
        "OPENAI_COMPATIBLE_API_KEY",
        false,
    ),
    ProviderKind(
        "openrouter",
        "openrouter",
        "https://openrouter.ai/api/v1",
        "/models",
        "OPENROUTER_API_KEY",
        true,
        keyPath = "/key",
    ),
    ProviderKind(
        "pleochroic",
        PLEOCHROIC_ENDPOINT,
        PLEOCHROIC_API_BASE,
        "/models",
        "PLEOCHROIC_API_KEY",
        true,
    ),
)

internal fun providerKind(id: String): ProviderKind? = PROVIDER_KINDS.firstOrNull { it.id == id }

internal fun requireProvider(id: String): ProviderKind = providerKind(id)
    ?: throw UsageException(
        "usage.init_provider",
        "provider" to id,
        "providers" to PROVIDER_KINDS.joinToString { it.id },
    )

/** The headers that sign a request of [kind] with [key], none without a key. */
internal fun keyHeaders(kind: ProviderKind, key: String?): Map<String, String> = when {
    key == null -> emptyMap()
    kind.anthropic -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
    else -> mapOf("Authorization" to "Bearer $key")
}

/** What a models request found. */
internal sealed interface Listing {
    class Models(val ids: List<String>) : Listing

    /** The endpoint refuses the key, or wants one. */
    class Refused(val reason: Text) : Listing

    /** The endpoint gave no answer that tells whether it takes the key. */
    class Unchecked(val reason: Text) : Listing
}

/** The models the endpoint of [kind] at [base] lists for [key], after the request of [ProviderKind.keyPath]. */
internal fun listModels(cli: Cli, kind: ProviderKind, base: String, key: String?): Listing {
    if (key != null && kind.keyPath != null) {
        val checked = fetch(cli, kind, base, kind.keyPath, key)
        if (checked !is Fetch.Answered) return (checked as Fetch.Failed).listing
    }
    val fetched = when (val answer = fetch(cli, kind, base, kind.modelsPath, key)) {
        is Fetch.Answered -> answer.fetched
        is Fetch.Failed -> return answer.listing
    }
    val data = try {
        (Json.parseToJsonElement(fetched.body) as? JsonObject)?.get("data") as? JsonArray
    } catch (e: IllegalArgumentException) {
        null
    } ?: return Listing.Unchecked(Text("models.no_list", "base" to base))
    return Listing.Models(data.mapNotNull { (it as? JsonObject)?.get("id").text }.distinct())
}

private sealed interface Fetch {
    class Answered(val fetched: Fetched) : Fetch

    class Failed(val listing: Listing) : Fetch
}

/** The successful answer of the endpoint at [base] to [path], signed with [key]. */
private fun fetch(cli: Cli, kind: ProviderKind, base: String, path: String, key: String?): Fetch {
    val fetched = try {
        cli.machine.get("${base.trimEnd('/')}$path", keyHeaders(kind, key))
    } catch (e: IOException) {
        val reason = (e.message ?: "$e").let { if (key != null && key.length >= 4) it.replace(key, "***") else it }
        return Fetch.Failed(Listing.Unchecked(Text("models.unreachable", "base" to base, "reason" to reason)))
    }
    if (fetched.status == 401 || fetched.status == 403) {
        val text = if (key == null) "models.key_wanted" else "models.key_refused"
        return Fetch.Failed(Listing.Refused(Text(text, "base" to base, "status" to fetched.status)))
    }
    if (fetched.status !in 200..299) {
        val text = if (path == kind.keyPath) "models.key_http" else "models.http"
        return Fetch.Failed(Listing.Unchecked(Text(text, "base" to base, "status" to fetched.status)))
    }
    return Fetch.Answered(fetched)
}
