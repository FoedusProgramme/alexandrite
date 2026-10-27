package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A config document never carries a secret's value; it names one by reference instead, in two different spellings:
 * the wire's `${secret:NAME}` (`docs/API.md` "同步" § "配置文档") and Alexandrite's own local `${env:NAME}` plus
 * `secrets.env`. A sync run converts between the two at its boundary, and only for a `NAME` the account actually
 * syncs as a secret: that is the one thing that makes a reference trustworthy, since the service's own `data`
 * never holds a value and the local form is never meant to leave this machine.
 *
 * This also closes a hijack a compromised or impersonated account could otherwise try: a pulled document naming
 * `${env:SOME_LOCAL_VAR}` directly (the local spelling, which the wire never uses) to make Alexandrite resolve an
 * unrelated local secret, such as another provider's or a channel's token, into a field the attacker controls.
 */

/** A bare variable name, as `${env:NAME}` and `${secret:NAME}` both spell it locally and on the wire. */
private val VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val ENV_REF = Regex("^\\$\\{env:(${VARIABLE_NAME.pattern})}$")
private val SECRET_REF = Regex("^\\$\\{secret:(${VARIABLE_NAME.pattern})}$")

/** The local reference to [name], as config documents hold it once pulled. */
public fun localSecretRef(name: String): String = "\${env:$name}"

/** The wire's reference to [name], as config documents hold it once pushed. */
public fun wireSecretRef(name: String): String = "\${secret:$name}"

/**
 * The first string in [element] (recursively) that a pull must not accept as is: either `${env:NAME}` (the local
 * spelling, which the wire never sends) or `${secret:NAME}` naming a `NAME` outside [syncedSecrets] (the account's
 * own secret names, as this run knows them). Null when every reference [element] holds is one of [syncedSecrets].
 */
public fun suspiciousSecretReference(element: JsonElement, syncedSecrets: Set<String>): String? {
    for (leaf in stringLeaves(element)) {
        if (ENV_REF.matches(leaf)) return leaf
        val name = SECRET_REF.matchEntire(leaf)?.groupValues?.get(1)
        if (name != null && name !in syncedSecrets) return leaf
    }
    return null
}

/** The names of [syncedSecrets] that [element] references as `${secret:NAME}`, in order, each once. */
public fun referencedSecrets(element: JsonElement, syncedSecrets: Set<String>): List<String> =
    stringLeaves(element).mapNotNull { SECRET_REF.matchEntire(it)?.groupValues?.get(1) }
        .filter { it in syncedSecrets }.distinct().toList()

/** [element] with every `${secret:NAME}` of [syncedSecrets] rewritten to the local `${env:NAME}` form, for a pull.
 * Call this only once [suspiciousSecretReference] has named no problem; it otherwise leaves a string as is. */
public fun toLocalSecretRefs(element: JsonElement, syncedSecrets: Set<String>): JsonElement =
    mapStringLeaves(element) { text ->
        SECRET_REF.matchEntire(text)?.groupValues?.get(1)?.takeIf { it in syncedSecrets }
            ?.let(::localSecretRef) ?: text
    }

/** [element] with every local `${env:NAME}` of [syncedSecrets] rewritten to the wire's `${secret:NAME}` form, for
 * a push. A reference to a secret this account does not sync is left as is; the service refuses it itself. */
public fun toWireSecretRefs(element: JsonElement, syncedSecrets: Set<String>): JsonElement =
    mapStringLeaves(element) { text ->
        ENV_REF.matchEntire(text)?.groupValues?.get(1)?.takeIf { it in syncedSecrets }
            ?.let(::wireSecretRef) ?: text
    }

private fun stringLeaves(element: JsonElement): Sequence<String> = when (element) {
    is JsonObject -> element.values.asSequence().flatMap(::stringLeaves)
    is JsonArray -> element.asSequence().flatMap(::stringLeaves)
    is JsonNull -> emptySequence()
    is JsonPrimitive -> if (element.isString) sequenceOf(element.content) else emptySequence()
}

private fun mapStringLeaves(element: JsonElement, transform: (String) -> String): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (_, v) -> mapStringLeaves(v, transform) })
    is JsonArray -> JsonArray(element.map { mapStringLeaves(it, transform) })
    is JsonNull -> element
    is JsonPrimitive -> if (element.isString) JsonPrimitive(transform(element.content)) else element
}
