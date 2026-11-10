package com.vigia.boveda

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.content.ContextCompat

/** Estado de «bóveda abierta»: la clave solo existe aquí, en memoria, y se pone a cero al bloquear. */
object SesionBoveda {
    @Volatile
    private var dek: ByteArray? = null

    private var contexto: Context? = null
    private var receptorRegistrado = false
    private var visibles = 0
    private val manejador = Handler(Looper.getMainLooper())
    private var tareaBloqueo: Runnable? = null

    val desbloqueada: Boolean get() = dek != null

    fun clave(): ByteArray? = dek

    fun abrir(c: Context, clave: ByteArray) {
        val app = c.applicationContext
        contexto = app
        dek = clave.copyOf()
        BovedaDb.abrir(app, clave)
        if (!receptorRegistrado) {
            receptorRegistrado = true
            val receptor = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (AjustesBoveda.bloquearAlApagarPantalla(ctx)) bloquear()
                }
            }
            ContextCompat.registerReceiver(app, receptor, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    fun bloquear() {
        val k = dek
        dek = null
        k?.fill(0)
        BovedaDb.cerrar()
        val c = contexto
        if (c != null) PortapapelesSeguro.limpiarSiEsNuestro(c)
    }

    /** Llamado por cada pantalla al hacerse visible: mientras haya una visible no corre el temporizador. */
    fun alIniciarPantalla() {
        visibles++
        val t = tareaBloqueo
        if (t != null) {
            manejador.removeCallbacks(t)
            tareaBloqueo = null
        }
    }

    fun alDetenerPantalla(c: Context) {
        visibles = maxOf(0, visibles - 1)
        if (visibles > 0 || dek == null) return
        val segundos = AjustesBoveda.autobloqueoSegundos(c)
        val t = Runnable { bloquear() }
        tareaBloqueo = t
        manejador.postDelayed(t, segundos * 1000L)
    }
}

/** Copia al portapapeles marcando el contenido como sensible y lo borra tras unos segundos. */
object PortapapelesSeguro {
    private const val ETIQUETA = "boveda_secreto"
    private val manejador = Handler(Looper.getMainLooper())
    private var tarea: Runnable? = null

    fun copiar(c: Context, texto: String, sensible: Boolean = true) {
        val app = c.applicationContext
        val cm = app.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(ETIQUETA, texto)
        if (sensible && Build.VERSION.SDK_INT >= 33) {
            val extras = PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }
        cm.setPrimaryClip(clip)
        val t = tarea
        if (t != null) manejador.removeCallbacks(t)
        val segundos = AjustesBoveda.portapapelesSegundos(app)
        if (segundos > 0) {
            val nueva = Runnable { limpiarSiEsNuestro(app) }
            tarea = nueva
            manejador.postDelayed(nueva, segundos * 1000L)
        }
    }

    /** Solo borra si lo que hay en el portapapeles lo puso La Bóveda (no pisa lo que copiaste después). */
    fun limpiarSiEsNuestro(c: Context) {
        try {
            val cm = c.applicationContext.getSystemService(ClipboardManager::class.java)
            val d = cm.primaryClipDescription
            if (d != null && d.label == ETIQUETA) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            // Android puede negar el acceso al portapapeles en segundo plano: se intenta, sin más.
        }
    }
}
