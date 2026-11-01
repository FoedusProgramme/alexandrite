package org.unitedsoftware.alexandrite.internal.portable

/** Marks a declaration that uses JDK API Android lacks, reached only once [JdkFeatures] has found that API. */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.FIELD,
)
public annotation class JdkOnly
