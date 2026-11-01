package org.unitedsoftware.alexandrite.internal.path

import java.nio.file.Path
import java.nio.file.Paths

/** [entry] as an absolute normalized path, a relative one resolved against [base], null when there is none. */
public fun resolveConfigured(entry: String, base: Path?): Path? {
    val path = Paths.get(entry)
    if (path.isAbsolute) return path.normalize()
    return base?.resolve(path)?.normalize()
}
