package org.unitedsoftware.alexandrite.runtime.plugin

import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import kotlin.reflect.KClass

/** Whether this is a single, unqualified binding of [port]. */
internal fun Binding<*>.binds(port: KClass<*>): Boolean = !multi && key.qualifier == null && key.type.classifier == port
