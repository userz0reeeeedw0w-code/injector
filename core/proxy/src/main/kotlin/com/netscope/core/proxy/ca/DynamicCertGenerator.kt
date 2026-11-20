package com.netscope.core.proxy.ca

import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class DynamicCertGenerator(private val caIdentity: CaIdentity) {
  private val sslContextCache = ConcurrentHashMap<String, SslContext>()

  fun getOrCreateServerSslContext(host: String): SslContext {
    val cleanHost = host.substringBefore(':').lowercase().ifBlank { "localhost" }
    return sslContextCache.computeIfAbsent(cleanHost) { buildSslContext(it) }
  }

  fun clearCache() {
    sslContextCache.clear()
  }

  private fun buildSslContext(host: String): SslContext {
    val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
    val leafPair = kpg.generateKeyPair()

    val now = System.currentTimeMillis()
    val notBefore = Date(now - 24L * 60 * 60 * 1000)
    val notAfter = Date(now + 365L * 24 * 60 * 60 * 1000)
    val subject = javax.security.auth.x500.X500Principal("CN=$host, O=NetScope Dynamic Leaf")
    val serial = BigInteger(128, SecureRandom())

    val builder =
      JcaX509v3CertificateBuilder(
        caIdentity.certificate,
        serial,
        notBefore,
        notAfter,
        subject,
        leafPair.public,
      )
    builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
    builder.addExtension(
      Extension.keyUsage,
      true,
      KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
    )
    builder.addExtension(
      Extension.extendedKeyUsage,
      false,
      ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
    )

    val nameType =
      if (isIpAddress(host)) GeneralName.iPAddress else GeneralName.dNSName
    val san = GeneralNames(GeneralName(nameType, host))
    builder.addExtension(Extension.subjectAlternativeName, false, san)

    val signer =
      JcaContentSignerBuilder("SHA256WithRSAEncryption")
        .setProvider(CaCertificateManager.bcProvider)
        .build(caIdentity.privateKey)
    val leafCert: X509Certificate =
      JcaX509CertificateConverter()
        .setProvider(CaCertificateManager.bcProvider)
        .getCertificate(builder.build(signer))

    val ks = java.security.KeyStore.getInstance("PKCS12").apply { load(null, CharArray(0)) }
    ks.setKeyEntry(
      "leaf",
      leafPair.private,
      CharArray(0),
      arrayOf(leafCert, caIdentity.certificate),
    )
    val kmf =
      javax.net.ssl.KeyManagerFactory.getInstance(
          javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()
        )
        .apply { init(ks, CharArray(0)) }

    return SslContextBuilder.forServer(kmf)
      .sslProvider(io.netty.handler.ssl.SslProvider.JDK)
      .build()
  }

  private fun isIpAddress(host: String): Boolean {
    val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    return ipv4.matches(host) || host.contains(":")
  }
}
