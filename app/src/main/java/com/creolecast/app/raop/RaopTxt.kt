package com.creolecast.app.raop

/** TXT `et` values: no encryption, and RSA-wrapped AES (FairPlay and MFi variants are 3-5). */
private const val ET_NONE = 0
private const val ET_RSA = 1

/**
 * The RAOP (`_raop._tcp`) TXT fields a sender acts on. `et` is a comma-separated list
 * ("0,1,3"), not a single value or a bitmask.
 */
object RaopTxt {
    fun intList(value: String?): Set<Int> =
        value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()

    /** True when [et] lets us stream at all: unencrypted or RSA/AES (or nothing advertised). */
    fun isSupported(et: Set<Int>): Boolean = et.isEmpty() || ET_NONE in et || ET_RSA in et

    /** RSA/AES is used only when the receiver requires it, i.e. doesn't also accept plain. */
    fun requiresRsa(et: Set<Int>): Boolean = ET_RSA in et && ET_NONE !in et
}
