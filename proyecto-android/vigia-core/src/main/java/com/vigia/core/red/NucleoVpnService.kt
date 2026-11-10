package com.vigia.core.red

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.vigia.core.ui.NotificacionSesion
import com.vigia.core.util.Hilos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * VPN local de la suite (extraída de VigiaVpnService 1.0). Es abstracta: cada app crea una subclase mínima,
 * la declara en su manifiesto y devuelve su [ConfigVpn].
 *
 *     class MiVpnService : NucleoVpnService() {
 *         override fun crearConfig() = ConfigVpn(nombreSesion = "Mi app", notificacion = ..., filtro = miFiltro)
 *     }
 *
 * Modo DNS: solo la dirección falsa 10.77.0.2 entra en el túnel; cada consulta se filtra y se reenvía al DNS
 * real por un socket ligado a la red activa. Modo CONEXIONES: todo el IPv4 pasa por el [Reenviador].
 * Si el reenvío falla de forma repetida, la VPN se apaga sola para devolver internet al teléfono.
 */
abstract class NucleoVpnService : VpnService() {

    private class Ejecucion(
        val interfaz: ParcelFileDescriptor,
        val salida: FileOutputStream,
        val pool: ExecutorService,
        val config: ConfigVpn
    ) {
        @Volatile
        var activa = true
        var hilo: Thread? = null
        var reenviador: Reenviador? = null
    }

    private class Candidato(val red: Network?, val servidor: InetAddress)

    private class Resultado(val datos: ByteArray?, val detalle: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bloqueoEscritura = Any()
    private val cacheApps = ConcurrentHashMap<Int, String>()
    private val fallosSeguidos = AtomicInteger(0)

    @Volatile
    private var ejecucion: Ejecucion? = null

    @Volatile
    private var arrancando = false

    @Volatile
    private var modoDns = "sistema"

    @Volatile
    private var candidatos: List<Candidato> = emptyList()

    @Volatile
    private var candidatosTs = 0L

    @Volatile
    private var preferido: InetAddress? = null

    @Volatile
    private var ultimoStartId = 0

    private var configVigente: ConfigVpn? = null

    /** La app devuelve aquí su configuración. Se llama al arrancar el servicio; no debe lanzar excepciones. */
    protected abstract fun crearConfig(): ConfigVpn

    /** Cambia el texto de la notificación persistente (por ejemplo para mostrar el contador de bloqueos). */
    protected fun actualizarNotificacion(datos: NotificacionSesion.Datos) {
        NotificacionSesion.actualizar(this, datos, javaClass)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ultimoStartId = startId
        if (intent != null && NotificacionSesion.ACCION_DETENER == intent.action) {
            val actual = ejecucion
            if (actual != null) limpiar(actual) else terminar()
            return START_NOT_STICKY
        }
        val cfg = configVigente ?: crearConfig().also { configVigente = it }
        // Android exige llamar a startForeground enseguida tras startForegroundService.
        NotificacionSesion.publicarPrimerPlano(this, cfg.notificacion, javaClass)
        if (ejecucion == null && !arrancando) arrancar(cfg)
        return if (cfg.reiniciarSiMuere) START_STICKY else START_NOT_STICKY
    }

    override fun onRevoke() {
        // El usuario (o el sistema) ha apagado la VPN: se cierra el túnel.
        val actual = ejecucion
        if (actual != null) limpiar(actual)
        super.onRevoke()
    }

    override fun onDestroy() {
        val ej = ejecucion
        if (ej != null) {
            ej.activa = false
            try {
                ej.interfaz.close()
            } catch (e: IOException) {
            }
            ej.hilo?.interrupt()
            ej.reenviador?.cerrarTodo()
            ej.pool.shutdownNow()
        }
        ejecucion = null
        VpnEstado.activa.value = false
        scope.cancel()
        super.onDestroy()
    }

    private fun terminar() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
        }
        stopSelf(ultimoStartId)
    }

    // ------------------------------------------------------------------ arranque

    private fun arrancar(cfg: ConfigVpn) {
        arrancando = true
        VpnEstado.mensaje.value = null
        fallosSeguidos.set(0)
        modoDns = cfg.dnsUpstream
        scope.launch {
            try {
                val ej = abrirTunel(cfg)
                if (ej == null) {
                    VpnEstado.mensaje.value = "No se pudo crear la VPN. Comprueba que la app tiene permiso de VPN."
                    terminar()
                    return@launch
                }
                ejecucion = ej
                VpnEstado.activa.value = true
                refrescarCandidatos()
                if (cfg.modo == ModoVpn.CONEXIONES) ej.reenviador = crearReenviador(ej)
                val entrada = FileInputStream(ej.interfaz.fileDescriptor)
                val hilo = Thread { bucleLectura(ej, entrada) }
                hilo.isDaemon = true
                ej.hilo = hilo
                hilo.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VpnEstado.mensaje.value = "No se pudo iniciar la VPN: " + e.javaClass.simpleName + " " + (e.message ?: "")
                val actual = ejecucion
                if (actual != null) limpiar(actual) else terminar()
            } finally {
                arrancando = false
            }
        }
    }

    private fun abrirTunel(cfg: ConfigVpn): Ejecucion? {
        val builder = Builder()
            .setSession(cfg.nombreSesion)
            .setMtu(1500)
            .addAddress(IP_CLIENTE, 32)
            .addDnsServer(IP_DNS)
            .setBlocking(true)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        if (cfg.modo == ModoVpn.CONEXIONES) {
            // Todo el tráfico IPv4 pasa por el túnel, salvo el de la propia app y las exclusiones pedidas.
            builder.addRoute("0.0.0.0", 0)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
            }
        } else {
            // Modo DNS: solo la dirección falsa del servidor DNS entra en el túnel.
            builder.addRoute(IP_DNS, 32)
        }
        // Apps excluidas: no usan el túnel (en modo DNS resuelven con el DNS normal de la red).
        for (p in cfg.excluirApps) {
            try {
                builder.addDisallowedApplication(p)
            } catch (e: Exception) {
            }
        }
        val pfd = builder.establish() ?: return null
        val salida = FileOutputStream(pfd.fileDescriptor)
        val pool = Hilos.grupoFijo(6, "vc-dns")
        return Ejecucion(pfd, salida, pool, cfg)
    }

    private fun limpiar(ej: Ejecucion) {
        synchronized(ej) {
            if (!ej.activa) return
            ej.activa = false
        }
        if (ejecucion === ej) {
            ejecucion = null
            VpnEstado.activa.value = false
        }
        try {
            ej.interfaz.close()
        } catch (e: IOException) {
        }
        ej.hilo?.interrupt()
        ej.reenviador?.cerrarTodo()
        ej.pool.shutdown()
        scope.launch(Dispatchers.IO) {
            try {
                ej.pool.awaitTermination(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
            }
            if (ejecucion == null) terminar()
        }
    }

    // ------------------------------------------------------------------ lectura del túnel

    private fun bucleLectura(ej: Ejecucion, entrada: FileInputStream) {
        val buf = ByteArray(32767)
        while (ej.activa) {
            val n = try {
                entrada.read(buf)
            } catch (e: IOException) {
                -1
            }
            if (n < 0) break
            if (n == 0) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            try {
                procesar(ej, buf, n)
            } catch (e: Exception) {
                // Un paquete mal formado nunca debe parar el bucle.
            }
        }
    }

    private fun procesar(ej: Ejecucion, buf: ByteArray, n: Int) {
        if (n < 20) return
        if ((Paquetes.u8(buf, 0) shr 4) != 4) return
        val ihl = (Paquetes.u8(buf, 0) and 0x0F) * 4
        if (ihl < 20 || n < ihl + 8) return
        val proto = Paquetes.u8(buf, 9)
        val origen = Paquetes.ip4(buf, 12)
        val destino = Paquetes.ip4(buf, 16)
        if (destino != IP_DNS_INT) {
            ej.reenviador?.paquete(buf, n, ihl, proto, origen, destino)
            return
        }

        if (proto == Paquetes.PROTO_UDP) {
            val puertoOrigen = Paquetes.u16(buf, ihl)
            val puertoDestino = Paquetes.u16(buf, ihl + 2)
            if (puertoDestino != 53) return
            val udpLen = Paquetes.u16(buf, ihl + 4)
            val cargaLen = minOf(udpLen - 8, n - ihl - 8)
            if (cargaLen < 12) return
            val consulta = buf.copyOfRange(ihl + 8, ihl + 8 + cargaLen)
            try {
                ej.pool.execute { manejarConsulta(ej, origen, puertoOrigen, consulta) }
            } catch (e: RejectedExecutionException) {
            }
        } else if (proto == Paquetes.PROTO_TCP) {
            if (n < ihl + 20) return
            val flags = Paquetes.u8(buf, ihl + 13)
            val esSyn = (flags and 0x02) != 0 && (flags and 0x10) == 0
            if (esSyn) {
                // DNS sobre TCP / DNS privado (DoT): se rechaza al instante para que el sistema use UDP.
                val puertoOrigen = Paquetes.u16(buf, ihl)
                val puertoDestino = Paquetes.u16(buf, ihl + 2)
                val seq = Paquetes.u32(buf, ihl + 4)
                val rst = Paquetes.construirRstTcp(destino, origen, puertoDestino, puertoOrigen, seq)
                escribirTunel(ej, rst)
            }
        }
    }

    private fun escribirTunel(ej: Ejecucion, paquete: ByteArray) {
        synchronized(bloqueoEscritura) {
            try {
                ej.salida.write(paquete)
            } catch (e: IOException) {
            }
        }
    }

    // ------------------------------------------------------------------ consultas DNS

    private fun manejarConsulta(ej: Ejecucion, origenIp: Int, puertoOrigen: Int, consulta: ByteArray) {
        val cfg = ej.config
        val obs = cfg.observador
        val detalle = obs != null && obs.quiereDetalle && cfg.grabando()
        val ahora = System.currentTimeMillis()
        val q = DnsParser.parseConsulta(consulta)
        // Averiguar la app de origen cuesta una llamada al sistema por consulta: solo si alguien la quiere.
        val uid = if (detalle) resolverUid(origenIp, puertoOrigen) else -1

        val filtro = cfg.filtro
        if (q != null && filtro.hayDominios && filtro.dominioBloqueado(q.nombre)) {
            val respuesta = DnsParser.construirBloqueo(consulta, cfg.respuestaBloqueo)
            escribirTunel(ej, Paquetes.construirUdp(IP_DNS_INT, origenIp, 53, puertoOrigen, respuesta))
            if (obs != null) {
                obs.alBloqueo(q.nombre)
                if (detalle) {
                    obs.alDns(
                        EventoDns(
                            ts = ahora, uid = uid, app = nombreApp(uid), dominio = q.nombre,
                            tipo = DnsParser.nombreTipo(q.tipo), respuesta = "bloqueado", bloqueado = true
                        )
                    )
                }
            }
            return
        }

        val resultado = reenviar(consulta)
        val datos = resultado.datos
        if (datos != null) {
            fallosSeguidos.set(0)
            escribirTunel(ej, Paquetes.construirUdp(IP_DNS_INT, origenIp, 53, puertoOrigen, datos))
        } else {
            val fallos = fallosSeguidos.incrementAndGet()
            if (fallos >= MAX_FALLOS && ej.activa) {
                // Modo a prueba de fallos: si el DNS real no contesta, se apaga la VPN para no dejar
                // al teléfono sin internet.
                VpnEstado.mensaje.value = "Túnel detenido: " + resultado.detalle
                limpiar(ej)
            }
        }

        if (detalle && obs != null && q != null) {
            val texto = if (datos != null) DnsParser.resumenRespuesta(datos) else resultado.detalle
            obs.alDns(
                EventoDns(
                    ts = ahora, uid = uid, app = nombreApp(uid), dominio = q.nombre,
                    tipo = DnsParser.nombreTipo(q.tipo), respuesta = texto, bloqueado = false
                )
            )
        }
    }

    /** Envía la consulta a los DNS candidatos, uno tras otro, hasta que alguno responda. */
    private fun reenviar(consulta: ByteArray): Resultado {
        var lista = candidatosVigentes()
        var ultimoError = "no hay servidores DNS disponibles"
        var pasada = 0
        while (pasada < 2) {
            val pref = preferido
            val ordenada = if (pref == null) lista else lista.sortedBy { if (it.servidor == pref) 0 else 1 }
            for (c in ordenada) {
                val host = c.servidor.hostAddress ?: "?"
                try {
                    val socket = DatagramSocket()
                    try {
                        val red = c.red
                        if (red != null) red.bindSocket(socket) else protect(socket)
                        socket.soTimeout = 2000
                        socket.send(DatagramPacket(consulta, consulta.size, c.servidor, 53))
                        val recibido = ByteArray(4096)
                        val paquete = DatagramPacket(recibido, recibido.size)
                        socket.receive(paquete)
                        preferido = c.servidor
                        return Resultado(recibido.copyOf(paquete.length), "")
                    } finally {
                        socket.close()
                    }
                } catch (e: SocketTimeoutException) {
                    ultimoError = "sin respuesta de " + host + " (tiempo agotado)"
                } catch (e: Exception) {
                    ultimoError = "error de red con " + host + ": " + e.javaClass.simpleName + " " + (e.message ?: "")
                }
            }
            pasada++
            if (pasada == 1) {
                // Puede que la red haya cambiado: se vuelve a mirar y solo se reintenta si hay otros servidores.
                val antes = lista.map { it.servidor }
                lista = refrescarCandidatos()
                if (lista.map { it.servidor } == antes) break
            }
        }
        return Resultado(null, ultimoError)
    }

    private fun candidatosVigentes(): List<Candidato> {
        val ahora = System.currentTimeMillis()
        val actual = candidatos
        if (actual.isEmpty() || ahora - candidatosTs > 15000L) {
            return refrescarCandidatos()
        }
        return actual
    }

    private fun refrescarCandidatos(): List<Candidato> {
        val ahora = System.currentTimeMillis()
        if (candidatos.isNotEmpty() && ahora - candidatosTs < 3000L) return candidatos
        val nueva = construirCandidatos()
        candidatos = nueva
        candidatosTs = ahora
        return nueva
    }

    /**
     * Lista ordenada de (red, servidor DNS de esa red). Con un servidor fijo elegido solo hay un candidato.
     * Con «sistema», primero las redes validadas y rápidas (wifi/ethernet), luego las de datos móviles.
     */
    private fun construirCandidatos(): List<Candidato> {
        if (modoDns != "sistema") {
            return try {
                listOf(Candidato(null, InetAddress.getByName(modoDns)))
            } catch (e: Exception) {
                emptyList()
            }
        }
        val puntuados = ArrayList<Pair<Int, Candidato>>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            for (red in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(red) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val lp = cm.getLinkProperties(red) ?: continue
                var puntos = 0
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) puntos += 10
                val rapida = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!rapida) puntos += 1
                for (d in lp.dnsServers) {
                    val familia = if (d is Inet4Address) 0 else 1
                    puntuados.add(Pair(puntos * 2 + familia, Candidato(red, d)))
                }
            }
        } catch (e: Exception) {
        }
        return puntuados.sortedBy { it.first }.map { it.second }.take(4)
    }

    // ------------------------------------------------------------------ modo conexiones

    private fun crearReenviador(ej: Ejecucion): Reenviador {
        val cfg = ej.config
        val obs = cfg.observador
        return Reenviador(
            bloquearQuic = cfg.bloquearQuic,
            filtro = cfg.filtro,
            respuestaBloqueo = cfg.respuestaBloqueo,
            escribir = { paquete -> escribirTunel(ej, paquete) },
            protegerTcp = { socket -> protect(socket) },
            protegerUdp = { socket -> protect(socket) },
            uidDe = { proto, puertoOrigen, destinoIp, puertoDestino -> uidDeFlujo(proto, puertoOrigen, destinoIp, puertoDestino) },
            nombreApp = { uid -> nombreApp(uid) },
            grabando = { obs != null && obs.quiereDetalle && cfg.grabando() },
            alEvento = { evento -> obs?.alConexion(evento) },
            alDns = { uid, aplicacion, consulta, respuesta -> registrarDnsExterno(ej, uid, aplicacion, consulta, respuesta) },
            alBloqueo = { dominio -> obs?.alBloqueo(dominio) },
            alFallar = { motivo -> alFallarReenvio(ej, motivo) }
        )
    }

    private fun alFallarReenvio(ej: Ejecucion, motivo: String) {
        VpnEstado.mensaje.value = "Captura de conexiones detenida: " + motivo +
            ". Se apagó la VPN para que el teléfono recupere internet."
        scope.launch(Dispatchers.Default) { limpiar(ej) }
    }

    private fun uidDeFlujo(proto: Int, puertoOrigen: Int, destinoIp: Int, puertoDestino: Int): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(Paquetes.ipObjeto(IP_CLIENTE_INT), puertoOrigen)
            val remoto = InetSocketAddress(Paquetes.ipObjeto(destinoIp), puertoDestino)
            val protocolo = if (proto == Paquetes.PROTO_TCP) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
            cm.getConnectionOwnerUid(protocolo, local, remoto)
        } catch (e: Exception) {
            -1
        }
    }

    /** Consultas DNS enviadas directamente a otro servidor (por ejemplo 8.8.8.8): también se anotan. */
    private fun registrarDnsExterno(ej: Ejecucion, uid: Int, aplicacion: String, consulta: ByteArray, respuesta: ByteArray) {
        val obs = ej.config.observador ?: return
        if (!obs.quiereDetalle || !ej.config.grabando()) return
        val q = DnsParser.parseConsulta(consulta) ?: return
        obs.alDns(
            EventoDns(
                ts = System.currentTimeMillis(), uid = uid, app = aplicacion, dominio = q.nombre,
                tipo = DnsParser.nombreTipo(q.tipo), respuesta = DnsParser.resumenRespuesta(respuesta), bloqueado = false
            )
        )
    }

    // ------------------------------------------------------------------ ayudas

    private fun resolverUid(origenIp: Int, puertoOrigen: Int): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(Paquetes.ipObjeto(origenIp), puertoOrigen)
            val remoto = InetSocketAddress(Paquetes.ipObjeto(IP_DNS_INT), 53)
            cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remoto)
        } catch (e: Exception) {
            -1
        }
    }

    private fun nombreApp(uid: Int): String {
        if (uid < 0) return "desconocida"
        val guardado = cacheApps[uid]
        if (guardado != null) return guardado
        val paquetes = try {
            packageManager.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        }
        val nombre = if (paquetes != null && paquetes.isNotEmpty()) paquetes[0] else "uid:" + uid
        cacheApps[uid] = nombre
        return nombre
    }

    companion object {
        const val IP_CLIENTE = "10.77.0.1"
        const val IP_DNS = "10.77.0.2"
        const val MAX_FALLOS = 8
        val IP_DNS_INT: Int = Paquetes.ip4(byteArrayOf(10, 77, 0, 2), 0)
        val IP_CLIENTE_INT: Int = Paquetes.ip4(byteArrayOf(10, 77, 0, 1), 0)
    }
}
