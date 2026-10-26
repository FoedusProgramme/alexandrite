package org.unitedsoftware.alexandrite.provider.pleochroic

import kotlinx.serialization.Serializable
import org.unitedsoftware.alexandrite.provider.common.EndpointConfig
import org.unitedsoftware.alexandrite.provider.common.EndpointConfigSerializer
import org.unitedsoftware.alexandrite.provider.common.NoOptions
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId

/** The gateway's model relay (`standalone-service` API, "模型中转"), also the default of a self-hosted service. */
internal const val PLEOCHROIC_BASE_URL: String = "https://api.pleochroic.co.uk/v1"

@ConfigSection
@Serializable
internal class PleochroicConfig(
    /** The endpoints by their ids; `alexandrite pleochroic login` writes the signed-in one. */
    val endpoints: Map<
        EndpointId,
        @Serializable(with = EndpointSerializer::class)
        EndpointConfig<NoOptions>,
        > =
        emptyMap(),
)

internal object EndpointSerializer : EndpointConfigSerializer<NoOptions>(
    "org.unitedsoftware.alexandrite.provider.pleochroic.EndpointConfig",
    NoOptions.serializer(),
    baseUrl = PLEOCHROIC_BASE_URL,
    keyRequired = true,
)
