package snd.sketchpilot.core.sketchware

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sketchware Pro's on-disk obfuscation: AES-128-CBC/PKCS5Padding.
 * Key and IV are the same 16-byte literal used by the host's own backup code.
 * This is format compatibility only; it is NOT credential protection.
 */
object SketchwareCrypto {
    private const val KEY_LITERAL = "sketchwaresecure"
    private val key = KEY_LITERAL.toByteArray(StandardCharsets.UTF_8)

    fun encrypt(plain: String): ByteArray = cipher(Cipher.ENCRYPT_MODE)
        .doFinal(plain.toByteArray(StandardCharsets.UTF_8))

    fun decrypt(data: ByteArray): String =
        String(cipher(Cipher.DECRYPT_MODE).doFinal(data), StandardCharsets.UTF_8)

    fun looksEncrypted(data: ByteArray): Boolean {
        if (data.isEmpty() || data.size % 16 != 0) return false
        return runCatching { decrypt(data) }.getOrNull()?.let {
            it.isEmpty() || it.startsWith("@") || it.startsWith("{") || it.startsWith("[")
        } ?: false
    }

    private fun cipher(mode: Int): Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(key))
    }
}
