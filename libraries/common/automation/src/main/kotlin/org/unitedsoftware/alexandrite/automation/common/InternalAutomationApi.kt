package org.unitedsoftware.alexandrite.automation.common

/** Marks automation-common API that only Alexandrite's own automation plugins and hosts may use. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal automation API: only Alexandrite's own automation plugins and hosts may use it.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class InternalAutomationApi
