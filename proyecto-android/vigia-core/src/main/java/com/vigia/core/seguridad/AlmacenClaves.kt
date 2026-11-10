package com.vigia.core.seguridad

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Qué hacer si el Keystore ya no puede descifrar la clave guardada (por ejemplo tras restaurar el teléfono). */
enum class PerdidaClave {
    /** Lanzar [IllegalStateException]. Es lo correcto cuando los datos son valiosos (La Bóveda). */
    LANZAR_ERROR,

    /** Borrar la base y crear una clave nueva. Solo para datos efímeros (las sesiones de Vigía). */
    REGENERAR_Y_BORRAR_BD
}

/**
 * Custodia de claves (extraída de KeyVault de Vigía 1.0).
 *
 * SQLCipher necesita una clave en bytes y las claves del Android Keystore no se pueden exportar. Solución: se
 * genera una clave aleatoria de 32 bytes y se guarda CIFRADA (AES-256-GCM) con una clave que nunca sale del
 * Keystore. Sin el Keystore del dispositivo, lo guardado no sirve para nada.
 *
 * Cada app usa su propio [aliasKeystore] y [nombrePrefs], así que las apps de la suite no comparten claves.
 */
class AlmacenClaves(
    contexto: Context,
    private val aliasKeystore: String,
    private val nombrePrefs: String,
    private val alPerderClave: PerdidaClave = PerdidaClave.LANZAR_ERROR
) {
    private val contexto: Context = contexto.applicationContext

    /** Clave de 32 bytes para SQLCipher. La crea la primera vez. */
    fun obtenerClaveBd(nombreBd: String): ByteArray {
        val prefs = contexto.getSharedPreferences(nombrePrefs, Context.MODE_PRIVATE)
        val guardado = prefs.getString(CLAVE_BLOB, null)
        if (guardado != null) {
            try {
                return desenvolver(Base64.decode(guardado, Base64.NO_WRAP))
            } catch (e: Exception) {
                if (alPerderClave == PerdidaClave.LANZAR_ERROR) {
                    throw IllegalStateException("El Keystore ya no puede descifrar la clave de la base de datos", e)
                }
                contexto.deleteDatabase(nombreBd)
            }
        }
        val nueva = ByteArray(32)
        SecureRandom().nextBytes(nueva)
        val blob = Base64.encodeToString(envolver(nueva), Base64.NO_WRAP)
        prefs.edit().putString(CLAVE_BLOB, blob).commit()
        return nueva
    }

    /** Guarda un texto secreto (por ejemplo un token) cifrado con la clave del Keystore. */
    fun guardarSecreto(nombre: String, valor: String) {
        val prefs = contexto.getSharedPreferences(nombrePrefs, Context.MODE_PRIVATE)
        if (valor.isEmpty()) {
            prefs.edit().remove("sec_" + nombre).commit()
            return
        }
        val blob = Base64.encodeToString(envolver(valor.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("sec_" + nombre, blob).commit()
    }

    fun leerSecreto(nombre: String): String? {
        val prefs = contexto.getSharedPreferences(nombrePrefs, Context.MODE_PRIVATE)
        val guardado = prefs.getString("sec_" + nombre, null) ?: return null
        return try {
            String(desenvolver(Base64.decode(guardado, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** Cifra bytes con la clave del Keystore (AES-256-GCM). Resultado: IV (12 bytes) + texto cifrado con etiqueta. */
    fun envolver(datos: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, claveKeystore())
        val iv = cipher.iv
        val cifrado = cipher.doFinal(datos)
        return iv + cifrado
    }

    fun desenvolver(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, 12)
        val cifrado = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, claveKeystore(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cifrado)
    }

    private fun claveKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existente = ks.getKey(aliasKeystore, null)
        if (existente is SecretKey) return existente
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                aliasKeystore,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val CLAVE_BLOB = "db_key_blob"

        /** Alias y preferencias propios de una app nueva de la suite: [espacio] = «boveda», «adblock», etc. */
        fun paraApp(contexto: Context, espacio: String, perdida: PerdidaClave = PerdidaClave.LANZAR_ERROR): AlmacenClaves {
            return AlmacenClaves(contexto, "suite_wrap_" + espacio, "suite_vault_" + espacio, perdida)
        }

        /** Mismos alias y preferencias que Vigía 1.0: permite que Vigía migre al core sin perder su base. */
        fun compatibleVigia(contexto: Context): AlmacenClaves {
            return AlmacenClaves(contexto, "vigia_wrap_key", "vigia_vault", PerdidaClave.REGENERAR_Y_BORRAR_BD)
        }
    }
}
