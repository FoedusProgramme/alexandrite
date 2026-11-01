package org.unitedsoftware.alexandrite.sdk.net

/**
 * HTTP header fields, compared and looked up ignoring the case of their names, each with its values in order. Its
 * string form leaves the values out.
 */
public class HttpHeaders private constructor(private val fields: Map<String, List<String>>) {
    private val byLowerName: Map<String, List<String>> = fields.mapKeys { it.key.lowercase() }

    /** The names as first given. */
    public val names: Set<String> get() = fields.keys

    /** The first value of [name], null when there is none. */
    public operator fun get(name: String): String? = byLowerName[name.lowercase()]?.first()

    public fun all(name: String): List<String> = byLowerName[name.lowercase()].orEmpty()

    public fun isEmpty(): Boolean = fields.isEmpty()

    public fun toMap(): Map<String, List<String>> = fields

    override fun equals(other: Any?): Boolean = other is HttpHeaders && other.byLowerName == byLowerName

    override fun hashCode(): Int = byLowerName.hashCode()

    override fun toString(): String = "HttpHeaders(${fields.keys.joinToString()})"

    public companion object {
        public val EMPTY: HttpHeaders = HttpHeaders(emptyMap())

        /** Whether [value] holds only visible ASCII, spaces and tabs. */
        public fun isValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

        /** [fields] with the names that differ only in case merged under the first of them. */
        public fun of(fields: Map<String, List<String>>): HttpHeaders {
            val merged = LinkedHashMap<String, MutableList<String>>()
            val spelled = HashMap<String, String>()
            for ((name, values) in fields) {
                if (values.isEmpty()) continue
                merged.getOrPut(spelled.getOrPut(name.lowercase()) { name }) { mutableListOf() } += values
            }
            return HttpHeaders(merged.mapValues { it.value.toList() })
        }

        public fun of(vararg fields: Pair<String, String>): HttpHeaders = of(
            LinkedHashMap<String, MutableList<String>>().apply {
                for ((name, value) in fields) getOrPut(name) { mutableListOf() } += value
            },
        )
    }
}
