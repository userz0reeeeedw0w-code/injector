package com.netscope.core.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystorePassphraseProvider {
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "netscope_sqlcipher_master_key"
  private const val PREFS_NAME = "netscope_secure_keystore_prefs"
  private const val PREF_CIPHERTEXT = "db_passphrase_ct"
  private const val PREF_IV = "db_passphrase_iv"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"

  fun getOrCreatePassphrase(context: Context): ByteArray {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val existingCt = prefs.getString(PREF_CIPHERTEXT, null)
    val existingIv = prefs.getString(PREF_IV, null)

    return runCatching {
        val key = getOrCreateAesKey()
        if (existingCt != null && existingIv != null) {
          val cipher = Cipher.getInstance(TRANSFORMATION)
          val iv = Base64.decode(existingIv, Base64.NO_WRAP)
          cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
          cipher.doFinal(Base64.decode(existingCt, Base64.NO_WRAP))
        } else {
          val rawSecret = ByteArray(32)
          SecureRandom().nextBytes(rawSecret)
          val cipher = Cipher.getInstance(TRANSFORMATION)
          cipher.init(Cipher.ENCRYPT_MODE, key)
          val encrypted = cipher.doFinal(rawSecret)
          val iv = cipher.iv
          prefs
            .edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
          rawSecret
        }
      }
      .getOrElse {
        // Fallback only if AndroidKeyStore is unavailable (e.g. unit test JVM or broken TEE)
        val existingFallback = prefs.getString("db_passphrase_fb", null)
        if (existingFallback != null) {
          Base64.decode(existingFallback, Base64.NO_WRAP)
        } else {
          val fallback = ByteArray(32).also { SecureRandom().nextBytes(it) }
          prefs.edit().putString("db_passphrase_fb", Base64.encodeToString(fallback, Base64.NO_WRAP)).apply()
          fallback
        }
      }
  }

  private fun getOrCreateAesKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    generator.init(spec)
    return generator.generateKey()
  }
}
