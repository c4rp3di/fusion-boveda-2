package com.vigia.boveda

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado extremo a extremo de la respuesta del puente (defensa añadida al permiso de firma).
 *
 *  1. El teclado genera un par EFÍMERO P-256 por petición y envía su clave pública.
 *  2. La Bóveda genera otro par efímero, hace ECDH y deriva una clave AES-256 con HKDF-SHA256.
 *     La derivación incluye el paquete destino y las dos claves públicas.
 *  3. La Bóveda cifra usuario y contraseña con AES-256-GCM. Los datos autenticados (AAD) son
 *     id de petición + versión + paquete destino: una respuesta no sirve para otra petición ni otra app.
 *  4. Las claves privadas efímeras se descartan tras usarse (secreto hacia adelante por petición).
 *
 * Este archivo es IDÉNTICO en las dos apps (solo cambia el paquete).
 */
object PuenteCanal {

    private const val CURVA = "secp256r1"
    private const val TAM_IV = 12
    private const val TAM_ETIQUETA_BITS = 128
    private const val MAX_CAMPO = 8192
    private val ETIQUETA = "VIGIA-PUENTE-TECLADO-v1".toByteArray(Charsets.UTF_8)

    /** Lo que viaja de vuelta: clave pública efímera de La Bóveda + IV + texto cifrado (con la etiqueta GCM al final). */
    class Sobre(val publicaBoveda: ByteArray, val iv: ByteArray, val cifrado: ByteArray)

    fun nuevoParEfimero(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec(CURVA), SecureRandom())
        return g.generateKeyPair()
    }

    fun leerPublica(codificada: ByteArray): PublicKey {
        val k = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(codificada))
        require(k is ECPublicKey && k.params.curve.field.fieldSize == 256) { "clave pública no válida" }
        return k
    }

    fun esPublicaValida(codificada: ByteArray): Boolean {
        return try {
            leerPublica(codificada)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hmac(clave: ByteArray, datos: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(clave, "HmacSHA256"))
        return m.doFinal(datos)
    }

    private fun derivar(
        privada: PrivateKey,
        ajena: PublicKey,
        publicaTeclado: ByteArray,
        publicaBoveda: ByteArray,
        id: ByteArray,
        destino: String
    ): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privada)
        ka.doPhase(ajena, true)
        val secreto = ka.generateSecret()
        // HKDF-SHA256: extraer (sal = id de petición) y expandir un único bloque de 32 bytes.
        val prk = hmac(id, secreto)
        secreto.fill(0)
        val info = ETIQUETA + byteArrayOf(0) + destino.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
            publicaTeclado + publicaBoveda + byteArrayOf(1)
        val okm = hmac(prk, info)
        prk.fill(0)
        return SecretKeySpec(okm, "AES")
    }

    private fun aad(id: ByteArray, destino: String): ByteArray {
        return id + byteArrayOf(PuenteContrato.VERSION.toByte()) + destino.toByteArray(Charsets.UTF_8)
    }

    /** Lado La Bóveda: cifra [claro] para la clave pública efímera del teclado. */
    fun cifrar(publicaTeclado: ByteArray, id: ByteArray, destino: String, claro: ByteArray): Sobre {
        val ajena = leerPublica(publicaTeclado)
        val par = nuevoParEfimero()
        val publicaBoveda = par.public.encoded
        val clave = derivar(par.private, ajena, publicaTeclado, publicaBoveda, id, destino)
        val iv = ByteArray(TAM_IV)
        SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, clave, GCMParameterSpec(TAM_ETIQUETA_BITS, iv))
        c.updateAAD(aad(id, destino))
        return Sobre(publicaBoveda, iv, c.doFinal(claro))
    }

    /** Lado teclado: descifra con su clave privada efímera. Lanza excepción si algo no cuadra (etiqueta GCM inválida, etc.). */
    fun descifrar(privadaTeclado: PrivateKey, publicaTeclado: ByteArray, id: ByteArray, destino: String, sobre: Sobre): ByteArray {
        require(sobre.iv.size == TAM_IV) { "IV no válido" }
        val ajena = leerPublica(sobre.publicaBoveda)
        val clave = derivar(privadaTeclado, ajena, publicaTeclado, sobre.publicaBoveda, id, destino)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(TAM_ETIQUETA_BITS, sobre.iv))
        c.updateAAD(aad(id, destino))
        return c.doFinal(sobre.cifrado)
    }

    /** Formato del texto en claro: [versión][largo usuario][usuario UTF-8][largo clave][clave UTF-8]. */
    fun empaquetar(usuario: ByteArray, clave: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(1 + 4 + usuario.size + 4 + clave.size)
        b.put(PuenteContrato.VERSION.toByte())
        b.putInt(usuario.size)
        b.put(usuario)
        b.putInt(clave.size)
        b.put(clave)
        return b.array()
    }

    /** Devuelve (usuario, clave) como bytes UTF-8. El llamante debe ponerlos a cero cuando termine. */
    fun desempaquetar(claro: ByteArray): Pair<ByteArray, ByteArray> {
        val b = ByteBuffer.wrap(claro)
        require(b.get().toInt() == PuenteContrato.VERSION) { "versión no soportada" }
        val nu = b.getInt()
        require(nu in 0..MAX_CAMPO && nu <= b.remaining() - 4) { "usuario no válido" }
        val usuario = ByteArray(nu)
        b.get(usuario)
        val nc = b.getInt()
        require(nc in 0..MAX_CAMPO && nc == b.remaining()) { "clave no válida" }
        val clave = ByteArray(nc)
        b.get(clave)
        return Pair(usuario, clave)
    }
}
