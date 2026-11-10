package com.vigia.core.inventario

/** Listas de referencia compartidas por el inventario, el score de riesgo y el gestor de permisos. */
object CatalogoPermisos {
    /** Permisos de ejecución que se anotan SOLO si están concedidos (nombre corto, sin «android.permission.»). */
    val SENSIBLES = setOf(
        "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG", "RECORD_AUDIO", "CAMERA",
        "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_CONTACTS", "READ_PHONE_STATE",
        "READ_CALENDAR", "ANSWER_PHONE_CALLS", "PROCESS_OUTGOING_CALLS"
    )

    /** Permisos especiales: se anotan si la app los DECLARA (Android no dice si están activos). */
    val ESPECIALES = setOf(
        "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE",
        "PACKAGE_USAGE_STATS", "WRITE_SETTINGS"
    )

    /** Instaladores considerados «tienda conocida». */
    val TIENDAS = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.amazon.venezia",
        "com.huawei.appmarket", "com.xiaomi.mipicks", "com.xiaomi.market",
        "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store"
    )
}
