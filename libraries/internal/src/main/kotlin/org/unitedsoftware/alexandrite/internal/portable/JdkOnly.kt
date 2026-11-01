package org.unitedsoftware.alexandrite.internal.portable

/**
 * Marks a declaration that reaches JDK API Android lacks, which the portability check (`alexandrite.portable`) lets
 * through. Each one is reached only where [JdkFeatures] (or a catch of the [LinkageError] a missing class or member
 * throws) has shown the API is there, or is an SDK port's JDK default that the runtime binds only then.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FIELD,
)
public annotation class JdkOnly
