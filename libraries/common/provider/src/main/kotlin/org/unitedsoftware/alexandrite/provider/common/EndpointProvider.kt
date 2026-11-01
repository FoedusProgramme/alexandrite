package org.unitedsoftware.alexandrite.provider.common

import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider

/** A provider of [endpoints], which it closes when it is destroyed. */
public abstract class EndpointProvider<T>(final override val endpoints: List<T>) :
    ModelProvider,
    Lifecycle where T : ModelEndpoint, T : AutoCloseable {
    override fun onDestroy() {
        endpoints.forEach(AutoCloseable::close)
    }
}
