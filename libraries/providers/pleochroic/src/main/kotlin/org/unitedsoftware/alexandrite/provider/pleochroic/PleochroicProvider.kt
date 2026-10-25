package org.unitedsoftware.alexandrite.provider.pleochroic

import org.unitedsoftware.alexandrite.provider.common.EndpointProvider
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider

@Singleton
@Contribute(ModelProvider::class)
internal class PleochroicProvider(config: PleochroicConfig) :
    EndpointProvider<ChatEndpoint>(
        config.endpoints.map { (id, endpoint) ->
            ChatEndpoint(id, endpoint.settings, PLEOCHROIC_FLAVOR)
        },
    )
