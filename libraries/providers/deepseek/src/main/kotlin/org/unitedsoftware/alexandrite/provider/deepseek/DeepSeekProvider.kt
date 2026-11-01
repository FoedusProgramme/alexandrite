package org.unitedsoftware.alexandrite.provider.deepseek

import org.unitedsoftware.alexandrite.provider.common.EndpointProvider
import org.unitedsoftware.alexandrite.provider.common.chat.ChatEndpoint
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.net.HttpEngine

@Singleton
@Contribute(ModelProvider::class)
internal class DeepSeekProvider(config: DeepSeekConfig, engine: HttpEngine) :
    EndpointProvider<ChatEndpoint>(
        config.endpoints.map { (id, endpoint) ->
            ChatEndpoint(id, endpoint.settings, engine, DEEPSEEK_FLAVOR)
        },
    )
