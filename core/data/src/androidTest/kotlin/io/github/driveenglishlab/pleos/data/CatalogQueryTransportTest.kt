package io.github.driveenglishlab.pleos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Ktor/OkHttp request bytes against a test-owned loopback socket. No product identity,
 * Auth, external server, catalog content, provider or vehicle state is used. */
@RunWith(AndroidJUnit4::class)
class CatalogQueryTransportTest {
    private suspend fun captureCatalogTarget(topicsJson: String): String = coroutineScope {
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            server.soTimeout = 5_000
            val request = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val line = requireNotNull(reader.readLine())
                    require(line.length <= 4_096)
                    var count = 0
                    while (true) {
                        val header = requireNotNull(reader.readLine())
                        require(header.length <= 8_192 && ++count <= 64)
                        if (header.isEmpty()) break
                        assertFalse("Fixture request must not carry credentials.", header.startsWith("Authorization:", true))
                    }
                    val parts = line.split(' ')
                    assertEquals(3, parts.size)
                    assertEquals("GET", parts[0])
                    assertEquals("HTTP/1.1", parts[2])
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    parts[1]
                }
            }
            KtorLearningTransport("http://127.0.0.1:${server.localPort}", allowLocalHttp = true).use { transport ->
                withTimeout(8_000) {
                    val response = transport.exchange("GET", "/v2/content/catalog",
                        linkedMapOf("topics" to topicsJson, "limit" to "20"), emptyMap(), null)
                    assertEquals(200, response.status)
                    assertEquals("{}", response.body.toString(Charsets.UTF_8))
                    request.await()
                }
            }
        }
    }

    private fun queryPairs(target: String): List<Pair<String, String>> {
        val uri = URI(target)
        assertEquals("/v2/content/catalog", uri.rawPath)
        assertNull(uri.rawFragment)
        return uri.rawQuery?.split('&')?.map { entry ->
            val pair = entry.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        } ?: emptyList()
    }

    @Test fun emptyTopicsAreOmittedEntirelyFromActualRequestUri() = runBlocking {
        val target = captureCatalogTarget("[]")
        assertEquals(listOf("limit" to "20"), queryPairs(target))
        assertFalse(target.contains("topics"))
    }

    @Test fun twoTopicsUseExactlyTwoRepeatedKeysInActualRequestUri() = runBlocking {
        val target = captureCatalogTarget("[\"technology\",\"science\"]")
        assertEquals(listOf("topics" to "technology", "topics" to "science", "limit" to "20"), queryPairs(target))
    }
}
