package com.netscope.core.network.vpn

import java.net.InetAddress
import java.nio.ByteBuffer

object PacketUtils {
  const val PROTO_TCP = 6
  const val PROTO_UDP = 17

  const val TCP_FIN = 0x01
  const val TCP_SYN = 0x02
  const val TCP_RST = 0x04
  const val TCP_PSH = 0x08
  const val TCP_ACK = 0x10

  data class Ipv4Header(
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
  ) {
    val srcAddress: InetAddress get() = InetAddress.getByAddress(srcIp)
    val dstAddress: InetAddress get() = InetAddress.getByAddress(dstIp)
  }

  data class TcpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val seqNum: Long,
    val ackNum: Long,
    val headerLength: Int,
    val flags: Int,
    val window: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
  )

  data class UdpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
  )

  data class HttpRequestPreview(
    val method: String,
    val path: String,
    val host: String?,
  )

  fun parseIpv4Header(packet: ByteArray, length: Int): Ipv4Header? {
    if (length < 20) return null
    val version = (packet[0].toInt() ushr 4) and 0x0F
    if (version != 4) return null
    val ihl = (packet[0].toInt() and 0x0F) * 4
    if (ihl < 20 || length < ihl) return null
    val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
    val effectiveLen = minOf(length, totalLen.coerceAtLeast(ihl))
    val protocol = packet[9].toInt() and 0xFF
    return Ipv4Header(
      headerLength = ihl,
      totalLength = effectiveLen,
      protocol = protocol,
      srcIp = packet.copyOfRange(12, 16),
      dstIp = packet.copyOfRange(16, 20),
    )
  }

  fun parseTcpHeader(packet: ByteArray, ipHeader: Ipv4Header): TcpHeader? {
    val off = ipHeader.headerLength
    if (ipHeader.totalLength < off + 20) return null
    val srcPort = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
    val dstPort = ((packet[off + 2].toInt() and 0xFF) shl 8) or (packet[off + 3].toInt() and 0xFF)
    val seqNum =
      ((packet[off + 4].toLong() and 0xFF) shl 24) or
        ((packet[off + 5].toLong() and 0xFF) shl 16) or
        ((packet[off + 6].toLong() and 0xFF) shl 8) or
        (packet[off + 7].toLong() and 0xFF)
    val ackNum =
      ((packet[off + 8].toLong() and 0xFF) shl 24) or
        ((packet[off + 9].toLong() and 0xFF) shl 16) or
        ((packet[off + 10].toLong() and 0xFF) shl 8) or
        (packet[off + 11].toLong() and 0xFF)
    val dataOffset = ((packet[off + 12].toInt() ushr 4) and 0x0F) * 4
    if (dataOffset < 20 || ipHeader.totalLength < off + dataOffset) return null
    val flags = packet[off + 13].toInt() and 0x3F
    val window = ((packet[off + 14].toInt() and 0xFF) shl 8) or (packet[off + 15].toInt() and 0xFF)
    val payloadOffset = off + dataOffset
    val payloadLength = (ipHeader.totalLength - payloadOffset).coerceAtLeast(0)
    return TcpHeader(
      srcPort = srcPort,
      dstPort = dstPort,
      seqNum = seqNum,
      ackNum = ackNum,
      headerLength = dataOffset,
      flags = flags,
      window = window,
      payloadOffset = payloadOffset,
      payloadLength = payloadLength,
    )
  }

  fun parseUdpHeader(packet: ByteArray, ipHeader: Ipv4Header): UdpHeader? {
    val off = ipHeader.headerLength
    if (ipHeader.totalLength < off + 8) return null
    val srcPort = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
    val dstPort = ((packet[off + 2].toInt() and 0xFF) shl 8) or (packet[off + 3].toInt() and 0xFF)
    val udpLen = ((packet[off + 4].toInt() and 0xFF) shl 8) or (packet[off + 5].toInt() and 0xFF)
    val payloadOffset = off + 8
    val available = (ipHeader.totalLength - payloadOffset).coerceAtLeast(0)
    val payloadLength = minOf(available, (udpLen - 8).coerceAtLeast(0))
    return UdpHeader(
      srcPort = srcPort,
      dstPort = dstPort,
      payloadOffset = payloadOffset,
      payloadLength = payloadLength,
    )
  }

  fun buildIpv4TcpPacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    seqNum: Long,
    ackNum: Long,
    flags: Int,
    window: Int = 65535,
    payload: ByteArray = ByteArray(0),
    payloadLen: Int = payload.size,
  ): ByteArray {
    val ipLen = 20
    val tcpLen = 20
    val totalLen = ipLen + tcpLen + payloadLen
    val buf = ByteBuffer.allocate(totalLen)

    // IPv4 Header
    buf.put(0x45.toByte())
    buf.put(0x00.toByte())
    buf.putShort(totalLen.toShort())
    buf.putShort(0x0000)
    buf.putShort(0x4000)
    buf.put(64.toByte())
    buf.put(PROTO_TCP.toByte())
    buf.putShort(0x0000)
    buf.put(srcIp)
    buf.put(dstIp)

    // TCP Header
    buf.putShort(srcPort.toShort())
    buf.putShort(dstPort.toShort())
    buf.putInt(seqNum.toInt())
    buf.putInt(ackNum.toInt())
    buf.put(0x50.toByte()) // Data offset = 5 (20 bytes)
    buf.put((flags and 0x3F).toByte())
    buf.putShort(window.coerceIn(0, 65535).toShort())
    buf.putShort(0x0000) // TCP checksum placeholder
    buf.putShort(0x0000) // Urgent pointer

    if (payloadLen > 0) {
      buf.put(payload, 0, payloadLen)
    }

    val bytes = buf.array()
    val ipChecksum = computeIpChecksum(bytes, 0, ipLen)
    bytes[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
    bytes[11] = (ipChecksum and 0xFF).toByte()

    val tcpChecksum =
      computeTransportChecksum(
        srcIp = srcIp,
        dstIp = dstIp,
        protocol = PROTO_TCP,
        segment = bytes,
        segmentOffset = ipLen,
        segmentLength = tcpLen + payloadLen,
      )
    bytes[ipLen + 16] = ((tcpChecksum ushr 8) and 0xFF).toByte()
    bytes[ipLen + 17] = (tcpChecksum and 0xFF).toByte()
    return bytes
  }

  fun buildIpv4UdpPacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
    payloadLen: Int = payload.size,
  ): ByteArray {
    val ipLen = 20
    val udpLen = 8 + payloadLen
    val totalLen = ipLen + udpLen
    val buf = ByteBuffer.allocate(totalLen)

    buf.put(0x45.toByte())
    buf.put(0x00.toByte())
    buf.putShort(totalLen.toShort())
    buf.putShort(0x0000)
    buf.putShort(0x4000)
    buf.put(64.toByte())
    buf.put(PROTO_UDP.toByte())
    buf.putShort(0x0000)
    buf.put(srcIp)
    buf.put(dstIp)

    buf.putShort(srcPort.toShort())
    buf.putShort(dstPort.toShort())
    buf.putShort(udpLen.toShort())
    buf.putShort(0x0000) // Checksum placeholder
    if (payloadLen > 0) {
      buf.put(payload, 0, payloadLen)
    }

    val bytes = buf.array()
    val ipChecksum = computeIpChecksum(bytes, 0, ipLen)
    bytes[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
    bytes[11] = (ipChecksum and 0xFF).toByte()

    val udpChecksum =
      computeTransportChecksum(
        srcIp = srcIp,
        dstIp = dstIp,
        protocol = PROTO_UDP,
        segment = bytes,
        segmentOffset = ipLen,
        segmentLength = udpLen,
      )
    bytes[ipLen + 6] = ((udpChecksum ushr 8) and 0xFF).toByte()
    bytes[ipLen + 7] = (udpChecksum and 0xFF).toByte()
    return bytes
  }

  fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    var i = offset
    while (i < offset + length - 1) {
      sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
      i += 2
    }
    if (length % 2 == 1) {
      sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    return sum.inv().toInt() and 0xFFFF
  }

  fun computeTransportChecksum(
    srcIp: ByteArray,
    dstIp: ByteArray,
    protocol: Int,
    segment: ByteArray,
    segmentOffset: Int,
    segmentLength: Int,
  ): Int {
    var sum = 0L
    // Pseudo-header
    sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
    sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
    sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
    sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
    sum += protocol and 0xFF
    sum += segmentLength and 0xFFFF

    // Segment bytes
    var i = segmentOffset
    while (i < segmentOffset + segmentLength - 1) {
      sum += ((segment[i].toInt() and 0xFF) shl 8) or (segment[i + 1].toInt() and 0xFF)
      i += 2
    }
    if (segmentLength % 2 == 1) {
      sum += (segment[segmentOffset + segmentLength - 1].toInt() and 0xFF) shl 8
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    val res = sum.inv().toInt() and 0xFFFF
    return if (protocol == PROTO_UDP && res == 0) 0xFFFF else res
  }

  fun extractTlsSni(payload: ByteArray): String? {
    if (payload.size < 43) return null
    if ((payload[0].toInt() and 0xFF) != 0x16) return null // Handshake record
    if ((payload[5].toInt() and 0xFF) != 0x01) return null // ClientHello

    var idx = 5 + 4 + 2 + 32 // Handshake hdr + version + random
    if (idx >= payload.size) return null

    // Session ID
    val sessionIdLen = payload[idx].toInt() and 0xFF
    idx += 1 + sessionIdLen
    if (idx + 2 > payload.size) return null

    // Cipher suites
    val cipherLen = ((payload[idx].toInt() and 0xFF) shl 8) or (payload[idx + 1].toInt() and 0xFF)
    idx += 2 + cipherLen
    if (idx + 1 > payload.size) return null

    // Compression methods
    val compLen = payload[idx].toInt() and 0xFF
    idx += 1 + compLen
    if (idx + 2 > payload.size) return null

    // Extensions length
    val extTotalLen = ((payload[idx].toInt() and 0xFF) shl 8) or (payload[idx + 1].toInt() and 0xFF)
    idx += 2
    val extEnd = minOf(payload.size, idx + extTotalLen)

    while (idx + 4 <= extEnd) {
      val extType = ((payload[idx].toInt() and 0xFF) shl 8) or (payload[idx + 1].toInt() and 0xFF)
      val extLen = ((payload[idx + 2].toInt() and 0xFF) shl 8) or (payload[idx + 3].toInt() and 0xFF)
      idx += 4
      if (idx + extLen > extEnd) break
      if (extType == 0x0000 && extLen >= 5) {
        // server_name extension
        val nameType = payload[idx + 2].toInt() and 0xFF
        val nameLen = ((payload[idx + 3].toInt() and 0xFF) shl 8) or (payload[idx + 4].toInt() and 0xFF)
        if (nameType == 0 && idx + 5 + nameLen <= extEnd) {
          return String(payload, idx + 5, nameLen, Charsets.UTF_8)
        }
      }
      idx += extLen
    }
    return null
  }

  fun extractHttpPreview(payload: ByteArray): HttpRequestPreview? {
    val text = runCatching { String(payload, 0, minOf(payload.size, 1024), Charsets.US_ASCII) }.getOrNull()
      ?: return null
    val firstLineEnd = text.indexOf("\r\n")
    if (firstLineEnd <= 0) return null
    val firstLine = text.substring(0, firstLineEnd)
    val parts = firstLine.split(' ')
    if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return null
    val method = parts[0].uppercase()
    if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) return null
    val path = parts[1]
    val hostLine =
      text.split("\r\n").drop(1).firstOrNull { it.startsWith("Host:", ignoreCase = true) }
    val host = hostLine?.substringAfter(':')?.trim()
    return HttpRequestPreview(method = method, path = path, host = host)
  }

  fun parseDnsQuestionDomain(dnsPayload: ByteArray): String? {
    if (dnsPayload.size < 13) return null
    var idx = 12
    val labels = mutableListOf<String>()
    while (idx < dnsPayload.size) {
      val labelLen = dnsPayload[idx].toInt() and 0xFF
      if (labelLen == 0) break
      if ((labelLen and 0xC0) == 0xC0) break
      idx++
      if (idx + labelLen > dnsPayload.size) break
      labels += String(dnsPayload, idx, labelLen, Charsets.US_ASCII)
      idx += labelLen
    }
    return labels.takeIf { it.isNotEmpty() }?.joinToString(".")
  }

  fun extractDnsAnswerIps(dnsPayload: ByteArray): List<String> {
    if (dnsPayload.size < 12) return emptyList()
    val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
    val anCount = ((dnsPayload[6].toInt() and 0xFF) shl 8) or (dnsPayload[7].toInt() and 0xFF)
    if (anCount == 0) return emptyList()

    var idx = 12
    repeat(qdCount) {
      idx = skipDnsName(dnsPayload, idx)
      if (idx + 4 > dnsPayload.size) return emptyList()
      idx += 4
    }

    val ips = mutableListOf<String>()
    repeat(anCount) {
      idx = skipDnsName(dnsPayload, idx)
      if (idx + 10 > dnsPayload.size) return ips
      val type = ((dnsPayload[idx].toInt() and 0xFF) shl 8) or (dnsPayload[idx + 1].toInt() and 0xFF)
      val rdLen = ((dnsPayload[idx + 8].toInt() and 0xFF) shl 8) or (dnsPayload[idx + 9].toInt() and 0xFF)
      idx += 10
      if (idx + rdLen > dnsPayload.size) return ips
      if (type == 1 && rdLen == 4) {
        val ip =
          "${dnsPayload[idx].toInt() and 0xFF}.${dnsPayload[idx + 1].toInt() and 0xFF}.${dnsPayload[idx + 2].toInt() and 0xFF}.${dnsPayload[idx + 3].toInt() and 0xFF}"
        ips += ip
      }
      idx += rdLen
    }
    return ips
  }

  private fun skipDnsName(buf: ByteArray, start: Int): Int {
    var idx = start
    while (idx < buf.size) {
      val len = buf[idx].toInt() and 0xFF
      if (len == 0) return idx + 1
      if ((len and 0xC0) == 0xC0) return idx + 2
      idx += 1 + len
    }
    return idx
  }
}
