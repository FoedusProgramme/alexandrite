package org.unitedsoftware.alexandrite.store.sqlite

import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

/** The id of the row of [address] in `chats`, which this adds when there is none. */
internal fun Tx.chatId(address: ChatAddress): Long =
    chatIdOrNull(address) ?: insert("INSERT INTO chats (address, created_at) VALUES (?, ?)", "$address", now)

internal fun Tx.chatIdOrNull(address: ChatAddress): Long? =
    queryOne("SELECT id FROM chats WHERE address = ?", "$address") { getLong(1) }
