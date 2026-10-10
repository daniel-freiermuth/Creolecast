package com.creolecast.app.airplay2

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom

class AirPlay2Crypto {

    companion object {
        fun generateCurve25519KeyPair(): AsymmetricCipherKeyPair {
            val generator = X25519KeyPairGenerator()
            generator.init(X25519KeyGenerationParameters(SecureRandom()))
            return generator.generateKeyPair()
        }

        fun curve25519Agree(privateKey: X25519PrivateKeyParameters, publicKey: ByteArray): ByteArray {
            val agreement = X25519Agreement()
            agreement.init(privateKey)
            val shared = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), shared, 0)
            return shared
        }

        fun hkdfSha512(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val generator = HKDFBytesGenerator(SHA512Digest())
            generator.init(HKDFParameters(ikm, salt, info))
            val out = ByteArray(length)
            generator.generateBytes(out, 0, length)
            return out
        }

        fun chacha20Poly1305Encrypt(
            key: ByteArray,
            nonce: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray
        ): Pair<ByteArray, ByteArray> {
            val sealed = chacha20Poly1305(true, key, nonce, plaintext, aad)
            val tagStart = sealed.size - 16
            return Pair(sealed.copyOfRange(0, tagStart), sealed.copyOfRange(tagStart, sealed.size))
        }

        /** Throws [org.bouncycastle.crypto.InvalidCipherTextException] on a tag mismatch. */
        fun chacha20Poly1305Decrypt(
            key: ByteArray,
            nonce: ByteArray,
            ciphertext: ByteArray,
            aad: ByteArray,
            tag: ByteArray
        ): ByteArray = chacha20Poly1305(false, key, nonce, ciphertext + tag, aad)

        /**
         * RFC 8439 AEAD via BouncyCastle's lightweight engine. The JCE name
         * "ChaCha20-Poly1305" exists on desktop JDKs but not in Android's
         * Conscrypt, so going through [javax.crypto.Cipher] failed on devices.
         */
        private fun chacha20Poly1305(
            encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray
        ): ByteArray {
            val aead = ChaCha20Poly1305()
            aead.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(aead.getOutputSize(input.size))
            val n = aead.processBytes(input, 0, input.size, out, 0)
            aead.doFinal(out, n)
            return out
        }

        fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            return signer.verifySignature(signature)
        }

        // Raw 32-byte Ed25519 seed (private key material for the long-term pairing identity)
        fun generateEd25519Seed(): ByteArray {
            val seed = ByteArray(32)
            SecureRandom().nextBytes(seed)
            return seed
        }

        fun ed25519PublicFromSeed(seed: ByteArray): ByteArray {
            return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        }

        fun ed25519SignWithSeed(seed: ByteArray, data: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
            signer.update(data, 0, data.size)
            return signer.generateSignature()
        }

        fun getPublicKeyBytes(keyPair: AsymmetricCipherKeyPair): ByteArray {
            return (keyPair.public as X25519PublicKeyParameters).encoded
        }
    }
}
