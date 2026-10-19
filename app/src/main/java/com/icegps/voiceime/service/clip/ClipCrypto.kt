package com.icegps.voiceime.service.clip

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 剪贴板局域网同步的 AES-GCM 加解密（设计文档 7.3）。
 *
 * - 配对时手机生成 256 位随机密钥（base64），随 pair_req 发给 PC。
 * - 每条 clip 消息：data = base64( nonce(96bit) + ciphertext )，nonce 每次随机。
 * - GCM 自带完整性校验：密钥不对或数据被篡改时解密直接失败，返回 null。
 */
object ClipCrypto {
    private const val NONCE_LEN = 12
    private const val TAG_LEN = 16
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 生成 AES-256 随机密钥（base64 编码） */
    fun generateKeyB64(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    /** 加密，返回 base64(nonce + ciphertext)；失败返回 null */
    fun encrypt(keyB64: String, plain: String): String? {
        return try {
            val key = SecretKeySpec(Base64.decode(keyB64, Base64.NO_WRAP), "AES")
            val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(nonce + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /** 解密，失败（密钥错误/tag 校验失败/数据损坏）返回 null，调用方直接丢弃 */
    fun decrypt(keyB64: String, dataB64: String): String? {
        return try {
            val raw = Base64.decode(dataB64, Base64.NO_WRAP)
            // blob 最小长度 = nonce(12) + tag(16)，明文可为空
            if (raw.size < NONCE_LEN + TAG_LEN) return null
            val nonce = raw.copyOfRange(0, NONCE_LEN)
            // 剩余 = ciphertext || tag(16)：JCE GCM 解密时内部自行拆分并校验 tag
            val ctAndTag = raw.copyOfRange(NONCE_LEN, raw.size)
            val key = SecretKeySpec(Base64.decode(keyB64, Base64.NO_WRAP), "AES")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            String(cipher.doFinal(ctAndTag), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
