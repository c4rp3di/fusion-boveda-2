package com.vigia.core.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vigia.core.R

/**
 * Notificación persistente de sesión (la de «Vigía activo»): fija, silenciosa, con botón de parada inmediata.
 * Toda app de la suite que mantenga un servicio en primer plano la usa para verse igual y comportarse igual.
 */
object NotificacionSesion {
    /** Acción que reciben los servicios cuando el usuario toca «Detener» en la notificación. */
    const val ACCION_DETENER = "com.vigia.core.DETENER"

    /**
     * @param actividad pantalla que se abre al tocar la notificación (null = ninguna).
     * @param textoDetener texto del botón de parada; null = sin botón.
     */
    class Datos(
        val canalId: String,
        val canalNombre: String,
        val id: Int,
        val titulo: String,
        val texto: String,
        val iconoRes: Int = R.drawable.vc_ic_notificacion,
        val actividad: Class<*>? = null,
        val textoDetener: String? = "Detener"
    )

    fun crearCanal(contexto: Context, datos: Datos) {
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel(datos.canalId, datos.canalNombre, NotificationManager.IMPORTANCE_LOW)
            canal.setShowBadge(false)
            contexto.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    fun construir(contexto: Context, datos: Datos, servicio: Class<*>?): Notification {
        crearCanal(contexto, datos)
        val b = NotificationCompat.Builder(contexto, datos.canalId)
            .setSmallIcon(datos.iconoRes)
            .setContentTitle(datos.titulo)
            .setContentText(datos.texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val actividad = datos.actividad
        if (actividad != null) {
            val abrir = PendingIntent.getActivity(
                contexto, 0, Intent(contexto, actividad),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.setContentIntent(abrir)
        }
        if (datos.textoDetener != null && servicio != null) {
            val detener = PendingIntent.getService(
                contexto, 1, Intent(contexto, servicio).setAction(ACCION_DETENER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, datos.textoDetener, detener)
        }
        return b.build()
    }

    /** Convierte el servicio en primer plano. Android 14 exige declarar un tipo: aquí «specialUse» (ver manifiesto). */
    fun publicarPrimerPlano(servicio: Service, datos: Datos, claseServicio: Class<*>) {
        val n = construir(servicio, datos, claseServicio)
        if (Build.VERSION.SDK_INT >= 34) {
            servicio.startForeground(datos.id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            servicio.startForeground(datos.id, n)
        }
    }

    /** Cambia el texto de la notificación de un servicio que ya está en primer plano. */
    fun actualizar(contexto: Context, datos: Datos, claseServicio: Class<*>) {
        val n = construir(contexto, datos, claseServicio)
        contexto.getSystemService(NotificationManager::class.java).notify(datos.id, n)
    }
}
