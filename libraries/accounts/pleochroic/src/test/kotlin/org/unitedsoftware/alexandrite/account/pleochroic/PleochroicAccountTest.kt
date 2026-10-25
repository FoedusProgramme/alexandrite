package org.unitedsoftware.alexandrite.account.pleochroic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.internal.http.HttpTimeouts
import org.unitedsoftware.alexandrite.internal.http.HttpTransport
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun transport() = HttpTransport(HttpTimeouts(5.seconds, 5.seconds, 5.seconds))

class PleochroicAccountTest {
    @Test
    fun `beginDeviceLogin posts the client and reads the device code`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"deviceCode": "dc-1", "userCode": "ABCD-EFGH", "verificationUri": "https://x/activate",
                            "verificationUriComplete": "https://x/activate?code=ABCD-EFGH", "expiresIn": 600,
                            "interval": 5}""",
                    )
                },
                "/api/device/code",
            )
            transport().use { http ->
                val code = beginDeviceLogin(http, "alx", "1.0.0", "my-laptop", server.baseUrl)

                assertEquals("dc-1", code.deviceCode)
                assertEquals("ABCD-EFGH", code.userCode)
                assertEquals(600.seconds, code.expiresIn)
                assertEquals(5.seconds, code.interval)
                val request = server.requests.single()
                assertEquals("alx", request.json()["client"]?.let { (it as JsonPrimitive).content })
                assertEquals("my-laptop", request.json()["deviceName"]?.let { (it as JsonPrimitive).content })
            }
        }
    }

    @Test
    fun `beginDeviceLogin falls back to building verificationUriComplete when the portal names none`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"deviceCode": "dc-1", "userCode": "ABCD-EFGH", "verificationUri": "https://x/activate"}""",
                    )
                },
                "/api/device/code",
            )
            transport().use { http ->
                val code = beginDeviceLogin(http, "alx", "1.0.0", portalBaseUrl = server.baseUrl)

                assertEquals("https://x/activate?code=ABCD-EFGH", code.verificationUriComplete)
            }
        }
    }

    @Test
    fun `pollDeviceLogin maps authorization_pending to Pending`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(400) { body("""{"error": "authorization_pending"}""") }, "/api/device/token")
            transport().use { http ->
                val poll = pollDeviceLogin(http, "dc-1", server.baseUrl)

                assertEquals(DevicePoll.Pending, poll)
            }
        }
    }

    @Test
    fun `pollDeviceLogin maps slow_down to SlowDown`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(400) { body("""{"error": "slow_down"}""") }, "/api/device/token")
            transport().use { http ->
                assertEquals(DevicePoll.SlowDown, pollDeviceLogin(http, "dc-1", server.baseUrl))
            }
        }
    }

    @Test
    fun `pollDeviceLogin maps access_denied to Denied`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(400) { body("""{"error": "access_denied"}""") }, "/api/device/token")
            transport().use { http ->
                assertEquals(DevicePoll.Denied, pollDeviceLogin(http, "dc-1", server.baseUrl))
            }
        }
    }

    @Test
    fun `pollDeviceLogin maps expired_token to Expired`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(400) { body("""{"error": "expired_token"}""") }, "/api/device/token")
            transport().use { http ->
                assertEquals(DevicePoll.Expired, pollDeviceLogin(http, "dc-1", server.baseUrl))
            }
        }
    }

    @Test
    fun `pollDeviceLogin rethrows an error code it does not know as a PleochroicApiException`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(400) { body("""{"error": "server_error"}""") }, "/api/device/token")
            transport().use { http ->
                val e = assertFailsWithPleochroic { pollDeviceLogin(http, "dc-1", server.baseUrl) }
                assertEquals("server_error", e.code)
            }
        }
    }

    @Test
    fun `pollDeviceLogin reads a granted key and account with an object plan`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"key": "plc-abc", "apiBase": "https://api.pleochroic.co.uk/v1",
                            "account": {"id": "u-1", "username": "akane", "name": "Akane",
                            "plan": {"id": "pro", "name": "Pro"}}}""",
                    )
                },
                "/api/device/token",
            )
            transport().use { http ->
                val poll = pollDeviceLogin(http, "dc-1", server.baseUrl)

                val granted = assertIs<DevicePoll.Done>(poll).granted
                assertEquals("plc-abc", granted.key)
                assertEquals("https://api.pleochroic.co.uk/v1", granted.apiBase)
                assertEquals("Akane", granted.account.label)
                assertEquals("Pro" to "pro", granted.account.plan?.name to granted.account.plan?.id)
            }
        }
    }

    @Test
    fun `pollDeviceLogin reads a bare string plan from an older device grant`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"key": "plc-abc", "apiBase": "https://api.pleochroic.co.uk/v1",
                            "account": {"id": "u-1", "username": "akane", "plan": "pro"}}""",
                    )
                },
                "/api/device/token",
            )
            transport().use { http ->
                val granted = assertIs<DevicePoll.Done>(pollDeviceLogin(http, "dc-1", server.baseUrl)).granted

                assertEquals("pro", granted.account.plan?.label)
                assertNull(granted.account.name)
                assertEquals("akane", granted.account.label)
            }
        }
    }

    @Test
    fun `fetchServiceStatus sends a bearer token and entitled reads services enabled`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse {
                    body(
                        """{"account": {"id": "u-1", "name": "Akane", "plan": {"id": "pro", "name": "Pro"}},
                            "services": {"models": {"enabled": true}, "web": {"enabled": false}}}""",
                    )
                },
                "/service",
            )
            transport().use { http ->
                val service = fetchServiceStatus(http, server.baseUrl, "plc-key")

                assertEquals("Bearer plc-key", server.requests.single().header("authorization"))
                assertEquals("Akane", serviceAccount(service).label)
                assertTrue(service.entitled("models"))
                assertTrue(!service.entitled("web"))
                assertTrue(!service.entitled("unknown"))
            }
        }
    }

    @Test
    fun `revokeKey treats a 404 as already revoked`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(fakeResponse(404) { body("""{"error": "not_found"}""") }, "/key")
            transport().use { http -> revokeKey(http, server.baseUrl, "plc-key") }
        }
    }

    @Test
    fun `revokeKey throws the service's error code for anything else`() = runBlocking {
        FakeModelServer().use { server ->
            server.enqueue(
                fakeResponse(500) {
                    body("""{"error": {"code": "server_error", "message": "oops"}}""")
                },
                "/key",
            )
            transport().use { http ->
                val e = assertFailsWithPleochroic { revokeKey(http, server.baseUrl, "plc-key") }
                assertEquals("server_error" to "oops", e.code to e.message)
            }
        }
    }
}

private suspend inline fun assertFailsWithPleochroic(block: suspend () -> Unit): PleochroicApiException {
    try {
        block()
    } catch (e: PleochroicApiException) {
        return e
    }
    throw AssertionError("Expected a PleochroicApiException.")
}
