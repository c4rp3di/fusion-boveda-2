package com.vigia.core.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vigia.core.red.VpnController

/**
 * Actividad base de la suite: resuelve los dos permisos que casi toda app necesita (VPN y notificaciones) sin que
 * cada pantalla repita el código de los lanzadores. Las apps usan el tema `Theme.VigiaCore`.
 */
open class ActividadBase : AppCompatActivity() {

    private var alVpnConcedido: (() -> Unit)? = null

    private val lanzadorVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        val accion = alVpnConcedido
        alVpnConcedido = null
        if (resultado.resultCode == Activity.RESULT_OK && accion != null) accion()
    }

    private val lanzadorNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Si la app ya tiene el permiso de VPN ejecuta [alListo] al instante; si no, abre el diálogo de Android y lo ejecuta al aceptar. */
    protected fun asegurarVpn(controlador: VpnController, alListo: () -> Unit) {
        val intent = controlador.permisoPendiente()
        if (intent == null) {
            alListo()
        } else {
            alVpnConcedido = alListo
            lanzadorVpn.launch(intent)
        }
    }

    /** Android 13+: sin este permiso la notificación persistente queda oculta (el servicio sigue funcionando). */
    protected fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33) {
            val concedido = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!concedido) lanzadorNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
