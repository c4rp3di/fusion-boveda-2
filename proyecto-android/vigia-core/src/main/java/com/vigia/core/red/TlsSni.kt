package com.vigia.core.red

/**
 * Lee el nombre del servidor (SNI) del primer mensaje de una conexión TLS (ClientHello), sin descifrar nada:
 * el SNI viaja en claro. Si el mensaje llega partido en varios segmentos, pide más datos.
 */
object TlsSni {
    const val NO_TLS = 0
    const val NECESITA_MAS = 1
    const val ENCONTRADO = 2
    const val SIN_SNI = 3

    class Resultado(val estado: Int, val nombre: String?)

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    private fun u16(d: ByteArray, i: Int): Int = (u8(d, i) shl 8) or u8(d, i + 1)

    fun analizar(d: ByteArray, n: Int): Resultado {
        if (n < 1) return Resultado(NECESITA_MAS, null)
        if (u8(d, 0) != 0x16) return Resultado(NO_TLS, null)
        if (n < 5) return Resultado(NECESITA_MAS, null)
        if (u8(d, 1) != 3) return Resultado(NO_TLS, null)
        val registro = u16(d, 3)
        if (n < 5 + registro) {
            return if (registro > 16384) Resultado(NO_TLS, null) else Resultado(NECESITA_MAS, null)
        }
        if (registro < 4 || u8(d, 5) != 1) return Resultado(SIN_SNI, null)
        val limite = 5 + registro
        var pos = 9
        try {
            pos += 2 + 32
            pos += 1 + u8(d, pos)
            pos += 2 + u16(d, pos)
            pos += 1 + u8(d, pos)
            if (pos + 2 > limite) return Resultado(SIN_SNI, null)
            val totalExtensiones = u16(d, pos)
            pos += 2
            val fin = minOf(pos + totalExtensiones, limite)
            while (pos + 4 <= fin) {
                val tipo = u16(d, pos)
                val largo = u16(d, pos + 2)
                pos += 4
                if (pos + largo > fin) return Resultado(SIN_SNI, null)
                if (tipo == 0) {
                    if (largo < 5) return Resultado(SIN_SNI, null)
                    val tipoNombre = u8(d, pos + 2)
                    val largoNombre = u16(d, pos + 3)
                    if (tipoNombre == 0 && pos + 5 + largoNombre <= fin) {
                        val nombre = String(d, pos + 5, largoNombre, Charsets.ISO_8859_1).lowercase()
                        return Resultado(ENCONTRADO, nombre)
                    }
                    return Resultado(SIN_SNI, null)
                }
                pos += largo
            }
        } catch (e: IndexOutOfBoundsException) {
            return Resultado(SIN_SNI, null)
        }
        return Resultado(SIN_SNI, null)
    }
}
