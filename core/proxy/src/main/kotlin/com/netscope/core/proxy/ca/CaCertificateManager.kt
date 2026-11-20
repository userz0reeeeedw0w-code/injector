package com.netscope.core.proxy.ca

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

data class CaIdentity(
  val certificate: X509Certificate,
  val privateKey: PrivateKey,
  val fingerprintSha256: String,
)

class CaCertificateManager(private val context: Context) {
  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "netscope_ca_p12_wrapper"
    private const val PREFS_NAME = "netscope_ca_prefs"
    private const val PREF_P12_PASS_CT = "p12_pass_ct"
    private const val PREF_P12_PASS_IV = "p12_pass_iv"
    private const val CA_ALIAS = "netscope_root_ca"
    private const val CA_CN = "NetScope Local Inspection CA"

    /**
     * Explicit BouncyCastle 1.78+ provider instance.
     * Avoids looking up "BC" by name, because Android ships a stripped system "BC" provider
     * that causes NoSuchAlgorithmException / OperatorCreationException on real devices & emulators.
     */
    internal val bcProvider: BouncyCastleProvider by lazy { BouncyCastleProvider() }
  }

  private val certDir: File
    get() = File(context.filesDir, "certs").apply { mkdirs() }

  private val p12File: File
    get() = File(certDir, "netscope_ca.p12")

  val publicCrtFile: File
    get() = File(certDir, "NetScopeRootCA.crt")

  val publicPemFile: File
    get() = File(certDir, "NetScopeRootCA.pem")

  @Synchronized
  fun getOrCreateCa(): CaIdentity {
    val password = getOrCreateP12Password()
    if (p12File.exists()) {
      runCatching {
          val ks = KeyStore.getInstance("PKCS12")
          FileInputStream(p12File).use { ks.load(it, password) }
          val cert = ks.getCertificate(CA_ALIAS) as? X509Certificate
          val key = ks.getKey(CA_ALIAS, password) as? PrivateKey
          if (cert != null && key != null) {
            ensurePublicFilesExported(cert)
            return CaIdentity(cert, key, sha256Fingerprint(cert))
          }
        }
    }

    val keyPair = generateRsaKeyPair(2048)
    val cert = generateRootCaCertificate(keyPair)
    val ks = KeyStore.getInstance("PKCS12").apply { load(null, password) }
    ks.setKeyEntry(CA_ALIAS, keyPair.private, password, arrayOf(cert))
    FileOutputStream(p12File).use { ks.store(it, password) }
    ensurePublicFilesExported(cert)
    return CaIdentity(cert, keyPair.private, sha256Fingerprint(cert))
  }

  @Synchronized
  fun regenerateCa(): CaIdentity {
    if (p12File.exists()) p12File.delete()
    if (publicCrtFile.exists()) publicCrtFile.delete()
    if (publicPemFile.exists()) publicPemFile.delete()
    return getOrCreateCa()
  }

  fun createInstallCaIntent(): Intent {
    val ca = getOrCreateCa()
    val intent = KeyChain.createInstallIntent()
    intent.putExtra(KeyChain.EXTRA_CERTIFICATE, ca.certificate.encoded)
    intent.putExtra(KeyChain.EXTRA_NAME, "NetScope Local CA")
    return intent
  }

  fun isCaInstalledInUserStore(): Boolean {
    val ca = runCatching { getOrCreateCa() }.getOrNull() ?: return false
    return runCatching {
        val androidCaStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = androidCaStore.aliases()
        while (aliases.hasMoreElements()) {
          val alias = aliases.nextElement()
          val cert = androidCaStore.getCertificate(alias) as? X509Certificate ?: continue
          if (cert.encoded.contentEquals(ca.certificate.encoded)) {
            return true
          }
        }
        false
      }
      .getOrDefault(false)
  }

  private fun ensurePublicFilesExported(cert: X509Certificate) {
    if (!publicCrtFile.exists()) {
      publicCrtFile.writeBytes(cert.encoded)
    }
    if (!publicPemFile.exists()) {
      val b64 = Base64.encodeToString(cert.encoded, Base64.DEFAULT)
      val pem = "-----BEGIN CERTIFICATE-----\n$b64-----END CERTIFICATE-----\n"
      publicPemFile.writeText(pem)
    }
  }

  private fun generateRsaKeyPair(bits: Int): KeyPair {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(bits, SecureRandom())
    return kpg.generateKeyPair()
  }

  private fun generateRootCaCertificate(keyPair: KeyPair): X509Certificate {
    val now = System.currentTimeMillis()
    val notBefore = Date(now - 24L * 60 * 60 * 1000)
    val notAfter = Date(now + 3650L * 24 * 60 * 60 * 1000) // 10 years
    val subject = X500Name("CN=$CA_CN, O=NetScope Security Tools, OU=Local Proxy CA")
    val serial = BigInteger(128, SecureRandom())

    val builder =
      JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
    val extUtils = JcaX509ExtensionUtils()
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
    builder.addExtension(
      Extension.keyUsage,
      true,
      KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
    )
    builder.addExtension(
      Extension.subjectKeyIdentifier,
      false,
      extUtils.createSubjectKeyIdentifier(keyPair.public),
    )

    val signer =
      JcaContentSignerBuilder("SHA256WithRSAEncryption")
        .setProvider(bcProvider)
        .build(keyPair.private)
    return JcaX509CertificateConverter()
      .setProvider(bcProvider)
      .getCertificate(builder.build(signer))
  }

  private fun sha256Fingerprint(cert: X509Certificate): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(cert.encoded)
    return digest.joinToString(":") { "%02X".format(it) }
  }

  private fun getOrCreateP12Password(): CharArray {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val ct = prefs.getString(PREF_P12_PASS_CT, null)
    val iv = prefs.getString(PREF_P12_PASS_IV, null)
    return runCatching {
        val key = getOrCreateAesMasterKey()
        if (ct != null && iv != null) {
          val cipher = Cipher.getInstance("AES/GCM/NoPadding")
          cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
          String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8).toCharArray()
        } else {
          val raw = ByteArray(24).also { SecureRandom().nextBytes(it) }
          val passStr = Base64.encodeToString(raw, Base64.NO_WRAP)
          val cipher = Cipher.getInstance("AES/GCM/NoPadding")
          cipher.init(Cipher.ENCRYPT_MODE, key)
          val enc = cipher.doFinal(passStr.toByteArray(Charsets.UTF_8))
          prefs
            .edit()
            .putString(PREF_P12_PASS_CT, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(PREF_P12_PASS_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
          passStr.toCharArray()
        }
      }
      .getOrElse { "netscope-local-ca-fallback".toCharArray() }
  }

  private fun getOrCreateAesMasterKey(): SecretKey {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = ks.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    gen.init(
      KeyGenParameterSpec.Builder(
          MASTER_KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return gen.generateKey()
  }
}
