package org.unitedsoftware.alexandrite.http.common

import java.net.URI
import java.net.URISyntaxException

/** The redirects a plugin follows at most for one request. */
public const val MAX_REDIRECTS: Int = 5

/** Where [location] leads from [url], null when it is no address or leaves https for http. */
public fun redirectTarget(url: String, location: String): String? {
    val next = try {
        URI(url).resolve(location)
    } catch (e: URISyntaxException) {
        return null
    } catch (e: IllegalArgumentException) {
        return null
    }
    return next.toString().takeUnless { url.startsWith("https:") && next.scheme == "http" }
}
