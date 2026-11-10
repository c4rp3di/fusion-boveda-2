package com.vigia.core.red

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Reenviador de tráfico (Fase 2B, EXPERIMENTAL). Recibe los paquetes IPv4 del túnel y los reenvía a internet
 * con sockets propios: termina cada conexión TCP del lado de la app (handshake, números de secuencia, ventana,
 * retransmisiones) y abre otra conexión real al destino. Solo mira cabeceras: la dirección de destino, el puerto,
 * los bytes y el nombre de servidor (SNI) del primer mensaje TLS. No descifra ni guarda contenido.
 *
 * La lógica se probó contra la pila TCP real de Linux (con y sin pérdida de paquetes) antes de pasarla a Kotlin.
 * Si fallan muchas conexiones seguidas hacia destinos distintos, avisa por [alFallar] para que el servicio apague
 * la VPN y el teléfono recupere internet.
 */
class Reenviador(
    private val bloquearQuic: Boolean,
    private val filtro: FiltroTrafico,
    private val respuestaBloqueo: RespuestaBloqueo,
    private val escribir: (ByteArray) -> Unit,
    private val protegerTcp: (Socket) -> Unit,
    private val protegerUdp: (DatagramSocket) -> Unit,
    private val uidDe: (Int, Int, Int, Int) -> Int,
    private val nombreApp: (Int) -> String,
    private val grabando: () -> Boolean,
    private val alEvento: (EventoRed) -> Unit,
    private val alDns: (Int, String, ByteArray, ByteArray) -> Unit,
    private val alBloqueo: (String) -> Unit,
    private val alFallar: (String) -> Unit
) {
    private class Segmento(val seq: Long, val datos: ByteArray, val fin: Boolean) {
        var enviado = System.currentTimeMillis()
    }

    private class Tcp(val origenIp: Int, val origenPuerto: Int, val destinoIp: Int, val destinoPuerto: Int) {
        val cerrojo = ReentrantLock()
        val hayHueco: Condition = cerrojo.newCondition()

        @Volatile
        var estado = 0 // 0 conectando, 1 establecida, 2 cerrada
        var rcvNxt = 0L
        var sndNxt = 0L
        var sndUna = 0L
        var isn = 0L
        var isnCliente = 0L
        var ventanaCliente = 65535
        var mss = MSS_SALIDA
        var socket: Socket? = null
        val cola = LinkedBlockingQueue<ByteArray>()
        var bytesCola = 0
        var ventAnunciada = 65535
        var clienteCerro = false
        var servidorCerro = false
        var finEnviado = false
        val sinConfirmar = ArrayDeque<Segmento>()
        var actividad = System.currentTimeMillis()
        var rto = 600L
        var reintentos = 0
        var dups = 0
        var recuperacion = false
        var pendUpd = 0
        var tsUpd = 0L
        val fueraOrden = HashMap<Long, ByteArray>()
        var bytesFuera = 0
        var finSeq = -1L
        var bytesIn = 0L
        var bytesOut = 0L
        var emitidoIn = 0L
        var emitidoOut = 0L
        var ultimaEmision = System.currentTimeMillis()
        var emitido = false
        val primeros = ByteArrayOutputStream()
        var sniEstado = 0
        var sni = ""
        var uid = -1
        var app = "desconocida"
        var esperaSni = false
        val retenido = ArrayList<ByteArray>()
        var tRet = 0L
        var bloqueada = false
    }

    private class Udp(
        val origenIp: Int, val origenPuerto: Int, val destinoIp: Int, val destinoPuerto: Int,
        val socket: DatagramSocket
    ) {
        @Volatile
        var ultima = System.currentTimeMillis()

        @Volatile
        var bytesIn = 0L

        @Volatile
        var bytesOut = 0L

        @Volatile
        var cerrado = false
        var emitidoIn = 0L
        var emitidoOut = 0L
        var emitido = false
        var ultimaEmision = System.currentTimeMillis()
        var uid = -1
        var app = "desconocida"

        @Volatile
        var ultimaConsulta: ByteArray? = null
    }

    private val fabrica = ThreadFactory { r ->
        val h = Thread(r)
        h.isDaemon = true
        h
    }
    private val hilos = ThreadPoolExecutor(0, 600, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(), fabrica)
    private val programador = Executors.newSingleThreadScheduledExecutor(fabrica)
    private val sesionesTcp = ConcurrentHashMap<Long, Tcp>()
    private val sesionesUdp = ConcurrentHashMap<Long, Udp>()
    private val azar = SecureRandom()

    @Volatile
    private var activo = true
    private val fallosSeguidos = AtomicInteger(0)
    private val ultimoExito = AtomicLong(System.currentTimeMillis())
    private val destinosFallidos = HashSet<Int>()
    private val fallaAvisada = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        programador.scheduleWithFixedDelay({ mantenimiento() }, 250L, 250L, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------------ entrada desde el túnel

    fun paquete(buf: ByteArray, n: Int, ihl: Int, proto: Int, origen: Int, destino: Int) {
        if (!activo) return
        val total = minOf(Paquetes.u16(buf, 2), n)
        if (proto == Paquetes.PROTO_TCP) {
            tcp(buf, total, ihl, origen, destino)
        } else if (proto == Paquetes.PROTO_UDP) {
            udp(buf, total, ihl, origen, destino)
        }
    }

    fun cerrarTodo() {
        activo = false
        for (t in sesionesTcp.values) cerrar(t, false)
        for (u in sesionesUdp.values) cerrarUdp(claveUdp(u), u)
        hilos.shutdownNow()
        programador.shutdownNow()
    }

    private fun claveTcp(sp: Int, dp: Int, dst: Int): Long {
        return (sp.toLong() shl 48) or (dp.toLong() shl 32) or (dst.toLong() and 0xFFFFFFFFL)
    }

    private fun claveUdp(u: Udp): Long = claveTcp(u.origenPuerto, u.destinoPuerto, u.destinoIp)

    private fun dif(a: Long, b: Long): Long = (a - b) and MASK

    private fun ventana(t: Tcp): Int = if (t.bytesCola < 32768) 65535 else maxOf(0, 65535 - t.bytesCola)

    private fun enviar(t: Tcp, seq: Long, flags: Int, carga: ByteArray?, mss: Int) {
        t.ventAnunciada = ventana(t)
        escribir(
            Paquetes.construirTcp(
                t.destinoIp, t.origenIp, t.destinoPuerto, t.origenPuerto,
                seq, t.rcvNxt, flags, t.ventAnunciada, mss, carga
            )
        )
    }

    // ------------------------------------------------------------------ TCP: paquetes de la app

    private fun rstDesconocido(origen: Int, destino: Int, sp: Int, dp: Int, seq: Long, ack: Long, flags: Int, largo: Int) {
        if ((flags and ACK) != 0) {
            escribir(Paquetes.construirTcp(destino, origen, dp, sp, ack, 0L, RST, 0, 0, null))
        } else {
            var extra = largo.toLong()
            if ((flags and SYN) != 0) extra++
            if ((flags and FIN) != 0) extra++
            escribir(Paquetes.construirTcp(destino, origen, dp, sp, 0L, (seq + extra) and MASK, RST or ACK, 0, 0, null))
        }
    }

    private fun tcp(d: ByteArray, total: Int, ihl: Int, origen: Int, destino: Int) {
        if (total < ihl + 20) return
        val sp = Paquetes.u16(d, ihl)
        val dp = Paquetes.u16(d, ihl + 2)
        val seq = Paquetes.u32(d, ihl + 4)
        val ack = Paquetes.u32(d, ihl + 8)
        val largoTcp = (Paquetes.u8(d, ihl + 12) shr 4) * 4
        val flags = Paquetes.u8(d, ihl + 13)
        val vent = Paquetes.u16(d, ihl + 14)
        val ini = ihl + largoTcp
        if (largoTcp < 20 || ini > total) return
        val carga = d.copyOfRange(ini, total)
        val clave = claveTcp(sp, dp, destino)
        val previa = sesionesTcp[clave]
        var t: Tcp? = previa

        if (previa != null && (flags and SYN) != 0 && (flags and ACK) == 0) {
            if (seq == previa.isnCliente) {
                previa.cerrojo.withLock {
                    if (previa.estado == 1) enviar(previa, previa.isn, SYN or ACK, null, MSS_SALIDA)
                }
                return
            }
            cerrar(previa, false)
            t = null
        }
        if (t == null) {
            if ((flags and SYN) != 0 && (flags and ACK) == 0) {
                nueva(d, ihl, origen, destino, sp, dp, seq, vent, clave)
                return
            }
            if ((flags and RST) != 0) return
            rstDesconocido(origen, destino, sp, dp, seq, ack, flags, carga.size)
            return
        }
        sesion(t, seq, ack, flags, vent, carga)
    }

    private fun nueva(d: ByteArray, ihl: Int, origen: Int, destino: Int, sp: Int, dp: Int, seq: Long, vent: Int, clave: Long) {
        if (filtro.ipBloqueada(destino)) {
            // Escudo: la IP está bloqueada, la conexión se rechaza al instante.
            val uid = uidDe(Paquetes.PROTO_TCP, sp, destino, dp)
            if (grabando()) {
                alEvento(
                    EventoRed(
                        ts = System.currentTimeMillis(), uid = uid, app = nombreApp(uid),
                        ip = ipTexto(destino), puerto = dp, proto = "TCP-bloqueada", sni = "", bytesIn = 0L, bytesOut = 0L
                    )
                )
            }
            rstDesconocido(origen, destino, sp, dp, seq, 0L, SYN, 0)
            return
        }
        if (sesionesTcp.size >= MAX_TCP) {
            rstDesconocido(origen, destino, sp, dp, seq, 0L, SYN, 0)
            return
        }
        val t = Tcp(origen, sp, destino, dp)
        t.isnCliente = seq
        t.rcvNxt = (seq + 1L) and MASK
        t.ventanaCliente = vent
        t.isn = azar.nextInt().toLong() and MASK
        t.sndNxt = t.isn
        t.sndUna = t.isn

        var mss = 536
        var i = ihl + 20
        val fin = ihl + ((Paquetes.u8(d, ihl + 12) shr 4) * 4)
        while (i < fin) {
            val tipo = Paquetes.u8(d, i)
            if (tipo == 0) break
            if (tipo == 1) {
                i++
                continue
            }
            if (i + 1 >= fin) break
            val largo = Paquetes.u8(d, i + 1)
            if (largo < 2) break
            if (tipo == 2 && largo == 4) mss = Paquetes.u16(d, i + 2)
            i += largo
        }
        t.mss = minOf(mss, MSS_SALIDA)
        t.esperaSni = filtro.hayDominios
        sesionesTcp[clave] = t
        try {
            hilos.execute { conectar(t, clave) }
        } catch (e: RejectedExecutionException) {
            sesionesTcp.remove(clave, t)
            rstDesconocido(origen, destino, sp, dp, seq, 0L, SYN, 0)
        }
    }

    private fun conectar(t: Tcp, clave: Long) {
        val socket = Socket()
        try {
            t.uid = uidDe(Paquetes.PROTO_TCP, t.origenPuerto, t.destinoIp, t.destinoPuerto)
            t.app = nombreApp(t.uid)
            // Hay que crear el descriptor del socket antes de protegerlo (si no, protect() no tiene a qué aplicarse).
            socket.bind(null)
            protegerTcp(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(Paquetes.ipObjeto(t.destinoIp), t.destinoPuerto), 8000)
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (x: IOException) {
            }
            registrarFallo(t.destinoIp)
            t.cerrojo.withLock { t.estado = 2 }
            escribir(Paquetes.construirTcp(t.destinoIp, t.origenIp, t.destinoPuerto, t.origenPuerto, 0L, t.rcvNxt, RST or ACK, 0, 0, null))
            sesionesTcp.remove(clave, t)
            emitirTcp(t, "TCP-rechazada", true)
            return
        }
        registrarExito()
        var listo = false
        t.cerrojo.withLock {
            if (t.estado != 2) {
                t.socket = socket
                t.estado = 1
                enviar(t, t.isn, SYN or ACK, null, MSS_SALIDA)
                t.sndNxt = (t.isn + 1L) and MASK
                listo = true
            }
        }
        if (!listo) {
            try {
                socket.close()
            } catch (x: IOException) {
            }
            return
        }
        try {
            hilos.execute { escritor(t) }
        } catch (e: RejectedExecutionException) {
            cerrar(t, true)
            return
        }
        lector(t)
    }

    private fun sesion(t: Tcp, seq: Long, ack: Long, fl: Int, vent: Int, carga: ByteArray) {
        if ((fl and RST) != 0) {
            cerrar(t, false)
            return
        }
        t.cerrojo.withLock {
            if (t.estado != 1) return
            t.actividad = System.currentTimeMillis()
            if ((fl and ACK) != 0) {
                val dup = carga.isEmpty() && (fl and (SYN or FIN)) == 0 && !t.sinConfirmar.isEmpty() &&
                    ack == t.sndUna && vent == t.ventanaCliente
                if (dup) {
                    t.dups++
                    if (t.dups == 3) {
                        retransmitir(t, t.sinConfirmar.first)
                        t.recuperacion = true
                        t.dups = 0
                    }
                } else {
                    avanzarAck(t, ack, vent)
                }
            }
            var responder = false
            // Sonda de ventana cero o keepalive: ACK sin datos con seq distinto del esperado → hay que contestar.
            if (carga.isEmpty() && (fl and (SYN or FIN)) == 0 && seq != t.rcvNxt) responder = true
            if (carga.isNotEmpty()) {
                val desf = dif(t.rcvNxt, seq)
                var nuevo: ByteArray? = null
                if (desf == 0L) {
                    nuevo = carga
                } else if (desf < 0x80000000L) {
                    if (desf < carga.size) nuevo = carga.copyOfRange(desf.toInt(), carga.size)
                } else {
                    val adelante = dif(seq, t.rcvNxt)
                    if (adelante < 131072L && t.bytesFuera + carga.size <= 131072 && !t.fueraOrden.containsKey(seq)) {
                        t.fueraOrden[seq] = carga
                        t.bytesFuera += carga.size
                    }
                }
                if (nuevo != null) {
                    aceptar(t, nuevo)
                    t.pendUpd = 0
                }
                drenar(t)
                responder = true
            }
            if ((fl and FIN) != 0) {
                if (!t.clienteCerro) t.finSeq = (seq + carga.size) and MASK
                responder = true
            }
            procesarFin(t)
            if (responder) enviar(t, t.sndNxt, ACK, null, 0)
            comprobarFin(t)
        }
    }

    private fun aceptar(t: Tcp, nuevo: ByteArray) {
        t.rcvNxt = (t.rcvNxt + nuevo.size) and MASK
        t.bytesCola += nuevo.size
        analizarSni(t, nuevo)
        if (t.esperaSni) {
            if (t.retenido.isEmpty()) t.tRet = System.currentTimeMillis()
            t.retenido.add(nuevo)
            if (t.sniEstado != 0) resolverRetencion(t)
        } else {
            t.cola.add(nuevo)
        }
    }

    /**
     * Escudo: con dominios bloqueados, los primeros bytes de cada conexión se retienen hasta saber el nombre del
     * servidor (SNI). Si está bloqueado se corta con RST sin haber enviado nada al destino; si no, se libera todo.
     */
    private fun resolverRetencion(t: Tcp) {
        if (!t.esperaSni) return
        t.esperaSni = false
        if (t.sni.isNotEmpty() && filtro.dominioBloqueado(t.sni)) {
            t.retenido.clear()
            t.bloqueada = true
            alBloqueo(t.sni)
            try {
                hilos.execute { cerrar(t, true) }
            } catch (e: RejectedExecutionException) {
                cerrar(t, true)
            }
            return
        }
        for (b in t.retenido) t.cola.add(b)
        t.retenido.clear()
    }

    private fun drenar(t: Tcp) {
        var progreso = true
        while (progreso && t.fueraOrden.isNotEmpty()) {
            progreso = false
            for (k in ArrayList(t.fueraOrden.keys)) {
                val d = dif(t.rcvNxt, k)
                if (d < 0x80000000L) {
                    val datos = t.fueraOrden.remove(k) ?: continue
                    t.bytesFuera -= datos.size
                    if (d < datos.size) aceptar(t, datos.copyOfRange(d.toInt(), datos.size))
                    progreso = true
                }
            }
        }
    }

    private fun procesarFin(t: Tcp) {
        if (t.finSeq >= 0L && !t.clienteCerro && t.finSeq == t.rcvNxt) {
            t.rcvNxt = (t.rcvNxt + 1L) and MASK
            t.clienteCerro = true
            if (t.esperaSni) resolverRetencion(t)
            t.cola.add(ByteArray(0))
            enviar(t, t.sndNxt, ACK, null, 0)
        }
    }

    private fun analizarSni(t: Tcp, nuevo: ByteArray) {
        if (t.sniEstado != 0) return
        t.primeros.write(nuevo, 0, nuevo.size)
        val bytes = t.primeros.toByteArray()
        val r = TlsSni.analizar(bytes, bytes.size)
        when (r.estado) {
            TlsSni.ENCONTRADO -> {
                t.sni = r.nombre ?: ""
                t.sniEstado = 1
            }
            TlsSni.NO_TLS, TlsSni.SIN_SNI -> t.sniEstado = 1
            else -> if (bytes.size > 16389) t.sniEstado = 1
        }
        if (t.sniEstado == 1) t.primeros.reset()
    }

    private fun avanzarAck(t: Tcp, ack: Long, vent: Int) {
        t.ventanaCliente = vent
        val avance = dif(ack, t.sndUna)
        val maximo = dif(t.sndNxt, t.sndUna)
        if (avance > 0L && avance <= maximo) {
            t.sndUna = ack
            t.reintentos = 0
            t.rto = 600L
            t.dups = 0
            while (!t.sinConfirmar.isEmpty()) {
                val g = t.sinConfirmar.first
                val consumo = g.datos.size + (if (g.fin) 1 else 0)
                val d = dif(t.sndUna, g.seq)
                if (d >= consumo && d < 0x80000000L) t.sinConfirmar.removeFirst() else break
            }
            if (t.sinConfirmar.isEmpty()) {
                t.recuperacion = false
            } else if (t.recuperacion) {
                retransmitir(t, t.sinConfirmar.first)
            }
        }
        t.hayHueco.signalAll()
    }

    private fun retransmitir(t: Tcp, g: Segmento) {
        g.enviado = System.currentTimeMillis()
        val flags = if (g.fin) (FIN or ACK) else (PSH or ACK)
        enviar(t, g.seq, flags, g.datos, 0)
    }

    private fun comprobarFin(t: Tcp) {
        if (t.clienteCerro && t.finEnviado && t.sinConfirmar.isEmpty()) {
            try {
                hilos.execute { cerrar(t, false) }
            } catch (e: RejectedExecutionException) {
                cerrar(t, false)
            }
        }
    }

    // ------------------------------------------------------------------ TCP: hilos de cada conexión

    private fun escritor(t: Tcp) {
        val salida = (try {
            t.socket?.getOutputStream()
        } catch (e: IOException) {
            null
        }) ?: return
        try {
            while (true) {
                val b = t.cola.take()
                if (b.isEmpty()) {
                    try {
                        t.socket?.shutdownOutput()
                    } catch (e: IOException) {
                    }
                    return
                }
                try {
                    salida.write(b)
                    salida.flush()
                } catch (e: IOException) {
                    cerrar(t, true)
                    return
                }
                t.cerrojo.withLock {
                    t.bytesCola -= b.size
                    t.bytesOut += b.size
                    if (t.ventAnunciada < 16384 && ventana(t) >= 32768 && t.estado == 1) {
                        enviar(t, t.sndNxt, ACK, null, 0)
                        t.pendUpd = 3
                        t.tsUpd = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: InterruptedException) {
            return
        }
    }

    private fun lector(t: Tcp) {
        val entrada = (try {
            t.socket?.getInputStream()
        } catch (e: IOException) {
            null
        }) ?: return
        val buf = ByteArray(MSS_SALIDA)
        try {
            while (true) {
                val n = try {
                    entrada.read(buf, 0, minOf(t.mss, MSS_SALIDA))
                } catch (e: IOException) {
                    cerrar(t, true)
                    return
                }
                if (n < 0) {
                    t.cerrojo.withLock {
                        if (t.estado != 1) return
                        t.servidorCerro = true
                        val seq = t.sndNxt
                        t.sinConfirmar.addLast(Segmento(seq, ByteArray(0), true))
                        enviar(t, seq, FIN or ACK, null, 0)
                        t.sndNxt = (t.sndNxt + 1L) and MASK
                        t.finEnviado = true
                        comprobarFin(t)
                    }
                    return
                }
                val inicio = System.currentTimeMillis()
                t.cerrojo.withLock {
                    while (t.estado == 1 && dif(t.sndNxt, t.sndUna) + n > t.ventanaCliente) {
                        t.hayHueco.await(500L, TimeUnit.MILLISECONDS)
                        if (System.currentTimeMillis() - inicio > 120_000L) break
                    }
                    if (t.estado != 1) return
                    val seq = t.sndNxt
                    val datos = buf.copyOf(n)
                    t.sinConfirmar.addLast(Segmento(seq, datos, false))
                    t.sndNxt = (t.sndNxt + n) and MASK
                    t.bytesIn += n
                    enviar(t, seq, PSH or ACK, datos, 0)
                }
            }
        } catch (e: InterruptedException) {
            return
        }
    }

    private fun cerrar(t: Tcp, rst: Boolean) {
        t.cerrojo.withLock {
            if (t.estado == 2) return
            t.estado = 2
            t.hayHueco.signalAll()
            if (rst) {
                escribir(
                    Paquetes.construirTcp(
                        t.destinoIp, t.origenIp, t.destinoPuerto, t.origenPuerto,
                        t.sndNxt, t.rcvNxt, RST or ACK, 0, 0, null
                    )
                )
            }
        }
        try {
            t.socket?.close()
        } catch (e: IOException) {
        }
        sesionesTcp.remove(claveTcp(t.origenPuerto, t.destinoPuerto, t.destinoIp), t)
        t.cola.add(ByteArray(0))
        emitirTcp(t, if (t.bloqueada) "TCP-bloqueada" else "TCP", true)
    }

    // ------------------------------------------------------------------ eventos de red

    private fun emitirTcp(t: Tcp, proto: String, final: Boolean) {
        var dIn = 0L
        var dOut = 0L
        t.cerrojo.withLock {
            dIn = t.bytesIn - t.emitidoIn
            dOut = t.bytesOut - t.emitidoOut
            t.emitidoIn = t.bytesIn
            t.emitidoOut = t.bytesOut
            t.ultimaEmision = System.currentTimeMillis()
        }
        if (!grabando()) return
        if (dIn == 0L && dOut == 0L && (t.emitido || !final)) return
        t.emitido = true
        alEvento(
            EventoRed(
                ts = System.currentTimeMillis(), uid = t.uid, app = t.app,
                ip = ipTexto(t.destinoIp), puerto = t.destinoPuerto, proto = proto, sni = t.sni,
                bytesIn = dIn, bytesOut = dOut
            )
        )
    }

    private fun ipTexto(ip: Int): String {
        return ((ip ushr 24) and 0xFF).toString() + "." + ((ip ushr 16) and 0xFF) + "." +
            ((ip ushr 8) and 0xFF) + "." + (ip and 0xFF)
    }

    // ------------------------------------------------------------------ mantenimiento periódico

    private fun mantenimiento() {
        try {
            val ahora = System.currentTimeMillis()
            for (t in sesionesTcp.values) mantener(t, ahora)
            for (u in sesionesUdp.values) {
                if (ahora - u.ultima > 90_000L) {
                    cerrarUdp(claveUdp(u), u)
                } else if (ahora - u.ultimaEmision > 300_000L) {
                    emitirUdp(u, false)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun mantener(t: Tcp, ahora: Long) {
        var cerrarConRst = false
        var periodico = false
        t.cerrojo.withLock {
            if (t.estado != 1) return
            if (ahora - t.actividad > 1_800_000L) {
                cerrarConRst = true
                return@withLock
            }
            if (t.esperaSni && t.retenido.isNotEmpty() && ahora - t.tRet > 3000L) {
                t.sniEstado = 1
                resolverRetencion(t)
            }
            if (t.pendUpd > 0 && ahora - t.tsUpd > 350L) {
                t.pendUpd--
                t.tsUpd = ahora
                enviar(t, t.sndNxt, ACK, null, 0)
            }
            if (!t.sinConfirmar.isEmpty()) {
                val g = t.sinConfirmar.first
                if (ahora - g.enviado > t.rto) {
                    t.reintentos++
                    if (t.reintentos > 8) {
                        cerrarConRst = true
                        return@withLock
                    }
                    t.rto = minOf(t.rto * 2L, 8000L)
                    retransmitir(t, g)
                }
            }
            if (ahora - t.ultimaEmision > 300_000L) periodico = true
        }
        if (cerrarConRst) {
            cerrar(t, true)
        } else if (periodico) {
            emitirTcp(t, "TCP", false)
        }
    }

    // ------------------------------------------------------------------ salud: modo a prueba de fallos

    private fun registrarExito() {
        fallosSeguidos.set(0)
        ultimoExito.set(System.currentTimeMillis())
        synchronized(destinosFallidos) { destinosFallidos.clear() }
    }

    private fun registrarFallo(destino: Int) {
        val n = fallosSeguidos.incrementAndGet()
        var distintos = 0
        synchronized(destinosFallidos) {
            destinosFallidos.add(destino)
            distintos = destinosFallidos.size
        }
        val sinExito = System.currentTimeMillis() - ultimoExito.get()
        if (n >= 25 && distintos >= 6 && sinExito > 60_000L && fallaAvisada.compareAndSet(false, true)) {
            alFallar("fallaron " + n + " conexiones seguidas hacia " + distintos + " destinos distintos")
        }
    }

    // ------------------------------------------------------------------ UDP

    private fun udp(d: ByteArray, total: Int, ihl: Int, origen: Int, destino: Int) {
        if (total < ihl + 8) return
        val sp = Paquetes.u16(d, ihl)
        val dp = Paquetes.u16(d, ihl + 2)
        val largo = minOf(Paquetes.u16(d, ihl + 4), total - ihl)
        if (largo < 8) return
        val carga = d.copyOfRange(ihl + 8, ihl + largo)
        if (((destino ushr 24) and 0xFF) >= 224) return // multicast, difusión y reservadas
        if (filtro.ipBloqueada(destino)) {
            escribir(Paquetes.construirIcmpPuertoInalcanzable(destino, origen, d, total, ihl))
            return
        }
        if (dp == 53 && filtro.hayDominios) {
            val consulta = DnsParser.parseConsulta(carga)
            if (consulta != null && filtro.dominioBloqueado(consulta.nombre)) {
                val respuesta = DnsParser.construirBloqueo(carga, respuestaBloqueo)
                escribir(Paquetes.construirUdp(destino, origen, 53, sp, respuesta))
                alBloqueo(consulta.nombre)
                alDns(-1, "desconocida", carga, respuesta)
                return
            }
        }
        if (dp == 443 && bloquearQuic) {
            escribir(Paquetes.construirIcmpPuertoInalcanzable(destino, origen, d, total, ihl))
            return
        }
        val clave = claveTcp(sp, dp, destino)
        var u = sesionesUdp[clave]
        if (u == null) {
            if (sesionesUdp.size >= MAX_UDP) return
            val socket = DatagramSocket()
            try {
                protegerUdp(socket)
            } catch (e: Exception) {
                socket.close()
                return
            }
            val nuevo = Udp(origen, sp, destino, dp, socket)
            sesionesUdp[clave] = nuevo
            try {
                hilos.execute { leerUdp(clave, nuevo) }
            } catch (e: RejectedExecutionException) {
                sesionesUdp.remove(clave, nuevo)
                socket.close()
                return
            }
            u = nuevo
        }
        u.ultima = System.currentTimeMillis()
        u.bytesOut += carga.size
        if (dp == 53) u.ultimaConsulta = carga
        try {
            u.socket.send(DatagramPacket(carga, carga.size, Paquetes.ipObjeto(destino), dp))
        } catch (e: IOException) {
            cerrarUdp(clave, u)
        }
    }

    private fun leerUdp(clave: Long, u: Udp) {
        try {
            u.uid = uidDe(Paquetes.PROTO_UDP, u.origenPuerto, u.destinoIp, u.destinoPuerto)
            u.app = nombreApp(u.uid)
            val buf = ByteArray(65535)
            u.socket.soTimeout = if (u.destinoPuerto == 53) 5000 else 60000
            while (activo && !u.cerrado) {
                val paquete = DatagramPacket(buf, buf.size)
                try {
                    u.socket.receive(paquete)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val datos = buf.copyOf(paquete.length)
                u.bytesIn += datos.size
                u.ultima = System.currentTimeMillis()
                escribir(Paquetes.construirUdp(u.destinoIp, u.origenIp, u.destinoPuerto, u.origenPuerto, datos))
                if (u.destinoPuerto == 53) {
                    val consulta = u.ultimaConsulta
                    if (consulta != null) alDns(u.uid, u.app, consulta, datos)
                }
            }
        } catch (e: Exception) {
        } finally {
            cerrarUdp(clave, u)
        }
    }

    private fun cerrarUdp(clave: Long, u: Udp) {
        if (u.cerrado) return
        u.cerrado = true
        sesionesUdp.remove(clave, u)
        try {
            u.socket.close()
        } catch (e: Exception) {
        }
        emitirUdp(u, true)
    }

    private fun emitirUdp(u: Udp, final: Boolean) {
        var dIn = 0L
        var dOut = 0L
        synchronized(u) {
            dIn = u.bytesIn - u.emitidoIn
            dOut = u.bytesOut - u.emitidoOut
            u.emitidoIn = u.bytesIn
            u.emitidoOut = u.bytesOut
            u.ultimaEmision = System.currentTimeMillis()
        }
        if (!grabando()) return
        if (dIn == 0L && dOut == 0L && (u.emitido || !final)) return
        u.emitido = true
        alEvento(
            EventoRed(
                ts = System.currentTimeMillis(), uid = u.uid, app = u.app,
                ip = ipTexto(u.destinoIp), puerto = u.destinoPuerto, proto = "UDP", sni = "",
                bytesIn = dIn, bytesOut = dOut
            )
        )
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL
        private const val FIN = 0x01
        private const val SYN = 0x02
        private const val RST = 0x04
        private const val PSH = 0x08
        private const val ACK = 0x10
        private const val MSS_SALIDA = 1400
        private const val MAX_TCP = 250
        private const val MAX_UDP = 100
    }
}
