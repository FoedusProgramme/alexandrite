package org.unitedsoftware.alexandrite.internal.text

/** Whether [url] starts with a scheme. */
public fun isAbsoluteUrl(url: String): Boolean = SCHEME.containsMatchIn(url)

/**
 * [reference] resolved against the absolute URL [base] by RFC 3986 (section 5.2), with no other normalization: neither
 * string is checked, so a URL with characters RFC 3986 does not allow resolves as well.
 */
public fun resolved(base: String, reference: String): String {
    val b = UrlParts.of(base)
    val r = UrlParts.of(reference)
    val target = when {
        r.scheme != null -> UrlParts(r.scheme, r.authority, withoutDotSegments(r.path), r.query, r.fragment)
        r.authority != null -> UrlParts(b.scheme, r.authority, withoutDotSegments(r.path), r.query, r.fragment)
        r.path.isEmpty() -> UrlParts(b.scheme, b.authority, b.path, r.query ?: b.query, r.fragment)
        r.path.startsWith("/") -> UrlParts(b.scheme, b.authority, withoutDotSegments(r.path), r.query, r.fragment)
        else -> UrlParts(b.scheme, b.authority, withoutDotSegments(merged(b, r.path)), r.query, r.fragment)
    }
    return target.toString()
}

private class UrlParts(
    val scheme: String?,
    val authority: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    override fun toString(): String = buildString {
        scheme?.let { append(it).append(':') }
        authority?.let { append("//").append(it) }
        append(path)
        query?.let { append('?').append(it) }
        fragment?.let { append('#').append(it) }
    }

    companion object {
        fun of(url: String): UrlParts {
            var rest = url
            val fragment = rest.indexOf('#').takeIf { it >= 0 }?.let { at ->
                rest.substring(at + 1).also { rest = rest.substring(0, at) }
            }
            val query = rest.indexOf('?').takeIf { it >= 0 }?.let { at ->
                rest.substring(at + 1).also { rest = rest.substring(0, at) }
            }
            val scheme = SCHEME.find(rest)?.value?.dropLast(1)?.also { rest = rest.substring(it.length + 1) }
            val authority = if (rest.startsWith("//")) {
                val end = rest.indexOf('/', 2).takeIf { it >= 0 } ?: rest.length
                rest.substring(2, end).also { rest = rest.substring(end) }
            } else {
                null
            }
            return UrlParts(scheme, authority, rest, query, fragment)
        }
    }
}

/** [reference]'s path appended to all of [base]'s path but its last segment (section 5.2.3). */
private fun merged(base: UrlParts, reference: String): String = when {
    base.authority != null && base.path.isEmpty() -> "/$reference"
    else -> base.path.substring(0, base.path.lastIndexOf('/') + 1) + reference
}

/** [path] with its `.` and `..` segments removed (section 5.2.4). */
private fun withoutDotSegments(path: String): String {
    var input = path
    val output = StringBuilder()
    while (input.isNotEmpty()) {
        when {
            input.startsWith("../") -> input = input.substring(3)

            input.startsWith("./") -> input = input.substring(2)

            input.startsWith("/./") -> input = input.substring(2)

            input == "/." -> input = "/"

            input.startsWith("/../") || input == "/.." -> {
                input = "/" + input.substring(if (input == "/..") 3 else 4)
                output.setLength(output.lastIndexOf("/").coerceAtLeast(0))
            }

            input == "." || input == ".." -> input = ""

            else -> {
                val end = input.indexOf('/', 1).takeIf { it >= 0 } ?: input.length
                output.append(input, 0, end)
                input = input.substring(end)
            }
        }
    }
    return output.toString()
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
