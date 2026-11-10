package com.vigia.boveda

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vigia.core.seguridad.CifradoArchivos
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Arquitectura de claves:
 *  - Una clave de bóveda (DEK) de 32 bytes aleatorios cifra la base SQLCipher y los archivos.
 *  - La DEK se guarda envuelta con la contraseña maestra (PBKDF2 310 000 iteraciones + AES-256-GCM).
 *  - Opcionalmente, una segunda copia envuelta con una clave del Keystore que exige huella o rostro en cada uso.
 *  Cambiar la contraseña maestra solo reenvuelve la DEK: no hace falta recifrar nada más.
 */
object ClavesBoveda {
    private const val ARCHIVO = "boveda_cab.bin"
    private val cifrador = CifradoArchivos("BOVED1")

    private fun archivo(c: Context) = File(c.applicationContext.filesDir, ARCHIVO)

    fun existe(c: Context): Boolean = archivo(c).exists()

    /** Crea la bóveda y devuelve su clave. */
    fun crear(c: Context, maestra: CharArray): ByteArray {
        val dek = ByteArray(32)
        SecureRandom().nextBytes(dek)
        guardar(c, cifrador.cifrar(dek, maestra))
        return dek
    }

    /** Devuelve la clave de bóveda, o null si la contraseña es incorrecta. */
    fun desbloquear(c: Context, maestra: CharArray): ByteArray? {
        return try {
            cifrador.descifrar(archivo(c).readBytes(), maestra)
        } catch (e: IllegalStateException) {
            null
        }
    }

    /** Cambia la contraseña maestra. Devuelve false si la actual no es correcta. */
    fun cambiarMaestra(c: Context, actual: CharArray, nueva: CharArray): Boolean {
        val dek = desbloquear(c, actual) ?: return false
        guardar(c, cifrador.cifrar(dek, nueva))
        dek.fill(0)
        return true
    }

    private fun guardar(c: Context, datos: ByteArray) {
        val destino = archivo(c)
        val tmp = File(destino.parentFile, ARCHIVO + ".tmp")
        tmp.writeBytes(datos)
        if (!tmp.renameTo(destino)) {
            destino.delete()
            tmp.renameTo(destino)
        }
    }
}

/** Desbloqueo con huella o rostro (solo biometría fuerte, atada a una clave del Keystore). */
object BiometriaBoveda {
    private const val ALIAS = "boveda_bio_key"
    private const val PREFS = "boveda_bio"
    private const val BLOB = "dek_blob"

    fun disponible(c: Context): Boolean {
        return BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun activada(c: Context): Boolean {
        return c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BLOB, null) != null
    }

    fun desactivar(c: Context) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(BLOB).apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(ALIAS)
        } catch (e: Exception) {
        }
    }

    private fun clave(crear: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existente = ks.getKey(ALIAS, null)
        if (existente is SecretKey) return existente
        if (!crear) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return gen.generateKey()
    }

    private fun info(): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle("La Bóveda")
            .setSubtitle("Confirma tu identidad")
            .setNegativeButtonText("Usar contraseña maestra")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
    }

    /** Guarda una copia de la clave de bóveda protegida por la biometría. Requiere la bóveda desbloqueada. */
    fun activar(act: FragmentActivity, dek: ByteArray, alExito: () -> Unit, alError: (String) -> Unit) {
        val cipher: Cipher
        try {
            desactivar(act)
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, clave(true))
        } catch (e: Exception) {
            alError("No se pudo preparar la clave biométrica: " + e.javaClass.simpleName)
            return
        }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult) {
                try {
                    val c = resultado.cryptoObject?.cipher ?: throw IllegalStateException("sin cifrador")
                    val blob = c.iv + c.doFinal(dek)
                    act.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
                    alExito()
                } catch (e: Exception) {
                    desactivar(act)
                    alError("No se pudo guardar la clave: " + e.javaClass.simpleName)
                }
            }

            override fun onAuthenticationError(codigo: Int, mensaje: CharSequence) {
                alError(mensaje.toString())
            }
        })
        prompt.authenticate(info(), BiometricPrompt.CryptoObject(cipher))
    }

    /**
     * Pide la biometría y entrega la clave de bóveda. [alMaestra] se llama si la persona prefiere la contraseña
     * (o si la clave quedó invalidada porque se añadió otra huella: en ese caso se desactiva la biometría).
     */
    fun desbloquear(act: FragmentActivity, alExito: (ByteArray) -> Unit, alError: (String) -> Unit, alMaestra: () -> Unit) {
        val guardado = act.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BLOB, null)
        if (guardado == null) {
            alMaestra()
            return
        }
        val blob = Base64.decode(guardado, Base64.NO_WRAP)
        val cipher: Cipher
        try {
            val k = clave(false)
            if (k == null) {
                desactivar(act)
                alMaestra()
                return
            }
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        } catch (e: KeyPermanentlyInvalidatedException) {
            desactivar(act)
            alError("Cambiaron las huellas del teléfono: la biometría se desactivó. Usa tu contraseña maestra.")
            alMaestra()
            return
        } catch (e: Exception) {
            alError("No se pudo usar la biometría: " + e.javaClass.simpleName)
            alMaestra()
            return
        }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult) {
                try {
                    val c = resultado.cryptoObject?.cipher ?: throw IllegalStateException("sin cifrador")
                    alExito(c.doFinal(blob, 12, blob.size - 12))
                } catch (e: Exception) {
                    alError("No se pudo descifrar con la biometría.")
                    alMaestra()
                }
            }

            override fun onAuthenticationError(codigo: Int, mensaje: CharSequence) {
                if (codigo == BiometricPrompt.ERROR_NEGATIVE_BUTTON || codigo == BiometricPrompt.ERROR_USER_CANCELED) {
                    alMaestra()
                } else {
                    alError(mensaje.toString())
                    alMaestra()
                }
            }
        })
        prompt.authenticate(info(), BiometricPrompt.CryptoObject(cipher))
    }
}
