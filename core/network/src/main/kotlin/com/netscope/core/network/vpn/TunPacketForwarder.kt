package com.netscope.core.network.vpn

import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.TimingMetrics
import com.netscope.shared.model.TlsStatus
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class PacketStats(
  val packetsProcessed: Long,
  val bytesIn: Long,
  val bytesOut: Long,
)

private data class FlowKey(
  val protocol: Int,
  val srcIp: String,
  val srcPort: Int,
  val dstIp: String,
  val dstPort: Int,
)

class TunPacketForwarder(
  private val sessionId: String,
  private val protectTcpSocket: (Socket) -> Boolean,
  private val protectUdpSocket: (DatagramSocket) -> Boolean,
  private val appUidResolver: AppUidResolver? = null,
  private val onTransactionObserved: (CapturedTransaction) -> Unit,
  private val onStatsUpdated: (PacketStats) -> Unit,
) {
  private val running = AtomicBoolean(false)
  private val packetCounter = AtomicLong(0L)
  private val bytesInCounter = AtomicLong(0L)
  private val bytesOutCounter = AtomicLong(0L)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val writeLock = Any()

  private val tcpSessions = ConcurrentHashMap<FlowKey, TcpNatSession>()
  private val udpSessions = ConcurrentHashMap<FlowKey, UdpNatSession>()
  private val dnsIpCache = ConcurrentHashMap<String, String>()

  fun startLoop(input: FileInputStream, output: FileOutputStream) {
    running.set(true)
    val buffer = ByteArray(32767)
    var lastStatsEmitMs = 0L

    while (running.get()) {
      val len =
        try {
          input.read(buffer)
        } catch (_: Exception) {
          break
        }
      if (len <= 0) continue

      packetCounter.incrementAndGet()
      bytesOutCounter.addAndGet(len.toLong())

      val ip = PacketUtils.parseIpv4Header(buffer, len)
      if (ip != null) {
        when (ip.protocol) {
          PacketUtils.PROTO_UDP -> handleUdpPacket(buffer, ip, output)
          PacketUtils.PROTO_TCP -> handleTcpPacket(buffer, ip, output)
        }
      }

      val now = System.currentTimeMillis()
      if (now - lastStatsEmitMs > 1000L) {
        lastStatsEmitMs = now
        onStatsUpdated(PacketStats(packetCounter.get(), bytesInCounter.get(), bytesOutCounter.get()))
      }
    }
  }

  fun stop() {
    running.set(false)
    tcpSessions.values.forEach { it.close() }
    tcpSessions.clear()
    udpSessions.values.forEach { it.close() }
    udpSessions.clear()
    scope.cancel()
  }

  private fun writeTunPacket(output: FileOutputStream, packet: ByteArray) {
    if (!running.get()) return
    synchronized(writeLock) {
      runCatching { output.write(packet) }
    }
  }

  private fun handleUdpPacket(
    rawPacket: ByteArray,
    ip: PacketUtils.Ipv4Header,
    output: FileOutputStream,
  ) {
    val udp = PacketUtils.parseUdpHeader(rawPacket, ip) ?: return
    val payload =
      if (udp.payloadLength > 0) {
        rawPacket.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
      } else {
        ByteArray(0)
      }

    val srcIpStr = ip.srcAddress.hostAddress ?: return
    val dstIpStr = ip.dstAddress.hostAddress ?: return
    val key = FlowKey(PacketUtils.PROTO_UDP, srcIpStr, udp.srcPort, dstIpStr, udp.dstPort)

    val session =
      udpSessions.computeIfAbsent(key) {
        val owner =
          appUidResolver?.resolveOwner(
            PacketUtils.PROTO_UDP,
            ip.srcAddress,
            udp.srcPort,
            ip.dstAddress,
            udp.dstPort,
          ) ?: AppOwnerInfo("android.device", "Device Traffic")
        UdpNatSession(
            key = key,
            clientIp = ip.srcIp,
            serverIp = ip.dstIp,
            clientPort = udp.srcPort,
            serverPort = udp.dstPort,
            owner = owner,
            output = output,
          )
          .also { it.start() }
      }
    session.send(payload)
  }

  private fun handleTcpPacket(
    rawPacket: ByteArray,
    ip: PacketUtils.Ipv4Header,
    output: FileOutputStream,
  ) {
    val tcp = PacketUtils.parseTcpHeader(rawPacket, ip) ?: return
    val srcIpStr = ip.srcAddress.hostAddress ?: return
    val dstIpStr = ip.dstAddress.hostAddress ?: return
    val key = FlowKey(PacketUtils.PROTO_TCP, srcIpStr, tcp.srcPort, dstIpStr, tcp.dstPort)

    val isSyn = (tcp.flags and PacketUtils.TCP_SYN) != 0 && (tcp.flags and PacketUtils.TCP_ACK) == 0
    val isRst = (tcp.flags and PacketUtils.TCP_RST) != 0

    if (isRst) {
      tcpSessions.remove(key)?.close()
      return
    }

    if (isSyn) {
      tcpSessions.remove(key)?.close()
      val owner =
        appUidResolver?.resolveOwner(
          PacketUtils.PROTO_TCP,
          ip.srcAddress,
          tcp.srcPort,
          ip.dstAddress,
          tcp.dstPort,
        ) ?: AppOwnerInfo("android.device", "Device Traffic")
      val session =
        TcpNatSession(
          key = key,
          clientIp = ip.srcIp,
          serverIp = ip.dstIp,
          clientPort = tcp.srcPort,
          serverPort = tcp.dstPort,
          initialClientSeq = tcp.seqNum,
          owner = owner,
          output = output,
        )
      tcpSessions[key] = session
      session.connectAsync()
      return
    }

    val existing = tcpSessions[key]
    if (existing == null) {
      // Unknown flow (e.g. pre-existing socket before VPN started) -> send RST so client reconnects cleanly
      if ((tcp.flags and PacketUtils.TCP_ACK) != 0) {
        val rst =
          PacketUtils.buildIpv4TcpPacket(
            srcIp = ip.dstIp,
            dstIp = ip.srcIp,
            srcPort = tcp.dstPort,
            dstPort = tcp.srcPort,
            seqNum = tcp.ackNum,
            ackNum = 0L,
            flags = PacketUtils.TCP_RST,
          )
        writeTunPacket(output, rst)
      }
      return
    }

    val payload =
      if (tcp.payloadLength > 0) {
        rawPacket.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
      } else {
        ByteArray(0)
      }
    existing.onClientPacket(tcp, payload)
  }

  private inner class UdpNatSession(
    private val key: FlowKey,
    private val clientIp: ByteArray,
    private val serverIp: ByteArray,
    private val clientPort: Int,
    private val serverPort: Int,
    private val owner: AppOwnerInfo,
    private val output: FileOutputStream,
  ) {
    private val socket = DatagramSocket()
    private val active = AtomicBoolean(true)
    private val startedAtMs = System.currentTimeMillis()
    private val txId = UUID.randomUUID().toString()
    private val totalOut = AtomicLong(0L)
    private val totalIn = AtomicLong(0L)
    private var dnsDomain: String? = null
    private var logged = AtomicBoolean(false)
    private var readerJob: Job? = null

    fun start() {
      protectUdpSocket(socket)
      socket.soTimeout = if (serverPort == 53) 5000 else 30_000
      readerJob =
        scope.launch {
          val buf = ByteArray(4096)
          val pkt = DatagramPacket(buf, buf.size)
          while (active.get() && running.get()) {
            try {
              socket.receive(pkt)
              val data = buf.copyOf(pkt.length)
              totalIn.addAndGet(data.size.toLong())
              bytesInCounter.addAndGet(data.size.toLong())

              if (serverPort == 53) {
                val qDomain = dnsDomain ?: PacketUtils.parseDnsQuestionDomain(data)
                if (qDomain != null) {
                  PacketUtils.extractDnsAnswerIps(data).forEach { resolvedIp ->
                    dnsIpCache[resolvedIp] = qDomain
                  }
                }
              }

              val tunPacket =
                PacketUtils.buildIpv4UdpPacket(
                  srcIp = serverIp,
                  dstIp = clientIp,
                  srcPort = serverPort,
                  dstPort = clientPort,
                  payload = data,
                )
              writeTunPacket(output, tunPacket)

              if (serverPort == 53 && logged.compareAndSet(false, true)) {
                emitUdpTransaction(statusCode = 200, statusMsg = "NOERROR")
              }
            } catch (_: Exception) {
              break
            }
          }
          if (!logged.get() && totalOut.get() > 0L) {
            emitUdpTransaction(statusCode = 200, statusMsg = "UDP Flow")
          }
          close()
          udpSessions.remove(key)
        }
    }

    fun send(payload: ByteArray) {
      if (!active.get()) return
      totalOut.addAndGet(payload.size.toLong())
      if (serverPort == 53 && dnsDomain == null) {
        dnsDomain = PacketUtils.parseDnsQuestionDomain(payload)
      }
      scope.launch {
        runCatching {
          val dst = InetAddress.getByAddress(serverIp)
          socket.send(DatagramPacket(payload, payload.size, dst, serverPort))
        }
      }
    }

    private fun emitUdpTransaction(statusCode: Int, statusMsg: String) {
      val elapsed = System.currentTimeMillis() - startedAtMs
      val hostLabel =
        dnsDomain ?: dnsIpCache[key.dstIp] ?: key.dstIp
      val isDns = serverPort == 53
      val isQuic = serverPort == 443
      onTransactionObserved(
        CapturedTransaction(
          id = txId,
          sessionId = sessionId,
          timestampEpochMs = startedAtMs,
          appPackage = owner.packageName,
          appName = owner.appName,
          method = HttpMethod.GET,
          scheme = if (isDns) "dns" else if (isQuic) "quic" else "udp",
          host = hostLabel,
          port = serverPort,
          path = if (isDns) "/dns-query" else "/udp:$serverPort",
          fullUrl =
            when {
              isDns -> "dns://$hostLabel"
              isQuic -> "quic://$hostLabel:$serverPort"
              else -> "udp://$hostLabel:$serverPort"
            },
          protocol =
            when {
              isDns -> "UDP/DNS"
              isQuic -> "QUIC/UDP"
              else -> "UDP"
            },
          statusCode = statusCode,
          statusMessage = statusMsg,
          tlsStatus = if (isDns) TlsStatus.DNS else TlsStatus.PASSTHROUGH_PINNED,
          requestSizeBytes = totalOut.get(),
          responseSizeBytes = totalIn.get(),
          timings = TimingMetrics(dnsMs = if (isDns) elapsed else 0L, totalMs = elapsed),
        )
      )
    }

    fun close() {
      if (active.compareAndSet(true, false)) {
        runCatching { socket.close() }
        readerJob?.cancel()
      }
    }
  }

  private inner class TcpNatSession(
    private val key: FlowKey,
    private val clientIp: ByteArray,
    private val serverIp: ByteArray,
    private val clientPort: Int,
    private val serverPort: Int,
    initialClientSeq: Long,
    private val owner: AppOwnerInfo,
    private val output: FileOutputStream,
  ) {
    private val stateLock = Any()
    private val active = AtomicBoolean(true)
    private val txId = UUID.randomUUID().toString()
    private val startedAtMs = System.currentTimeMillis()
    private val totalOut = AtomicLong(0L)
    private val totalIn = AtomicLong(0L)

    private var serverSeq: Long = Random.nextInt(1000, Int.MAX_VALUE).toLong() and 0xFFFFFFFFL
    private var expectedClientSeq: Long = (initialClientSeq + 1L) and 0xFFFFFFFFL
    private var connected = false
    private var finSent = false
    private var closedReported = false

    private var socket: Socket? = null
    private var detectedHost: String? = dnsIpCache[key.dstIp]
    private var detectedMethod: HttpMethod = HttpMethod.HEAD
    private var detectedPath: String = "/tcp:$serverPort"
    private var detectedScheme: String = if (serverPort == 443) "https" else "tcp"
    private var detectedProtocol: String = if (serverPort == 443) "TLS/TCP" else "TCP"
    private var detectedTlsStatus: TlsStatus =
      if (serverPort == 443) TlsStatus.PASSTHROUGH_PINNED else TlsStatus.CLEARTEXT

    fun connectAsync() {
      scope.launch {
        val sock = Socket()
        try {
          protectTcpSocket(sock)
          sock.tcpNoDelay = true
          sock.soTimeout = 60_000
          sock.connect(InetSocketAddress(InetAddress.getByAddress(serverIp), serverPort), 8000)
          socket = sock

          synchronized(stateLock) {
            if (!active.get()) {
              runCatching { sock.close() }
              return@launch
            }
            connected = true
            val synAck =
              PacketUtils.buildIpv4TcpPacket(
                srcIp = serverIp,
                dstIp = clientIp,
                srcPort = serverPort,
                dstPort = clientPort,
                seqNum = serverSeq,
                ackNum = expectedClientSeq,
                flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
              )
            serverSeq = (serverSeq + 1L) and 0xFFFFFFFFL
            writeTunPacket(output, synAck)
          }

          readUpstreamLoop(sock)
        } catch (_: Exception) {
          synchronized(stateLock) {
            if (!connected && active.get()) {
              val rstAck =
                PacketUtils.buildIpv4TcpPacket(
                  srcIp = serverIp,
                  dstIp = clientIp,
                  srcPort = serverPort,
                  dstPort = clientPort,
                  seqNum = 0L,
                  ackNum = expectedClientSeq,
                  flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK,
                )
              writeTunPacket(output, rstAck)
            }
          }
          closeAndFinalize(statusCode = 0, statusMsg = "Connection Closed")
        }
      }
    }

    fun onClientPacket(tcp: PacketUtils.TcpHeader, payload: ByteArray) {
      val sock = socket
      val isFin = (tcp.flags and PacketUtils.TCP_FIN) != 0

      synchronized(stateLock) {
        if (!active.get() || !connected || sock == null) return

        if (payload.isNotEmpty()) {
          if (tcp.seqNum == expectedClientSeq) {
            expectedClientSeq = (expectedClientSeq + payload.size) and 0xFFFFFFFFL
            totalOut.addAndGet(payload.size.toLong())

            if (totalOut.get() == payload.size.toLong()) {
              inspectInitialPayload(payload)
            }

            scope.launch {
              val writeOk =
                runCatching {
                    sock.getOutputStream().write(payload)
                    sock.getOutputStream().flush()
                    true
                  }
                  .getOrDefault(false)
              if (!writeOk) {
                closeAndFinalize(200, "Stream Closed")
              }
            }

            val ackPkt =
              PacketUtils.buildIpv4TcpPacket(
                srcIp = serverIp,
                dstIp = clientIp,
                srcPort = serverPort,
                dstPort = clientPort,
                seqNum = serverSeq,
                ackNum = expectedClientSeq,
                flags = PacketUtils.TCP_ACK,
              )
            writeTunPacket(output, ackPkt)
          } else {
            // Re-ACK expected sequence to guide retransmission cleanly
            val dupAck =
              PacketUtils.buildIpv4TcpPacket(
                srcIp = serverIp,
                dstIp = clientIp,
                srcPort = serverPort,
                dstPort = clientPort,
                seqNum = serverSeq,
                ackNum = expectedClientSeq,
                flags = PacketUtils.TCP_ACK,
              )
            writeTunPacket(output, dupAck)
          }
        }

        if (isFin) {
          expectedClientSeq = (expectedClientSeq + 1L) and 0xFFFFFFFFL
          val finFlags =
            if (!finSent) {
              finSent = true
              PacketUtils.TCP_FIN or PacketUtils.TCP_ACK
            } else {
              PacketUtils.TCP_ACK
            }
          val finAck =
            PacketUtils.buildIpv4TcpPacket(
              srcIp = serverIp,
              dstIp = clientIp,
              srcPort = serverPort,
              dstPort = clientPort,
              seqNum = serverSeq,
              ackNum = expectedClientSeq,
              flags = finFlags,
            )
          if ((finFlags and PacketUtils.TCP_FIN) != 0) {
            serverSeq = (serverSeq + 1L) and 0xFFFFFFFFL
          }
          writeTunPacket(output, finAck)
          closeAndFinalize(200, "Completed")
        }
      }
    }

    private fun inspectInitialPayload(payload: ByteArray) {
      val sni = PacketUtils.extractTlsSni(payload)
      if (sni != null) {
        detectedHost = sni
        detectedScheme = "https"
        detectedPath = "/[TLS-Stream]"
        detectedProtocol = "TLS/TCP"
        detectedTlsStatus = TlsStatus.PASSTHROUGH_PINNED
        return
      }
      val http = PacketUtils.extractHttpPreview(payload)
      if (http != null) {
        detectedMethod = HttpMethod.fromString(http.method)
        detectedPath = http.path
        if (!http.host.isNullOrBlank()) {
          detectedHost = http.host.substringBefore(':')
        }
        detectedScheme = "http"
        detectedProtocol = "HTTP/1.1"
        detectedTlsStatus = TlsStatus.CLEARTEXT
      }
    }

    private fun readUpstreamLoop(sock: Socket) {
      val buf = ByteArray(1360)
      val input = sock.getInputStream()
      while (active.get() && running.get()) {
        val read =
          try {
            input.read(buf)
          } catch (_: Exception) {
            -1
          }
        if (read <= 0) break

        totalIn.addAndGet(read.toLong())
        bytesInCounter.addAndGet(read.toLong())

        synchronized(stateLock) {
          if (!active.get()) break
          val dataPkt =
            PacketUtils.buildIpv4TcpPacket(
              srcIp = serverIp,
              dstIp = clientIp,
              srcPort = serverPort,
              dstPort = clientPort,
              seqNum = serverSeq,
              ackNum = expectedClientSeq,
              flags = PacketUtils.TCP_PSH or PacketUtils.TCP_ACK,
              payload = buf,
              payloadLen = read,
            )
          serverSeq = (serverSeq + read) and 0xFFFFFFFFL
          writeTunPacket(output, dataPkt)
        }
      }

      synchronized(stateLock) {
        if (active.get() && !finSent) {
          finSent = true
          val finPkt =
            PacketUtils.buildIpv4TcpPacket(
              srcIp = serverIp,
              dstIp = clientIp,
              srcPort = serverPort,
              dstPort = clientPort,
              seqNum = serverSeq,
              ackNum = expectedClientSeq,
              flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK,
            )
          serverSeq = (serverSeq + 1L) and 0xFFFFFFFFL
          writeTunPacket(output, finPkt)
        }
      }
      closeAndFinalize(200, "Completed")
    }

    private fun closeAndFinalize(statusCode: Int, statusMsg: String) {
      close()
      tcpSessions.remove(key)
      synchronized(stateLock) {
        if (closedReported) return
        closedReported = true
      }
      if (totalOut.get() == 0L && totalIn.get() == 0L) return
      val hostLabel = detectedHost ?: key.dstIp
      val elapsed = System.currentTimeMillis() - startedAtMs
      onTransactionObserved(
        CapturedTransaction(
          id = txId,
          sessionId = sessionId,
          timestampEpochMs = startedAtMs,
          appPackage = owner.packageName,
          appName = owner.appName,
          method = detectedMethod,
          scheme = detectedScheme,
          host = hostLabel,
          port = serverPort,
          path = detectedPath,
          fullUrl = "$detectedScheme://$hostLabel:$serverPort$detectedPath",
          protocol = detectedProtocol,
          statusCode = statusCode,
          statusMessage = statusMsg,
          tlsStatus = detectedTlsStatus,
          requestSizeBytes = totalOut.get(),
          responseSizeBytes = totalIn.get(),
          timings = TimingMetrics(totalMs = elapsed),
          notes =
            if (detectedTlsStatus == TlsStatus.PASSTHROUGH_PINNED) {
              "Direct TCP/TLS stream forwarded transparently (compatible with messengers and pinned apps)."
            } else "",
        )
      )
    }

    fun close() {
      if (active.compareAndSet(true, false)) {
        runCatching { socket?.close() }
      }
    }
  }

  companion object {
    fun parseDnsQuestionDomain(dnsPayload: ByteArray): String? =
      PacketUtils.parseDnsQuestionDomain(dnsPayload)

    fun buildIpv4UdpPacket(
      srcIp: ByteArray,
      dstIp: ByteArray,
      srcPort: Int,
      dstPort: Int,
      payload: ByteArray,
    ): ByteArray =
      PacketUtils.buildIpv4UdpPacket(srcIp, dstIp, srcPort, dstPort, payload)
  }
}
