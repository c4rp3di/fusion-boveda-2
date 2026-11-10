package com.vigia.core.red

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo único que la interfaz de una app necesita saber del túnel. Cada app decide qué hacer con el tráfico
 * (su [ConfigVpn]); la pantalla solo arranca, para y observa.
 *
 *     val vpn = VpnController.crear(this, MiVpnService::class.java)
 *     vpn.permisoPendiente()   // null si ya hay permiso; si no, un Intent para pedirlo
 *     vpn.start(); vpn.stop()
 */
interface VpnController {
    val activa: StateFlow<Boolean>

    val mensaje: StateFlow<String?>

    /** Intent del diálogo de permiso de VPN de Android, o null si la app ya tiene el permiso. */
    fun permisoPendiente(): Intent?

    /** Arranca el túnel como servicio en primer plano. Requiere que el permiso ya esté concedido. */
    fun start()

    fun stop()

    companion object {
        fun crear(contexto: Context, servicio: Class<out NucleoVpnService>): VpnController {
            return ControladorVpn(contexto.applicationContext, servicio)
        }
    }
}

internal class ControladorVpn(
    private val contexto: Context,
    private val servicio: Class<out NucleoVpnService>
) : VpnController {

    override val activa: StateFlow<Boolean> get() = VpnEstado.activa

    override val mensaje: StateFlow<String?> get() = VpnEstado.mensaje

    override fun permisoPendiente(): Intent? = VpnService.prepare(contexto)

    override fun start() {
        ContextCompat.startForegroundService(contexto, Intent(contexto, servicio))
    }

    override fun stop() {
        contexto.stopService(Intent(contexto, servicio))
    }
}
