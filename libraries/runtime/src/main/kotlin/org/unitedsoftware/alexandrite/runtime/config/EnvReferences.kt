package org.unitedsoftware.alexandrite.runtime.config

import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi

/** The `${env:NAME}` references by which a config file's string values insert environment variables. */
@InternalAlexandriteApi
public object EnvReferences {
    /** What a variable's name may be. */
    public val NAME: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** How a value that starts a reference it does not finish is fixed. */
    public const val HOW_TO_WRITE: String =
        "Write \${env:NAME} to insert an environment variable, or \$\${ for a literal \${."

    private val REFERENCE = Regex("\\$\\{env:(${NAME.pattern})\\}")

    /** The reference to the variable [name]. */
    public fun of(name: String): String = "\${env:$name}"

    /** The variable that [text] names when it is one whole reference, null otherwise. */
    public fun whole(text: String): String? = REFERENCE.matchEntire(text)?.groupValues?.get(1)

    /** The variables that the references in [text] name, in order. */
    public fun all(text: String): Sequence<String> = REFERENCE.findAll(text).map { it.groupValues[1] }
}
