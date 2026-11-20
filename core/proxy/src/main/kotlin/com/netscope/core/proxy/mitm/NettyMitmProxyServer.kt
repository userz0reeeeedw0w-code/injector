package com.netscope.core.proxy.mitm

import com.netscope.core.proxy.ca.CaIdentity
import com.netscope.core.proxy.ca.DynamicCertGenerator
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.HeaderEntry
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.TimingMetrics
import com.netscope.shared.model.TlsStatus
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory
import io.netty.handler.ssl.SslHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ProxyConfig(
  val sessionId: String,
  val mitmEnabled: Boolean = true,
  val pinningCompatibilityMode: Boolean = true,
  val maxBodyCaptureBytes: Int = 256 * 1024,
)

class NettyMitmProxyServer(
  private val caIdentity: CaIdentity,
  private val socketProtector: (Socket) -> Boolean = { true },
) {
  private val certGenerator = DynamicCertGenerator(caIdentity)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _transactions = MutableSharedFlow<CapturedTransaction>(extraBufferCapacity = 256)
  val transactions: SharedFlow<CapturedTransaction> = _transactions.asSharedFlow()

  private val pinnedBypassHosts = ConcurrentHashMap.newKeySet<String>()
  private var bossGroup: EventLoopGroup? = null
  private var workerGroup: EventLoopGroup? = null
  private var serverChannel: Channel? = null

  @Volatile var port: Int = 0
    private set

  @Volatile var config: ProxyConfig = ProxyConfig(sessionId = UUID.randomUUID().toString())

  private val protectedOkHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .socketFactory(
        object : SocketFactory() {
          override fun createSocket(): Socket = Socket().also { socketProtector(it) }

          override fun createSocket(host: String?, port: Int): Socket =
            Socket(host, port).also { socketProtector(it) }

          override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
          ): Socket = Socket(host, port, localHost, localPort).also { socketProtector(it) }

          override fun createSocket(host: InetAddress?, port: Int): Socket =
            Socket(host, port).also { socketProtector(it) }

          override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
          ): Socket = Socket(address, port, localAddress, localPort).also { socketProtector(it) }
        }
      )
      .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(false)
      .build()
  }

  @Synchronized
  fun start(listenPort: Int = 0): Int {
    if (serverChannel?.isActive == true) return port
    val boss = NioEventLoopGroup(1)
    val worker = NioEventLoopGroup(4)
    bossGroup = boss
    workerGroup = worker

    val bootstrap =
      ServerBootstrap()
        .group(boss, worker)
        .channel(NioServerSocketChannel::class.java)
        .childOption(ChannelOption.TCP_NODELAY, true)
        .childHandler(
          object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
              ch.pipeline().addLast("httpCodec", HttpServerCodec())
              ch.pipeline().addLast("aggregator", HttpObjectAggregator(2 * 1024 * 1024))
              ch.pipeline().addLast("mitmHandler", MitmHttpHandler())
            }
          }
        )

    val future = bootstrap.bind("127.0.0.1", listenPort).sync()
    serverChannel = future.channel()
    port = (future.channel().localAddress() as InetSocketAddress).port
    return port
  }

  @Synchronized
  fun stop() {
    runCatching { serverChannel?.close()?.sync() }
    runCatching { bossGroup?.shutdownGracefully() }
    runCatching { workerGroup?.shutdownGracefully() }
    serverChannel = null
    bossGroup = null
    workerGroup = null
    port = 0
  }

  fun markHostPinned(host: String) {
    pinnedBypassHosts.add(host.lowercase())
  }

  fun clearPinnedCache() {
    pinnedBypassHosts.clear()
  }

  private fun emitTransaction(tx: CapturedTransaction) {
    _transactions.tryEmit(tx)
  }

  private inner class MitmHttpHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
    private var currentScheme: String = "http"
    private var currentTargetHost: String = ""
    private var currentTargetPort: Int = 80
    private var isMitmTls: Boolean = false

    override fun channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest) {
      if (req.method().name().equals("CONNECT", ignoreCase = true)) {
        handleConnectTunnel(ctx, req)
        return
      }

      if (req.headers().contains(HttpHeaderNames.UPGRADE, "websocket", true)) {
        handleWebSocketUpgrade(ctx, req)
        return
      }

      val reqSnapshot = req.retain()
      scope.launch {
        try {
          forwardHttpRequest(ctx, reqSnapshot)
        } finally {
          reqSnapshot.release()
        }
      }
    }

    private fun handleConnectTunnel(ctx: ChannelHandlerContext, req: FullHttpRequest) {
      val hostPort = req.uri().split(":")
      val host = hostPort.getOrNull(0)?.trim().orEmpty()
      val targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
      currentScheme = "https"
      currentTargetHost = host
      currentTargetPort = targetPort

      val ack =
        DefaultFullHttpResponse(
          HttpVersion.HTTP_1_1,
          HttpResponseStatus(200, "Connection Established"),
        )
      ctx.writeAndFlush(ack).addListener {
        val shouldMitm =
          config.mitmEnabled &&
            !(config.pinningCompatibilityMode && pinnedBypassHosts.contains(host.lowercase()))

        if (shouldMitm) {
          isMitmTls = true
          val sslCtx = certGenerator.getOrCreateServerSslContext(host)
          val sslHandler: SslHandler = sslCtx.newHandler(ctx.alloc())
          sslHandler.handshakeFuture().addListener { handshake ->
            if (!handshake.isSuccess) {
              // Client likely uses Certificate Pinning: record fallback and switch host to passthrough
              if (config.pinningCompatibilityMode) {
                pinnedBypassHosts.add(host.lowercase())
              }
              emitTransaction(
                CapturedTransaction(
                  id = UUID.randomUUID().toString(),
                  sessionId = config.sessionId,
                  timestampEpochMs = System.currentTimeMillis(),
                  method = HttpMethod.HEAD,
                  scheme = "https",
                  host = host,
                  port = targetPort,
                  path = "/[TLS-Handshake-Rejected]",
                  fullUrl = "https://$host:$targetPort",
                  protocol = "TLS",
                  statusCode = 0,
                  statusMessage = "Pinning / Untrusted CA",
                  tlsStatus = TlsStatus.PASSTHROUGH_PINNED,
                  notes =
                    "Client rejected local CA (likely SSL pinning). Added to compatibility passthrough.",
                )
              )
              ctx.close()
            }
          }
          ctx.pipeline().addFirst("ssl", sslHandler)
        } else {
          // Transparent TCP passthrough (no decryption)
          setupTcpPassthrough(ctx, host, targetPort)
        }
      }
    }

    private fun setupTcpPassthrough(
      clientCtx: ChannelHandlerContext,
      host: String,
      targetPort: Int,
    ) {
      val startMs = System.currentTimeMillis()
      val pipeline = clientCtx.pipeline()
      clientCtx.channel().config().isAutoRead = false
      listOf("httpCodec", "aggregator", "mitmHandler").forEach { name ->
        if (pipeline.get(name) != null) pipeline.remove(name)
      }

      var bytesIn = 0L
      val outboundBootstrap =
        Bootstrap()
          .group(clientCtx.channel().eventLoop())
          .channel(NioSocketChannel::class.java)
          .option(ChannelOption.TCP_NODELAY, true)
          .handler(
            object : ChannelInboundHandlerAdapter() {
              override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                if (msg is ByteBuf) bytesIn += msg.readableBytes()
                clientCtx.writeAndFlush(msg)
              }

              override fun channelInactive(ctx: ChannelHandlerContext) {
                clientCtx.close()
              }
            }
          )

      outboundBootstrap.connect(host, targetPort).addListener { connectFuture ->
        if (!connectFuture.isSuccess) {
          clientCtx.close()
          return@addListener
        }
        val outboundChannel = (connectFuture as io.netty.channel.ChannelFuture).channel()
        var bytesOut = 0L
        pipeline.addLast(
          object : ChannelInboundHandlerAdapter() {
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
              if (msg is ByteBuf) bytesOut += msg.readableBytes()
              outboundChannel.writeAndFlush(msg)
            }

            override fun channelInactive(ctx: ChannelHandlerContext) {
              outboundChannel.close()
              emitTransaction(
                CapturedTransaction(
                  id = UUID.randomUUID().toString(),
                  sessionId = config.sessionId,
                  timestampEpochMs = startMs,
                  method = HttpMethod.HEAD,
                  scheme = "https",
                  host = host,
                  port = targetPort,
                  path = "/[TLS-Passthrough]",
                  fullUrl = "https://$host:$targetPort",
                  protocol = "TLS Tunnel",
                  statusCode = 200,
                  statusMessage = "Tunnel Passthrough",
                  tlsStatus = TlsStatus.PASSTHROUGH_PINNED,
                  requestSizeBytes = bytesOut,
                  responseSizeBytes = bytesIn,
                  timings = TimingMetrics(totalMs = System.currentTimeMillis() - startMs),
                  notes = "Passthrough mode active (pinning compatibility or CA disabled).",
                )
              )
            }
          }
        )
        clientCtx.channel().config().isAutoRead = true
        clientCtx.read()
      }
    }

    private fun handleWebSocketUpgrade(ctx: ChannelHandlerContext, req: FullHttpRequest) {
      val host = req.headers().get(HttpHeaderNames.HOST) ?: currentTargetHost
      val wsUrl = "${if (isMitmTls) "wss" else "ws"}://$host${req.uri()}"
      val handshaker =
        WebSocketServerHandshakerFactory(wsUrl, null, true).newHandshaker(req)
      if (handshaker == null) {
        WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
        return
      }
      handshaker.handshake(ctx.channel(), req)
      ctx.pipeline().replace(
        "mitmHandler",
        "wsHandler",
        object : SimpleChannelInboundHandler<WebSocketFrame>() {
          override fun channelRead0(wCtx: ChannelHandlerContext, frame: WebSocketFrame) {
            val payload = (frame as? TextWebSocketFrame)?.text() ?: "[Binary WS Frame]"
            emitTransaction(
              CapturedTransaction(
                id = UUID.randomUUID().toString(),
                sessionId = config.sessionId,
                timestampEpochMs = System.currentTimeMillis(),
                method = HttpMethod.GET,
                scheme = if (isMitmTls) "wss" else "ws",
                host = host.substringBefore(':'),
                port = currentTargetPort,
                path = req.uri(),
                fullUrl = wsUrl,
                protocol = "WebSocket",
                statusCode = 101,
                statusMessage = "Switching Protocols",
                tlsStatus = TlsStatus.WEBSOCKET,
                requestBody = payload.take(config.maxBodyCaptureBytes),
                requestSizeBytes = payload.toByteArray().size.toLong(),
                isWebSocket = true,
              )
            )
            wCtx.writeAndFlush(TextWebSocketFrame(payload))
          }
        },
      )
    }

    private fun forwardHttpRequest(ctx: ChannelHandlerContext, req: FullHttpRequest) {
      val startNs = System.nanoTime()
      val hostHeader = req.headers().get(HttpHeaderNames.HOST) ?: currentTargetHost
      val cleanHost = hostHeader.substringBefore(':')
      val port = hostHeader.substringAfter(':', "").toIntOrNull() ?: currentTargetPort
      val rawUri = req.uri()
      val fullUrl =
        if (rawUri.startsWith("http://") || rawUri.startsWith("https://")) {
          rawUri
        } else {
          "$currentScheme://$hostHeader$rawUri"
        }

      val parsedPath = rawUri.substringAfter(hostHeader, rawUri).substringBefore('?')
      val parsedQuery = if (rawUri.contains('?')) rawUri.substringAfter('?') else ""

      val reqHeaders =
        req.headers().entries().map { HeaderEntry(it.key, it.value) }
      val reqBytes = ByteArray(req.content().readableBytes())
      req.content().getBytes(req.content().readerIndex(), reqBytes)
      val reqBodyText =
        if (reqBytes.isNotEmpty()) {
          String(reqBytes.copyOf(minOf(reqBytes.size, config.maxBodyCaptureBytes)), StandardCharsets.UTF_8)
        } else ""
      val reqContentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE) ?: ""

      val okReqBuilder = Request.Builder().url(fullUrl)
      reqHeaders.forEach { h ->
        if (
          !h.name.equals("Proxy-Connection", ignoreCase = true) &&
            !h.name.equals("Host", ignoreCase = true) &&
            !h.name.equals("Content-Length", ignoreCase = true)
        ) {
          okReqBuilder.addHeader(h.name, h.value)
        }
      }

      val methodStr = req.method().name().uppercase()
      val hasBody = methodStr in setOf("POST", "PUT", "PATCH", "DELETE") && reqBytes.isNotEmpty()
      val okBody =
        if (hasBody) {
          reqBytes.toRequestBody(reqContentType.toMediaTypeOrNull())
        } else if (methodStr in setOf("POST", "PUT", "PATCH")) {
          ByteArray(0).toRequestBody(null)
        } else null
      okReqBuilder.method(methodStr, okBody)

      try {
        val response = protectedOkHttpClient.newCall(okReqBuilder.build()).execute()
        val ttfbMs = (System.nanoTime() - startNs) / 1_000_000L
        val resBytes = response.body?.bytes() ?: ByteArray(0)
        val totalMs = (System.nanoTime() - startNs) / 1_000_000L

        val resHeaders =
          response.headers.toMultimap().flatMap { (k, vals) -> vals.map { HeaderEntry(k, it) } }
        val resContentType = response.header("Content-Type") ?: ""
        val resBodyText =
          if (resBytes.isNotEmpty()) {
            String(
              resBytes.copyOf(minOf(resBytes.size, config.maxBodyCaptureBytes)),
              StandardCharsets.UTF_8,
            )
          } else ""

        val nettyResp =
          DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus(response.code, response.message.ifBlank { "OK" }),
            Unpooled.wrappedBuffer(resBytes),
          )
        resHeaders.forEach { h ->
          if (
            !h.name.equals("Transfer-Encoding", ignoreCase = true) &&
              !h.name.equals("Content-Length", ignoreCase = true)
          ) {
            nettyResp.headers().add(h.name, h.value)
          }
        }
        nettyResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, resBytes.size)
        ctx.writeAndFlush(nettyResp)

        emitTransaction(
          CapturedTransaction(
            id = UUID.randomUUID().toString(),
            sessionId = config.sessionId,
            timestampEpochMs = System.currentTimeMillis(),
            method = HttpMethod.fromString(methodStr),
            scheme = currentScheme,
            host = cleanHost,
            port = port,
            path = parsedPath.ifBlank { "/" },
            query = parsedQuery,
            fullUrl = fullUrl,
            protocol =
              if (response.protocol == Protocol.HTTP_2) "HTTP/2" else "HTTP/1.1",
            statusCode = response.code,
            statusMessage = response.message,
            tlsStatus = if (isMitmTls) TlsStatus.MITM_DECRYPTED else TlsStatus.CLEARTEXT,
            requestHeaders = reqHeaders,
            requestBody = reqBodyText,
            requestContentType = reqContentType,
            requestSizeBytes = reqBytes.size.toLong(),
            responseHeaders = resHeaders,
            responseBody = resBodyText,
            responseContentType = resContentType,
            responseSizeBytes = resBytes.size.toLong(),
            timings = TimingMetrics(ttfbMs = ttfbMs, totalMs = totalMs),
          )
        )
        response.close()
      } catch (e: Exception) {
        val errBytes = (e.message ?: "Upstream Proxy Error").toByteArray(StandardCharsets.UTF_8)
        val errResp =
          DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.BAD_GATEWAY,
            Unpooled.wrappedBuffer(errBytes),
          )
        errResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, errBytes.size)
        ctx.writeAndFlush(errResp).addListener(ChannelFutureListener.CLOSE)

        emitTransaction(
          CapturedTransaction(
            id = UUID.randomUUID().toString(),
            sessionId = config.sessionId,
            timestampEpochMs = System.currentTimeMillis(),
            method = HttpMethod.fromString(methodStr),
            scheme = currentScheme,
            host = cleanHost,
            port = port,
            path = parsedPath.ifBlank { "/" },
            query = parsedQuery,
            fullUrl = fullUrl,
            protocol = "HTTP/1.1",
            statusCode = 502,
            statusMessage = "Bad Gateway",
            tlsStatus = if (isMitmTls) TlsStatus.MITM_DECRYPTED else TlsStatus.CLEARTEXT,
            requestHeaders = reqHeaders,
            requestBody = reqBodyText,
            requestSizeBytes = reqBytes.size.toLong(),
            responseBody = e.message ?: "Proxy Error",
            timings = TimingMetrics(totalMs = (System.nanoTime() - startNs) / 1_000_000L),
            notes = "Upstream failure: ${e.message}",
          )
        )
      }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
      ctx.close()
    }
  }
}
