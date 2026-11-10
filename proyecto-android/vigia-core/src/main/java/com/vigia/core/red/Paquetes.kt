package com.vigia.core.red

import java.net.InetAddress

/** Utilidades para leer y construir paquetes IPv4/UDP/TCP (solo cabeceras; no se interpreta contenido cifrado). */
object Paquetes {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    fun u32(b: ByteArray, i: Int): Long {
        return (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
            (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    }

    fun ip4(b: ByteArray, i: Int): Int {
        return (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    }

    fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }

    fun put32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 24).toByte()
        b[i + 1] = (v ushr 16).toByte()
        b[i + 2] = (v ushr 8).toByte()
        b[i + 3] = v.toByte()
    }

    fun ipObjeto(ip: Int): InetAddress {
        val bytes = byteArrayOf(
            (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte()
        )
        return InetAddress.getByAddress(bytes)
    }

    /** Suma (sin complementar) de las palabras de 16 bits de la pseudo-cabecera IPv4. */
    fun sumaPseudo(origen: Int, destino: Int, proto: Int, largo: Int): Long {
        return ((origen ushr 16) and 0xFFFF).toLong() + (origen and 0xFFFF).toLong() +
            ((destino ushr 16) and 0xFFFF).toLong() + (destino and 0xFFFF).toLong() +
            proto.toLong() + largo.toLong()
    }

    /** Checksum de Internet (RFC 1071) sobre [largo] bytes desde [inicio], con una suma inicial opcional. */
    fun checksum(datos: ByteArray, inicio: Int, largo: Int, semilla: Long): Int {
        var suma = semilla
        var i = inicio
        val fin = inicio + largo
        while (i + 1 < fin) {
            suma += ((u8(datos, i) shl 8) or u8(datos, i + 1)).toLong()
            i += 2
        }
        if (i < fin) {
            suma += (u8(datos, i) shl 8).toLong()
        }
        while ((suma shr 16) != 0L) {
            suma = (suma and 0xFFFFL) + (suma shr 16)
        }
        return suma.inv().toInt() and 0xFFFF
    }

    private fun cabeceraIp(p: ByteArray, total: Int, proto: Int, origen: Int, destino: Int) {
        p[0] = 0x45.toByte()
        p[1] = 0
        put16(p, 2, total)
        put16(p, 4, 0)
        put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = proto.toByte()
        put16(p, 10, 0)
        put32(p, 12, origen)
        put32(p, 16, destino)
        put16(p, 10, checksum(p, 0, 20, 0L))
    }

    fun construirUdp(
        origenIp: Int, destinoIp: Int,
        origenPuerto: Int, destinoPuerto: Int,
        carga: ByteArray
    ): ByteArray {
        val udpLen = 8 + carga.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        cabeceraIp(p, total, PROTO_UDP, origenIp, destinoIp)
        put16(p, 20, origenPuerto)
        put16(p, 22, destinoPuerto)
        put16(p, 24, udpLen)
        put16(p, 26, 0)
        System.arraycopy(carga, 0, p, 28, carga.size)
        var c = checksum(p, 20, udpLen, sumaPseudo(origenIp, destinoIp, PROTO_UDP, udpLen))
        if (c == 0) c = 0xFFFF
        put16(p, 26, c)
        return p
    }

    /** RST+ACK como respuesta a un SYN (rechaza una conexión TCP al instante). */
    fun construirRstTcp(
        origenIp: Int, destinoIp: Int,
        origenPuerto: Int, destinoPuerto: Int,
        seqRecibido: Long
    ): ByteArray {
        val total = 40
        val p = ByteArray(total)
        cabeceraIp(p, total, PROTO_TCP, origenIp, destinoIp)
        put16(p, 20, origenPuerto)
        put16(p, 22, destinoPuerto)
        put32(p, 24, 0)
        put32(p, 28, ((seqRecibido + 1L) and 0xFFFFFFFFL).toInt())
        p[32] = 0x50.toByte()
        p[33] = 0x14.toByte()
        put16(p, 34, 0)
        put16(p, 36, 0)
        put16(p, 38, 0)
        put16(p, 36, checksum(p, 20, 20, sumaPseudo(origenIp, destinoIp, PROTO_TCP, 20)))
        return p
    }

    /** Segmento TCP genérico (con opción MSS opcional en el SYN-ACK). */
    fun construirTcp(
        origenIp: Int, destinoIp: Int,
        origenPuerto: Int, destinoPuerto: Int,
        seq: Long, ack: Long, flags: Int, ventana: Int, mss: Int, carga: ByteArray?
    ): ByteArray {
        val opciones = if (mss > 0) 4 else 0
        val cabecera = 20 + opciones
        val largoCarga = carga?.size ?: 0
        val total = 20 + cabecera + largoCarga
        val p = ByteArray(total)
        cabeceraIp(p, total, PROTO_TCP, origenIp, destinoIp)
        put16(p, 20, origenPuerto)
        put16(p, 22, destinoPuerto)
        put32(p, 24, (seq and 0xFFFFFFFFL).toInt())
        put32(p, 28, (ack and 0xFFFFFFFFL).toInt())
        p[32] = ((cabecera / 4) shl 4).toByte()
        p[33] = flags.toByte()
        put16(p, 34, ventana)
        put16(p, 36, 0)
        put16(p, 38, 0)
        if (mss > 0) {
            p[40] = 2
            p[41] = 4
            put16(p, 42, mss)
        }
        if (carga != null && largoCarga > 0) System.arraycopy(carga, 0, p, 20 + cabecera, largoCarga)
        put16(p, 36, checksum(p, 20, cabecera + largoCarga, sumaPseudo(origenIp, destinoIp, PROTO_TCP, cabecera + largoCarga)))
        return p
    }

    /** ICMP «puerto inalcanzable»: hace que la app abandone al instante (por ejemplo QUIC) en vez de esperar. */
    fun construirIcmpPuertoInalcanzable(
        origenIp: Int, destinoIp: Int, original: ByteArray, largoOriginal: Int, ihl: Int
    ): ByteArray {
        val incluir = minOf(largoOriginal, ihl + 8)
        val icmp = 8 + incluir
        val total = 20 + icmp
        val p = ByteArray(total)
        cabeceraIp(p, total, 1, origenIp, destinoIp)
        p[20] = 3
        p[21] = 3
        System.arraycopy(original, 0, p, 28, incluir)
        put16(p, 22, checksum(p, 20, icmp, 0L))
        return p
    }
}
