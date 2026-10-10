package com.creolecast.app.raop

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.RSAPrivateKeySpec
import java.util.Base64
import javax.crypto.Cipher
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RaopCryptoTest {
    @Test
    fun encryptAesKeyIsDecryptableWithAirportExpressPrivateKey() {
        // Receivers (shairport-sync, shairplay) RSA-OAEP/SHA-1 decrypt a=rsaaeskey with this key.
        val aesKey = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance(
            "RSA/None/OAEPWithSHA1AndMGF1Padding",
            BouncyCastleProvider()
        )
        cipher.init(Cipher.DECRYPT_MODE, airportExpressPrivateKey())

        assertArrayEquals(aesKey, cipher.doFinal(RaopCrypto.encryptAesKey(aesKey)))
    }

    @Test
    fun `audio is AES-128-CBC restarted from the session IV on every packet`() {
        // NIST SP 800-38A, F.2.1 CBC-AES128.Encrypt
        val crypto = RaopCrypto()
        crypto.initAes(
            hex("2b7e151628aed2a6abf7158809cf4f3c"),
            hex("000102030405060708090a0b0c0d0e0f")
        )
        val plaintext = hex(
            "6bc1bee22e409f96e93d7e117393172a" + "ae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52ef" + "f69f2445df4f9b17ad2b417be66c3710"
        )
        val expected = hex(
            "7649abac8119b246cee98e9b12e9197d" + "5086cb9b507219ee95db113a917678b2" +
                "73bed6b8e3c1743b7116e69e22229516" + "3ff1caa1681fac09120eca307586e1a7"
        )

        assertArrayEquals(expected, crypto.encryptAudio(plaintext))
        assertArrayEquals(
            "second packet must not chain from the first",
            expected,
            crypto.encryptAudio(plaintext)
        )
    }

    @Test
    fun `trailing partial block stays in the clear and the length is unchanged`() {
        val crypto = RaopCrypto().apply { initAes(ByteArray(16) { 1 }, ByteArray(16) { 2 }) }
        val alacFrame = ByteArray(1412) { (it * 13).toByte() }
        val encrypted = crypto.encryptAudio(alacFrame)

        assertEquals(alacFrame.size, encrypted.size)
        val whole = 1412 / 16 * 16
        assertFalse(alacFrame.copyOf(whole).contentEquals(encrypted.copyOf(whole)))
        assertArrayEquals(
            alacFrame.copyOfRange(whole, alacFrame.size),
            encrypted.copyOfRange(whole, encrypted.size)
        )
    }

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun airportExpressPrivateKey(): PrivateKey {
        val pkcs1 = RSAPrivateKey.getInstance(
            Base64.getDecoder().decode(AIRPORT_EXPRESS_PRIVATE_KEY)
        )
        return KeyFactory.getInstance("RSA")
            .generatePrivate(RSAPrivateKeySpec(pkcs1.modulus, pkcs1.privateExponent))
    }

    private companion object {
        // Body of the publicly known AirPort Express RSA private key (PKCS#1 DER), as shipped in
        // shairplay's airport.key and shairport-sync's common.c super_secret_key.
        const val AIRPORT_EXPRESS_PRIVATE_KEY =
            "MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUt" +
                "wC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4U" +
                "wPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf" +
                "/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/" +
                "UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfW" +
                "BLmkzkEiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14nDY4TFQAa" +
                "LlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPscEsA5ltpxOgUGCY7b7ez5" +
                "NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZuNGcJE0uFQEG4Z3BrWP7yoNuSK3dii2jm" +
                "lpPHr0O/KnPQtzI3eguhe0TwUem/eYSdyzMyVx/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurciz" +
                "aaA/L0HIgAmOit1GJA2saMxTVPNhAoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFu" +
                "a39GLS99ZEErhLdrwj8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrnjndM" +
                "oPdevWnVkgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v//mU8eVkQaoANf0Z" +
                "oMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtkrfa7ef+AUb69DNggq4mHQAYBp7L+" +
                "k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQNepUE/yY2PP5CNoFmjedpLHMOPFdVgqDzDFxU8hL" +
                "AoGBANDrr7xAJbqBjHVwIzQ4To9pb4BNeqDndk5Qe7fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvA" +
                "cJyRM9SJ7OKlGt0FMSdJD5KG0XPIpAVNwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf" +
                "54PX4ZGS/Xac1UK+pLkBB+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TFBVmD7fV0Zhov" +
                "17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLalpGSwomSNYJcB9HNMlmhkGzc" +
                "1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gI" +
                "LAuE4Pu13aKiJnfft7hIjbK+5kyb3TysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ" +
                "2gG0N5hvJpzwwhbhXqFKA4zaaSrw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY="
    }
}
