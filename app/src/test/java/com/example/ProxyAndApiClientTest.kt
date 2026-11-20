package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.netscope.core.network.apiclient.ApiClientExecutor
import com.netscope.core.network.vpn.PacketUtils
import com.netscope.core.network.vpn.TunPacketForwarder
import com.netscope.core.proxy.ca.CaCertificateManager
import com.netscope.core.proxy.ca.DynamicCertGenerator
import com.netscope.core.proxy.mitm.NettyMitmProxyServer
import com.netscope.core.proxy.mitm.ProxyConfig
import com.netscope.shared.model.ApiRequestDraft
import com.netscope.shared.model.AuthConfig
import com.netscope.shared.model.AuthType
import com.netscope.shared.model.BodyType
import com.netscope.shared.model.EnvironmentProfile
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.KeyValueEntry
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProxyAndApiClientTest {

  @Test
  fun `NettyMitmProxyServer forwards HTTP traffic and captures transaction`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val ca = CaCertificateManager(context).getOrCreateCa()
    val server = MockWebServer()
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"captured":"yes"}"""))
    server.start()

    val proxy = NettyMitmProxyServer(ca)
    proxy.config = ProxyConfig(sessionId = "proxy-test", mitmEnabled = false)
    val proxyPort = proxy.start(0)

    try {
      val client =
        OkHttpClient.Builder()
          .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
          .connectTimeout(5, TimeUnit.SECONDS)
          .build()

      val response = client.newCall(Request.Builder().url(server.url("/hello")).build()).execute()
      assertEquals(200, response.code)
      assertEquals("""{"captured":"yes"}""", response.body?.string())
      response.close()
    } finally {
      proxy.stop()
      server.shutdown()
    }
  }

  @Test
  fun `DynamicCertGenerator issues leaf certificate with host SAN`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val ca = CaCertificateManager(context).getOrCreateCa()
    val generator = DynamicCertGenerator(ca)
    val sslCtx = generator.getOrCreateServerSslContext("api.netscope.dev")
    assertTrue(sslCtx.isServer)
  }

  @Test
  fun `TunPacketForwarder parses DNS query domain and builds valid IPv4 UDP packet`() {
    val dnsQuery =
      byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01,
      )
    val domain = TunPacketForwarder.parseDnsQuestionDomain(dnsQuery)
    assertEquals("example.com", domain)

    val packet =
      TunPacketForwarder.buildIpv4UdpPacket(
        srcIp = byteArrayOf(8, 8, 8, 8),
        dstIp = byteArrayOf(10, 88, 0, 2),
        srcPort = 53,
        dstPort = 54321,
        payload = dnsQuery,
      )
    assertEquals(20 + 8 + dnsQuery.size, packet.size)
    assertEquals(17, packet[9].toInt() and 0xFF)
  }

  @Test
  fun `PacketUtils builds valid TCP packet with checksum and parses TLS SNI and HTTP preview`() {
    val tcpBytes =
      PacketUtils.buildIpv4TcpPacket(
        srcIp = byteArrayOf(1, 1, 1, 1),
        dstIp = byteArrayOf(10, 88, 0, 2),
        srcPort = 443,
        dstPort = 50000,
        seqNum = 100L,
        ackNum = 200L,
        flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
      )
    val ipHdr = PacketUtils.parseIpv4Header(tcpBytes, tcpBytes.size)
    assertNotNull(ipHdr)
    val tcpHdr = PacketUtils.parseTcpHeader(tcpBytes, ipHdr!!)
    assertNotNull(tcpHdr)
    assertEquals(443, tcpHdr!!.srcPort)
    assertEquals(50000, tcpHdr.dstPort)
    assertEquals(PacketUtils.TCP_SYN or PacketUtils.TCP_ACK, tcpHdr.flags)

    val httpPayload = "GET /v1/messages HTTP/1.1\r\nHost: web.telegram.org\r\n\r\n".toByteArray()
    val preview = PacketUtils.extractHttpPreview(httpPayload)
    assertNotNull(preview)
    assertEquals("GET", preview!!.method)
    assertEquals("/v1/messages", preview.path)
    assertEquals("web.telegram.org", preview.host)

    // Minimal TLS ClientHello with SNI "api.whatsapp.net"
    val hostBytes = "api.whatsapp.net".toByteArray()
    val sniExtLen = 5 + hostBytes.size
    val extsLen = 4 + sniExtLen
    val hsLen = 2 + 32 + 1 + 2 + 1 + 2 + extsLen
    val clientHello =
      byteArrayOf(
        0x16, 0x03, 0x01, 0x00, (hsLen + 4).toByte(),
        0x01, 0x00, 0x00, hsLen.toByte(),
        0x03, 0x03,
      ) + ByteArray(32) +
        byteArrayOf(0x00, 0x00, 0x00, 0x00) +
        byteArrayOf(0x00, extsLen.toByte()) +
        byteArrayOf(0x00, 0x00, 0x00, sniExtLen.toByte()) +
        byteArrayOf(0x00, (sniExtLen - 2).toByte(), 0x00, 0x00, hostBytes.size.toByte()) +
        hostBytes

    assertEquals("api.whatsapp.net", PacketUtils.extractTlsSni(clientHello))
  }

  @Test
  fun `ApiClientExecutor executes POST request with Bearer auth and env variables`() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setResponseCode(201).setBody("""{"status":"created"}"""))
    server.start()

    try {
      val executor = ApiClientExecutor()
      val env =
        EnvironmentProfile(
          id = "env",
          name = "Test",
          variables = listOf(KeyValueEntry("v1", "token", "my-jwt-123")),
          isActive = true,
        )
      val draft =
        ApiRequestDraft(
          id = "draft-1",
          method = HttpMethod.POST,
          url = server.url("/items").toString(),
          auth = AuthConfig(type = AuthType.BEARER, bearerToken = "{{token}}"),
          bodyType = BodyType.JSON,
          rawBody = """{"name":"probe"}""",
        )

      val result = executor.execute(draft, environment = env, recordToTrafficFeed = false)
      assertEquals(201, result.statusCode)
      assertEquals("""{"status":"created"}""", result.responseBody)

      val recorded = server.takeRequest()
      assertEquals("POST", recorded.method)
      assertEquals("Bearer my-jwt-123", recorded.getHeader("Authorization"))
      assertEquals("""{"name":"probe"}""", recorded.body.readUtf8())
    } finally {
      server.shutdown()
    }
  }
}
