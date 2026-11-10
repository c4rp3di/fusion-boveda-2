package com.vigia.core.red

import com.vigia.core.ui.NotificacionSesion
import kotlinx.coroutines.flow.MutableStateFlow

/** DNS: solo la consulta DNS entra en el túnel (ligero). CONEXIONES: todo el tráfico IPv4 pasa por el reenviador. */
enum class ModoVpn { DNS, CONEXIONES }

/** Qué contesta el túnel a un dominio bloqueado. */
enum class RespuestaBloqueo { NXDOMAIN, CERO }

/**
 * Todo lo que una app decide sobre su túnel. La rellena la subclase de [NucleoVpnService] en `crearConfig()`.
 *
 * @param dnsUpstream «sistema» (usa los DNS de la red activa) o la IP de un servidor concreto, por ejemplo 1.1.1.1.
 * @param grabando se consulta en cada evento: devolver falso pone en pausa el detalle (no el bloqueo).
 * @param reiniciarSiMuere si es verdadero, Android relanza el servicio tras matarlo (bloqueador 24/7).
 * @param excluirApps paquetes que NO pasan por el túnel (en ambos modos).
 */
class ConfigVpn(
    val nombreSesion: String,
    val notificacion: NotificacionSesion.Datos,
    val modo: ModoVpn = ModoVpn.DNS,
    val dnsUpstream: String = "sistema",
    val bloquearQuic: Boolean = false,
    val filtro: FiltroTrafico = FiltroTrafico.NINGUNO,
    val respuestaBloqueo: RespuestaBloqueo = RespuestaBloqueo.NXDOMAIN,
    val observador: ObservadorTrafico? = null,
    val grabando: () -> Boolean = { true },
    val reiniciarSiMuere: Boolean = false,
    val excluirApps: List<String> = emptyList()
)

/** Estado visible para la interfaz (un único túnel por app). */
object VpnEstado {
    /** ¿Está la VPN local activa ahora mismo? */
    val activa = MutableStateFlow(false)

    /** Motivo legible si el túnel no pudo arrancar o se detuvo solo. */
    val mensaje = MutableStateFlow<String?>(null)
}
