package org.unitedsoftware.alexandrite.provider.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One endpoint of this provider read from [json]. */
public fun <O> EndpointConfigSerializer<O>.read(json: String): EndpointConfig<O> = Json.decodeFromString(this, json)

/** One endpoint of this provider read from [json]. */
public fun <O> EndpointConfigSerializer<O>.read(json: JsonObject): EndpointConfig<O> =
    Json.decodeFromJsonElement(this, json)
