package com.vigia.core.inventario

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

class AppInfo(
    val paquete: String,
    val etiqueta: String,
    val sistema: Boolean,
    val actualizadaDeSistema: Boolean,
    val habilitada: Boolean,
    val version: String,
    val instalador: String,
    val instalada: Long,
    val actualizada: Long,
    val huella: String,
    val permisos: List<String>,
    val especiales: List<String>,
    val conLanzador: Boolean,
    val accesibilidad: Boolean,
    val lectorNotificaciones: Boolean,
    val administrador: Boolean,
    val teclado: Boolean,
    val puntos: Int,
    val motivos: List<String>
) {
    /** normal, atencion o revisar: una guía para ordenar la lista, nunca un veredicto. */
    val nivel: String
        get() = if (puntos >= 6) "revisar" else if (puntos >= 3) "atencion" else "normal"
}

/**
 * Lista en vivo de las apps del teléfono con su score de riesgo (de Vigía 1.0, fase 7.3).
 * Necesita QUERY_ALL_PACKAGES en el manifiesto de la app que la use; sin él, Android solo devuelve las apps visibles.
 */
object AppsDispositivo {

    @Suppress("DEPRECATION")
    fun cargar(contexto: Context): List<AppInfo> {
        val pm = contexto.packageManager
        val accesibilidad = paquetesDeAjuste(contexto, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listeners = paquetesDeAjuste(contexto, "enabled_notification_listeners")
        val teclados = paquetesDeAjuste(contexto, Settings.Secure.ENABLED_INPUT_METHODS)
        val admins = administradores(contexto)
        val ahora = System.currentTimeMillis()

        val lista = ArrayList<AppInfo>()
        for (info in pm.getInstalledApplications(0)) {
            val paquete = info.packageName
            val sistema = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val actualizada = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            var version = ""
            var instalada = 0L
            var ultima = 0L
            var huella = ""
            val permisos = ArrayList<String>()
            val especiales = ArrayList<String>()
            try {
                val firma = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
                val pi = pm.getPackageInfo(paquete, PackageManager.GET_PERMISSIONS or firma)
                version = pi.versionName ?: ""
                instalada = pi.firstInstallTime
                ultima = pi.lastUpdateTime
                huella = huellaFirma(pi)
                val pedidos = pi.requestedPermissions
                val banderas = pi.requestedPermissionsFlags
                if (pedidos != null) {
                    for (i in pedidos.indices) {
                        val corto = pedidos[i].substringAfterLast('.')
                        if (CatalogoPermisos.SENSIBLES.contains(corto)) {
                            val concedido = banderas != null && i < banderas.size &&
                                (banderas[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                            if (concedido) permisos.add(corto)
                        } else if (CatalogoPermisos.ESPECIALES.contains(corto)) {
                            especiales.add(corto)
                        }
                    }
                }
            } catch (e: Exception) {
            }
            val instalador = instaladorDe(pm, paquete)
            val conLanzador = pm.getLaunchIntentForPackage(paquete) != null
            val acc = accesibilidad.contains(paquete)
            val lis = listeners.contains(paquete)
            val adm = admins.contains(paquete)
            val tec = teclados.contains(paquete)

            val score = ScoreRiesgo.calcular(
                sistema = sistema, instalador = instalador, accesibilidad = acc, lectorNotificaciones = lis,
                administrador = adm, teclado = tec, permisos = permisos, especiales = especiales,
                conLanzador = conLanzador, instalada = instalada, ahora = ahora
            )

            lista.add(
                AppInfo(
                    paquete, info.loadLabel(pm).toString(), sistema, actualizada, info.enabled, version, instalador,
                    instalada, ultima, huella, permisos, especiales, conLanzador, acc, lis, adm, tec,
                    score.puntos, score.motivos
                )
            )
        }
        lista.sortWith(compareByDescending<AppInfo> { it.puntos }.thenBy { it.etiqueta.lowercase() })
        return lista
    }

    private fun paquetesDeAjuste(contexto: Context, clave: String): Set<String> {
        val res = HashSet<String>()
        try {
            val texto = Settings.Secure.getString(contexto.contentResolver, clave)
            if (texto != null) {
                for (parte in texto.split(':')) {
                    if (parte.isNotBlank()) res.add(parte.substringBefore('/'))
                }
            }
        } catch (e: Exception) {
        }
        return res
    }

    private fun administradores(contexto: Context): Set<String> {
        val res = HashSet<String>()
        try {
            val dpm = contexto.getSystemService(DevicePolicyManager::class.java)
            val admins = dpm.activeAdmins
            if (admins != null) {
                for (a in admins) res.add(a.packageName)
            }
        } catch (e: Exception) {
        }
        return res
    }

    @Suppress("DEPRECATION")
    private fun instaladorDe(pm: PackageManager, paquete: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(paquete).installingPackageName ?: ""
            } else {
                pm.getInstallerPackageName(paquete) ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("DEPRECATION")
    private fun huellaFirma(pi: PackageInfo): String {
        return try {
            val firmas = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
            if (firmas != null && firmas.isNotEmpty()) {
                val d = MessageDigest.getInstance("SHA-256").digest(firmas[0].toByteArray())
                val sb = StringBuilder()
                for (i in 0 until 8) sb.append(String.format(Locale.US, "%02x", d[i].toInt() and 0xFF))
                sb.toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
