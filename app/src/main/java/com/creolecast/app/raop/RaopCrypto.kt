package com.creolecast.app.raop

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider

class RaopCrypto {
    companion object {
        // The bundled (full) Bouncy Castle provider, passed by instance: looking "BC" up
        // by name would return Android's stripped platform provider instead.
        private val BOUNCY_CASTLE = BouncyCastleProvider()

        // AirPort Express RSA public key modulus (2048-bit, e = 65537). Receivers decrypt
        // a=rsaaeskey with the matching private key: shairplay's airport.key,
        // shairport-sync's super_secret_key.
        private val AIRPORT_RSA_MODULUS = BigInteger(
            "e7d744f2a2e2788b6c1f55a08eb70544a8fa7945aa8be6c62ce5f51cbdd4dc68" +
                "42fe3d1083dd2edec1bfd4252dc02e6f398bdf0e6148ea84855e2e442da6d626" +
                "64f674a1f304929ade4f6893ef2df6e711a8c77a0d91c9d980822e50d12922af" +
                "ea40ea9f0e14c0f76938c5f3882fc0323dd9fe55155f51bb5921c201629fd733" +
                "52d5e2efaabf9ba048d7b813a2b6767f6c3ccf1eb4ce673d037b0d2ea30c5fff" +
                "eb06f8d08adde409571a9c689fef10728855dd8cfb9a8bef5c8943ef3b5faa15" +
                "dde698beddf3599603eb3e6f61372bb628f6559f599a78bf500687aa7f4976c0" +
                "562d412956f8989e18a6355bd81597825e0fc875343ec782117625cdbf98447b",
            16
        )

        fun encryptAesKey(aesKey: ByteArray): ByteArray {
            val spec = RSAPublicKeySpec(AIRPORT_RSA_MODULUS, BigInteger.valueOf(65537))
            val factory = KeyFactory.getInstance("RSA")
            val publicKey = factory.generatePublic(spec)

            // RAOP uses RSA-OAEP with SHA-1
            val cipher = Cipher.getInstance("RSA/None/OAEPWithSHA1AndMGF1Padding", BOUNCY_CASTLE)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return cipher.doFinal(aesKey)
        }
    }

    private lateinit var secretKeySpec: SecretKeySpec
    private lateinit var ivSpec: IvParameterSpec

    fun initAes(aesKey: ByteArray, iv: ByteArray) {
        secretKeySpec = SecretKeySpec(aesKey, "AES")
        ivSpec = IvParameterSpec(iv)
    }

    /**
     * Encrypts audio data using AES-128-CBC.
     * In RAOP, the IV is reset for every packet.
     */
    fun encryptAudio(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding", BOUNCY_CASTLE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)

        return if (data.size % 16 == 0) {
            cipher.doFinal(data)
        } else {
            val paddedSize = ((data.size + 15) / 16) * 16
            val paddedData = ByteArray(paddedSize)
            System.arraycopy(data, 0, paddedData, 0, data.size)
            cipher.doFinal(paddedData)
        }
    }
}
