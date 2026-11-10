package com.vigia.core.seguridad

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado de datos con contraseña: AES-256-GCM con clave derivada por PBKDF2-HMAC-SHA256 (310 000 iteraciones).
 * Es el formato de los informes .vigia de Vigía 1.0, generalizado: con la magia «VIGIA1» produce y lee
 * exactamente esos archivos.
 *
 * Formato: [magia] (6 bytes ASCII) · versión de KDF = 1 (1 byte) · iteraciones (4 bytes, big endian) ·
 * sal (16) · IV (12) · texto cifrado con la etiqueta GCM al final.
 *
 * Trabaja sobre arrays en memoria: pensado para informes y documentos, no para vídeos.
 */
class CifradoArchivos(private val magia: String, private val iteraciones: Int = ITERACIONES_POR_DEFECTO) {

    private val bytesMagia: ByteArray = magia.toByteArray(Charsets.US_ASCII)

    init {
        require(bytesMagia.size == 6) { "La magia debe tener exactamente 6 caracteres ASCII" }
    }

    fun cifrar(plano: ByteArray, contrasena: CharArray): ByteArray {
        val azar = SecureRandom()
        val sal = ByteArray(16)
        val iv = ByteArray(12)
        azar.nextBytes(sal)
        azar.nextBytes(iv)
        val clave = pbkdf2(contrasena, sal, iteraciones)
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, iv))
        val cifrado = cifrador.doFinal(plano)
        clave.fill(0)

        val salida = ByteBuffer.allocate(bytesMagia.size + 1 + 4 + sal.size + iv.size + cifrado.size)
        salida.put(bytesMagia)
        salida.put(1.toByte())
        salida.putInt(iteraciones)
        salida.put(sal)
        salida.put(iv)
        salida.put(cifrado)
        return salida.array()
    }

    /**
     * @throws IllegalArgumentException si no es un archivo de esta app o está mal formado.
     * @throws IllegalStateException si la contraseña es incorrecta o el archivo está dañado.
     */
    fun descifrar(datos: ByteArray, contrasena: CharArray): ByteArray {
        if (datos.size < 6 + 1 + 4 + 16 + 12 + 16) throw IllegalArgumentException("archivo demasiado pequeño")
        for (i in bytesMagia.indices) {
            if (datos[i] != bytesMagia[i]) throw IllegalArgumentException("formato no reconocido")
        }
        if (datos[6].toInt() != 1) throw IllegalArgumentException("versión de cifrado desconocida")
        val iter = ByteBuffer.wrap(datos, 7, 4).int
        if (iter < 1000 || iter > 5_000_000) throw IllegalArgumentException("parámetros de cifrado no válidos")
        val sal = datos.copyOfRange(11, 27)
        val iv = datos.copyOfRange(27, 39)
        val cifrado = datos.copyOfRange(39, datos.size)
        val clave = pbkdf2(contrasena, sal, iter)
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.DECRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, iv))
        try {
            return cifrador.doFinal(cifrado)
        } catch (e: BadPaddingException) {
            throw IllegalStateException("contraseña incorrecta o archivo dañado")
        } finally {
            clave.fill(0)
        }
    }

    /** PBKDF2-HMAC-SHA256 con HMAC estándar (funciona igual en todas las versiones de Android, minSdk 24 incluido). */
    private fun pbkdf2(contrasena: CharArray, sal: ByteArray, iter: Int): ByteArray {
        val clavePwd = String(contrasena).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clavePwd, "HmacSHA256"))
        clavePwd.fill(0)
        mac.update(sal)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 2..iter) {
            u = mac.doFinal(u)
            for (j in t.indices) {
                t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return t
    }

    companion object {
        const val ITERACIONES_POR_DEFECTO = 310_000
    }
}
