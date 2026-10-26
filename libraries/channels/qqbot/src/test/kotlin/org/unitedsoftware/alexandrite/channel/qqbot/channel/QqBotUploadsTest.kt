package org.unitedsoftware.alexandrite.channel.qqbot.channel

import kotlinx.coroutines.runBlocking
import org.unitedsoftware.alexandrite.channel.qqbot.auth.QqBotTokens
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotApi
import org.unitedsoftware.alexandrite.channel.qqbot.net.QqBotHttp
import org.unitedsoftware.alexandrite.channel.qqbot.testing.FakeApiServer
import org.unitedsoftware.alexandrite.channel.qqbot.testing.MutableClock
import org.unitedsoftware.alexandrite.channel.qqbot.testing.testConfig
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelType
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QqBotUploadsTest {
    private val instance = ChannelInstanceId(ChannelType("qqbot"), "main")
    private val direct = ChatAddress(instance, "private:u1")
    private val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))

    @Test
    fun `a small file goes as base64 in the upload call`() = runBlocking {
        val platform = Platform()
        platform.server.on(FILES, """{"file_info":"FI","ttl":300}""")
        try {
            val file = OutboundFile.builder("a.png", "image/png", "hello".toByteArray()).build()

            val uploaded = assertIs<QqBotUpload.Uploaded>(platform.uploads.upload(direct, file))

            assertEquals("FI", uploaded.fileInfo)
            val body = platform.server.calls(FILES).single().body
            assertTrue("\"file_type\":1" in body, body)
            assertTrue("\"file_name\":\"a.png\"" in body, body)
            assertTrue("\"file_data\":\"aGVsbG8=\"" in body, body)
        } finally {
            platform.close()
        }
    }

    @Test
    fun `a large file goes in blocks and the platform merges them`() = runBlocking {
        val platform = Platform()
        platform.server.on(PREPARE) {
            val base = platform.server.base
            org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer(
                200,
                """{"upload_id":"up","block_size":"3145728","upload_config":{"concurrency":2},"parts":[""" +
                    """{"index":0,"presigned_url":"$base/upload/0","block_size":"3145728"},""" +
                    """{"index":1,"presigned_url":"$base/upload/1","block_size":"3145728"}]}""",
            )
        }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        for (path in listOf("/upload/0", "/upload/1")) {
            platform.server.on(path) {
                val now = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                Thread.sleep(80)
                active.decrementAndGet()
                org.unitedsoftware.alexandrite.channel.qqbot.testing.PlatformAnswer(200, "{}")
            }
        }
        platform.server.on(PART_FINISH, "{}")
        platform.server.on(FILES, """{"file_info":"FI2"}""")
        try {
            val bytes = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
            val file = OutboundFile.builder("big.bin", "application/octet-stream", bytes).build()

            val uploaded = assertIs<QqBotUpload.Uploaded>(platform.uploads.upload(direct, file))

            assertEquals("FI2", uploaded.fileInfo)
            val prepare = platform.server.calls(PREPARE).single()
            assertTrue("\"file_size\":\"6291456\"" in prepare.body, prepare.body)
            assertTrue("\"md5\":" in prepare.body, prepare.body)
            assertTrue("\"sha1\":" in prepare.body, prepare.body)
            assertTrue("\"md5_10m\":" in prepare.body, prepare.body)
            val puts = platform.server.calls("/upload/0") + platform.server.calls("/upload/1")
            assertEquals(2, puts.size)
            assertTrue(puts.all { it.method == "PUT" }, puts.map { it.method }.toString())
            assertEquals(2, platform.server.calls(PART_FINISH).size)
            assertTrue("\"upload_id\":\"up\"" in platform.server.calls(FILES).single().body)
            assertTrue(peak.get() >= 2, "the two blocks were not uploaded at the same time: peak ${peak.get()}")
        } finally {
            platform.close()
        }
    }

    /** Uploads that talk to a fake platform, which keeps what it read. */
    private inner class Platform : AutoCloseable {
        val server = FakeApiServer()
        private val http: QqBotHttp
        val uploads: QqBotUploads

        init {
            server.on(TOKEN_PATH, """{"access_token":"access","expires_in":"7200"}""")
            val config = testConfig(server.base)
            http = QqBotHttp(config)
            val tokens = QqBotTokens(config, http, clock)
            uploads = QqBotUploads(QqBotApi(http, tokens), config)
        }

        override fun close() {
            http.close()
            server.close()
        }
    }

    private companion object {
        const val TOKEN_PATH = "/app/getAppAccessToken"
        const val FILES = "/v2/users/u1/files"
        const val PREPARE = "/v2/users/u1/upload_prepare"
        const val PART_FINISH = "/v2/users/u1/upload_part_finish"
    }
}
