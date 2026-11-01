package org.unitedsoftware.alexandrite.internal.io

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/** The text of [file] in UTF-8, throwing a [CharacterCodingException] where it is no UTF-8. */
public fun readUtf8(file: Path): String =
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
