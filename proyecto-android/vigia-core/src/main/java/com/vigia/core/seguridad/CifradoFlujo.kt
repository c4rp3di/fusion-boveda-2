package com.vigia.core.seguridad

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de archivos grandes en streaming (fotos, PDFs, vídeos) con AES-256-GCM por bloques de 64 KiB: nunca
 * se carga el archivo entero en memoria.
 *
 * Formato: «VCF1» (4) · sal (16) · prefijo de nonce (4) · [longitud del bloque (4, big endian) · bloque cifrado
 * con su etiqueta GCM]... Cada archivo usa una clave propia = HMAC-SHA256(claveMaestra, «VCF1-file» + sal).
 * El nonce de cada bloque es prefijo + contador de 8 bytes, y se autentica además la cabecera y una marca de
 * «último bloque»: reordenar, duplicar o truncar bloques hace fallar el descifrado.
 */
object CifradoFlujo {
    const val TAM_BLOQUE = 65536
    private const val TAG = 16
    private val MAGIA = byteArrayOf(0x56, 0x43, 0x46, 0x31)
    private const val TAM_CABECERA = 24

    fun cifrar(entrada: InputStream, salida: OutputStream, claveMaestra: ByteArray) {
        val azar = SecureRandom()
        val sal = ByteArray(16)
        val prefijo = ByteArray(4)
        azar.nextBytes(sal)
        azar.nextBytes(prefijo)
        val cabecera = ByteArray(TAM_CABECERA)
        System.arraycopy(MAGIA, 0, cabecera, 0, 4)
        System.arraycopy(sal, 0, cabecera, 4, 16)
        System.arraycopy(prefijo, 0, cabecera, 20, 4)
        val clave = derivar(claveMaestra, sal)
        try {
            salida.write(cabecera)
            val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
            val llave = SecretKeySpec(clave, "AES")
            val actual = ByteArray(TAM_BLOQUE)
            val siguiente = ByteArray(TAM_BLOQUE)
            var n = leerLleno(entrada, actual)
            var contador = 0L
            while (true) {
                var m = 0
                var ultimo = true
                if (n == TAM_BLOQUE) {
                    m = leerLleno(entrada, siguiente)
                    ultimo = m == 0
                }
                cifrador.init(Cipher.ENCRYPT_MODE, llave, GCMParameterSpec(128, nonce(prefijo, contador)))
                cifrador.updateAAD(cabecera)
                cifrador.updateAAD(byteArrayOf(if (ultimo) 1 else 0))
                val cifrado = cifrador.doFinal(actual, 0, n)
                salida.write(ByteBuffer.allocate(4).putInt(cifrado.size).array())
                salida.write(cifrado)
                if (ultimo) break
                System.arraycopy(siguiente, 0, actual, 0, m)
                n = m
                contador++
            }
            salida.flush()
        } finally {
            clave.fill(0)
        }
    }

    /**
     * @throws IllegalArgumentException si no es un archivo de este formato o está truncado.
     * @throws IllegalStateException si un bloque no supera la autenticación (archivo dañado o clave incorrecta).
     * Si falla a mitad, parte del contenido ya se habrá escrito en [salida]: quien llama debe descartarla.
     */
    fun descifrar(entrada: InputStream, salida: OutputStream, claveMaestra: ByteArray) {
        val cabecera = ByteArray(TAM_CABECERA)
        if (leerLleno(entrada, cabecera) != TAM_CABECERA) throw IllegalArgumentException("archivo cifrado incompleto")
        for (i in 0 until 4) {
            if (cabecera[i] != MAGIA[i]) throw IllegalArgumentException("formato no reconocido")
        }
        val sal = cabecera.copyOfRange(4, 20)
        val prefijo = cabecera.copyOfRange(20, 24)
        val clave = derivar(claveMaestra, sal)
        try {
            val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
            val llave = SecretKeySpec(clave, "AES")
            var actual = leerBloque(entrada) ?: throw IllegalArgumentException("archivo cifrado sin datos")
            var contador = 0L
            while (true) {
                val siguiente = leerBloque(entrada)
                val ultimo = siguiente == null
                cifrador.init(Cipher.DECRYPT_MODE, llave, GCMParameterSpec(128, nonce(prefijo, contador)))
                cifrador.updateAAD(cabecera)
                cifrador.updateAAD(byteArrayOf(if (ultimo) 1 else 0))
                val plano = try {
                    cifrador.doFinal(actual)
                } catch (e: GeneralSecurityException) {
                    throw IllegalStateException("archivo dañado o clave incorrecta")
                }
                salida.write(plano)
                if (siguiente == null) break
                actual = siguiente
                contador++
            }
            salida.flush()
        } finally {
            clave.fill(0)
        }
    }

    private fun derivar(claveMaestra: ByteArray, sal: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(claveMaestra, "HmacSHA256"))
        mac.update("VCF1-file".toByteArray(Charsets.US_ASCII))
        mac.update(sal)
        return mac.doFinal()
    }

    private fun nonce(prefijo: ByteArray, contador: Long): ByteArray {
        val b = ByteBuffer.allocate(12)
        b.put(prefijo)
        b.putLong(contador)
        return b.array()
    }

    private fun leerBloque(entrada: InputStream): ByteArray? {
        val l = ByteArray(4)
        val n = leerLleno(entrada, l)
        if (n == 0) return null
        if (n < 4) throw IllegalArgumentException("archivo cifrado truncado")
        val largo = ByteBuffer.wrap(l).int
        if (largo < TAG || largo > TAM_BLOQUE + TAG) throw IllegalArgumentException("bloque cifrado no válido")
        val datos = ByteArray(largo)
        if (leerLleno(entrada, datos) != largo) throw IllegalArgumentException("archivo cifrado truncado")
        return datos
    }

    /** Lee hasta llenar el búfer o llegar al final; devuelve los bytes leídos. */
    private fun leerLleno(entrada: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = entrada.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
