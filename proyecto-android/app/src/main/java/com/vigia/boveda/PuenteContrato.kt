package com.vigia.boveda

/**
 * Contrato del puente Teclado Soberano ⇄ La Bóveda (versión 1).
 *
 * Las constantes de protocolo deben ser IDÉNTICAS en las dos apps (el archivo PuenteContrato.kt del teclado
 * solo cambia el paquete). Cualquier cambio incompatible exige subir [VERSION].
 */
object PuenteContrato {
    const val VERSION = 1

    const val PAQUETE_BOVEDA = "com.vigia.boveda"
    const val ACTIVIDAD_BOVEDA = "com.vigia.boveda.PuenteTecladoActivity"
    const val PAQUETE_TECLADO = "com.vigia.tecladosoberano"

    /** Permiso de firma: solo las apps firmadas con la misma llave que La Bóveda pueden abrir el puente. */
    const val PERMISO = "com.vigia.boveda.permission.PUENTE_TECLADO"

    // ---- Petición (Teclado -> Bóveda), como extras del Intent explícito
    const val EXTRA_VERSION = "vigia.puente.version"
    /** 16 bytes aleatorios: identifica la petición y sirve de «sal» de la clave derivada. */
    const val EXTRA_ID = "vigia.puente.id"
    /** Clave pública EFÍMERA del teclado (P-256, formato X.509). */
    const val EXTRA_CLAVE_PUBLICA = "vigia.puente.clave_publica"
    /** Paquete de la app donde se está escribiendo (EditorInfo.packageName). */
    const val EXTRA_DESTINO = "vigia.puente.destino"
    /** ResultReceiver por el que La Bóveda contesta. */
    const val EXTRA_RECEPTOR = "vigia.puente.receptor"

    // ---- Respuesta (Bóveda -> Teclado), dentro del Bundle del ResultReceiver
    const val RES_PUBLICA_BOVEDA = "vigia.puente.res.publica"
    const val RES_IV = "vigia.puente.res.iv"
    const val RES_CIFRADO = "vigia.puente.res.cifrado"
    /** Texto corto con el motivo de un cierre anómalo (diagnóstico; nunca contiene datos secretos). */
    const val RES_MOTIVO = "vigia.puente.res.motivo"

    // ---- Códigos de resultado
    const val COD_OK = 1
    const val COD_CANCELADO = 2
    const val COD_NO_INICIALIZADA = 3
    const val COD_ERROR = 4

    /** Aviso de vida: La Bóveda mostró su pantalla. NO es una respuesta final. */
    const val COD_ABIERTA = 10

    // ---- Solo La Bóveda: marca interna para que DesbloqueoActivity devuelva el control al puente
    const val ES_PUENTE = "es_puente"
}
