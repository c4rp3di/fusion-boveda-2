package com.vigia.boveda

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.InlinePresentation
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.util.Log
import java.util.regex.Pattern
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.content.IntentCompat
import com.vigia.boveda.R
import com.vigia.core.red.UtilRed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Lo que el servicio encontró en la pantalla de otra app o página: los campos y de dónde viene. */
class CamposAutofill(
    val usuarioId: AutofillId?,
    val claveId: AutofillId?,
    val dominioWeb: String?,
    val paquete: String,
    val usuarioValor: String,
    val claveValor: String,
    /** Resumen de lo que se vio en la pantalla (solo cantidades, nunca valores), para el registro. */
    val resumen: String = ""
) {
    val hayCampos: Boolean get() = usuarioId != null || claveId != null
}


/** Paso de los campos al diálogo de desbloqueo y vuelta. */
object ExtrasAutofill {
    const val ES_AUTOFILL = "es_autofill"
    const val ES_GUARDAR = "es_guardar"
    private const val USUARIO = "usuario_id"
    private const val CLAVE = "clave_id"
    private const val DOMINIO = "dominio"
    private const val PAQUETE = "paquete"

    fun poner(i: Intent, c: CamposAutofill) {
        i.putExtra(ES_AUTOFILL, true)
        i.putExtra(USUARIO, c.usuarioId)
        i.putExtra(CLAVE, c.claveId)
        i.putExtra(DOMINIO, c.dominioWeb)
        i.putExtra(PAQUETE, c.paquete)
    }

    fun leer(i: Intent): CamposAutofill {
        return CamposAutofill(
            IntentCompat.getParcelableExtra(i, USUARIO, AutofillId::class.java),
            IntentCompat.getParcelableExtra(i, CLAVE, AutofillId::class.java),
            i.getStringExtra(DOMINIO),
            i.getStringExtra(PAQUETE) ?: "",
            "", ""
        )
    }
}

/**
 * Recorre la estructura de la pantalla y localiza el campo de usuario y el de contraseña. Mira tres cosas: las pistas de
 * autocompletado y el tipo de entrada de Android (apps nativas), y los atributos HTML (navegadores, que no usan el tipo de
 * entrada de Android sino «type», «autocomplete», «name»…).
 */
object ParserEstructura {

    private class Nodo(
        val id: AutofillId,
        val esClave: Boolean,
        val esUsuario: Boolean,
        val esTexto: Boolean,
        val enfocado: Boolean,
        val valor: String
    )

    private class Cuenta {
        var nodos = 0
        var campos = 0
        var ocultos = 0
    }

    fun analizar(estructura: AssistStructure): CamposAutofill {
        val nodos = ArrayList<Nodo>()
        val dominios = ArrayList<String>()
        val cuenta = Cuenta()
        for (i in 0 until estructura.windowNodeCount) {
            recorrer(estructura.getWindowNodeAt(i).rootViewNode, nodos, dominios, cuenta)
        }
        val paquete = estructura.activityComponent?.packageName ?: ""
        val clave = nodos.firstOrNull { it.esClave && it.enfocado } ?: nodos.firstOrNull { it.esClave }
        var usuario: Nodo? = null
        if (clave != null) {
            // El usuario es el último campo candidato anterior a la contraseña.
            for (n in nodos) {
                if (n === clave) break
                if (n.esUsuario && !n.esClave) usuario = n
            }
            // Si ninguno se reconoce como usuario, vale el último campo de texto anterior a la contraseña.
            if (usuario == null) {
                for (n in nodos) {
                    if (n === clave) break
                    if (n.esTexto && !n.esClave) usuario = n
                }
            }
        }
        if (usuario == null) usuario = nodos.firstOrNull { it.esUsuario && !it.esClave }
        val dominio = dominios.firstOrNull { it.isNotEmpty() }
        val resumen = "nodos=" + cuenta.nodos + " campos=" + cuenta.campos + " ocultos=" + cuenta.ocultos +
            " clave=" + (clave != null) + " usuario=" + (usuario != null)
        return CamposAutofill(
            usuario?.id, clave?.id, dominio, paquete,
            usuario?.valor ?: "", clave?.valor ?: "", resumen
        )
    }

    private fun recorrer(n: AssistStructure.ViewNode, salida: MutableList<Nodo>, dominios: MutableList<String>, cuenta: Cuenta) {
        cuenta.nodos++
        val web = n.webDomain
        if (!web.isNullOrEmpty()) dominios.add(web.lowercase())
        val id = n.autofillId
        if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
            if (n.visibility != View.VISIBLE) {
                cuenta.ocultos++
            } else {
                cuenta.campos++
                clasificar(n, id, salida)
            }
        }
        for (i in 0 until n.childCount) recorrer(n.getChildAt(i), salida, dominios, cuenta)
    }

    private fun clasificar(n: AssistStructure.ViewNode, id: AutofillId, salida: MutableList<Nodo>) {
        val pistas = n.autofillHints?.map { it.lowercase() } ?: emptyList()
        val tipo = n.inputType
        val variacion = tipo and InputType.TYPE_MASK_VARIATION
        val claseTexto = (tipo and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT

        // Atributos HTML de los navegadores
        var tipoHtml = ""
        var autocompletar = ""
        var textoHtml = ""
        val html = n.htmlInfo
        if (html != null) {
            val atributos = html.attributes
            if (atributos != null) {
                for (a in atributos) {
                    val nombre = (a.first ?: "").lowercase()
                    val valor = (a.second ?: "").lowercase()
                    when (nombre) {
                        "type" -> tipoHtml = valor
                        "autocomplete", "ua-autofill-hints" -> autocompletar += " " + valor
                        "name", "id", "placeholder", "label", "aria-label" -> textoHtml += " " + valor
                    }
                }
            }
        }
        val etiqueta = ((n.idEntry ?: "") + " " + (n.hint ?: "") + " " + textoHtml).lowercase()
        val esCampoTexto = claseTexto || html != null || (n.className ?: "").contains("EditText")

        val usuarioPista = pistas.contains(View.AUTOFILL_HINT_USERNAME) || pistas.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS)
        val porPista = pistas.contains(View.AUTOFILL_HINT_PASSWORD) || pistas.any { it.contains("password") }
        val porTipo = claseTexto && (variacion == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variacion == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variacion == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val porHtml = tipoHtml == "password" || autocompletar.contains("password")
        val porEtiqueta = etiqueta.contains("pass") || etiqueta.contains("contraseña") || etiqueta.contains("clave") ||
            etiqueta.contains("pwd")
        val esClave = porPista || porTipo || porHtml || (porEtiqueta && !usuarioPista)

        val usuarioTipo = claseTexto && (variacion == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variacion == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val usuarioHtml = tipoHtml == "email" || autocompletar.contains("username") || autocompletar.contains("email")
        val usuarioEtiqueta = etiqueta.contains("user") || etiqueta.contains("email") || etiqueta.contains("mail") ||
            etiqueta.contains("login") || etiqueta.contains("usuario") || etiqueta.contains("correo") ||
            etiqueta.contains("account") || etiqueta.contains("cuenta") || etiqueta.contains("identif")
        val esUsuario = !esClave && (usuarioPista || usuarioTipo || usuarioHtml || usuarioEtiqueta)

        if (esClave || esUsuario || esCampoTexto) {
            val v = n.autofillValue
            val texto = if (v != null && v.isText) v.textValue.toString() else ""
            salida.add(Nodo(id, esClave, esUsuario, esCampoTexto, n.isFocused, texto))
        }
    }
}

/** Sugerencias «en línea», las que salen dentro de la barra del teclado (Android 11 o superior). */
object InlineBoveda {

    @RequiresApi(30)
    fun presentacion(c: Context, peticion: FillRequest?, indice: Int, titulo: String, subtitulo: String): InlinePresentation? {
        return try {
            val pedido = peticion?.inlineSuggestionsRequest ?: return null
            val especificaciones = pedido.inlinePresentationSpecs
            if (especificaciones.isEmpty()) return null
            val especificacion = especificaciones[minOf(indice, especificaciones.size - 1)]
            if (!UiVersions.getVersions(especificacion.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
            val atribucion = PendingIntent.getActivity(
                c, 0, Intent(c, DesbloqueoActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val contenido = InlineSuggestionUi.newContentBuilder(atribucion)
                .setTitle(titulo)
                .setSubtitle(subtitulo)
                .build()
            InlinePresentation(contenido.slice, especificacion, false)
        } catch (e: Throwable) {
            Log.w("BovedaAutofill", "no se pudo crear la sugerencia en línea: " + e.javaClass.simpleName)
            null
        }
    }
}

/** Decide qué credenciales encajan con un formulario y construye la respuesta para el sistema. */
object ConstructorRespuesta {

    /**
     * Coincidencia estricta: el sitio web debe ser el mismo dominio guardado o un subdominio suyo (o al revés);
     * en apps, el paquete debe ser el vinculado. Nunca se sugiere por parecido de nombre.
     */
    fun encaja(c: Credencial, dominioWeb: String?, paquete: String): Boolean {
        if (!dominioWeb.isNullOrEmpty()) {
            val host = UtilRed.extraerHost(c.url) ?: return false
            return dominioWeb == host || dominioWeb.endsWith("." + host) || host.endsWith("." + dominioWeb)
        }
        return c.appPaquete.isNotEmpty() && c.appPaquete == paquete
    }

    private fun vista(c: Context, titulo: String, sub: String): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.autofill_item)
        v.setTextViewText(R.id.autofill_titulo, titulo)
        v.setTextViewText(R.id.autofill_sub, sub)
        return v
    }

    private fun idsDe(campos: CamposAutofill): Array<AutofillId> = listOfNotNull(campos.usuarioId, campos.claveId).toTypedArray()

    /** Bóveda cerrada: una sola entrada «Desbloquear» que abre la pantalla de desbloqueo. */
    fun pedirDesbloqueo(c: Context, campos: CamposAutofill, peticion: FillRequest?): FillResponse {
        val intent = Intent(c, DesbloqueoActivity::class.java)
        ExtrasAutofill.poner(intent, campos)
        val pendiente = PendingIntent.getActivity(c, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val remota = vista(c, "La Bóveda", "Toca para desbloquear")
        val b = FillResponse.Builder()
        var enLinea: InlinePresentation? = null
        if (Build.VERSION.SDK_INT >= 30) enLinea = InlineBoveda.presentacion(c, peticion, 0, "La Bóveda", "Toca para desbloquear")
        if (Build.VERSION.SDK_INT >= 30 && enLinea != null) {
            b.setAuthentication(idsDe(campos), pendiente.intentSender, remota, enLinea)
        } else {
            b.setAuthentication(idsDe(campos), pendiente.intentSender, remota)
        }
        return b.build()
    }

    /** Bóveda abierta: un dato por credencial que encaje, y la opción de guardar lo que se escriba. */
    class Resultado(val respuesta: FillResponse?, val coincidencias: Int)

    /** Los conjuntos de datos se muestran siempre, aunque el usuario ya haya tecleado algo en el campo. */
    private val SIN_FILTRO: Pattern = Pattern.compile(".*", Pattern.DOTALL)

    private fun poner(d: Dataset.Builder, id: AutofillId, valor: String) {
        if (Build.VERSION.SDK_INT >= 27) {
            d.setValue(id, AutofillValue.forText(valor), SIN_FILTRO)
        } else {
            d.setValue(id, AutofillValue.forText(valor))
        }
    }

    suspend fun conDatos(c: Context, campos: CamposAutofill, peticion: FillRequest? = null): FillResponse? {
        return construir(c, campos, peticion).respuesta
    }

    suspend fun construir(c: Context, campos: CamposAutofill, peticion: FillRequest? = null): Resultado {
        val dao = BovedaDb.actual()?.credenciales() ?: return Resultado(null, 0)
        val b = FillResponse.Builder()
        var hay = false
        var conjuntos = 0
        for (cred in dao.todas()) {
            if (!encaja(cred, campos.dominioWeb, campos.paquete)) continue
            val d = Dataset.Builder(vista(c, cred.titulo, cred.usuario))
            if (Build.VERSION.SDK_INT >= 30) {
                val enLinea = InlineBoveda.presentacion(c, peticion, conjuntos, cred.titulo, cred.usuario)
                if (enLinea != null) d.setInlinePresentation(enLinea)
            }
            var valores = 0
            if (campos.usuarioId != null) {
                poner(d, campos.usuarioId, cred.usuario)
                valores++
            }
            if (campos.claveId != null) {
                poner(d, campos.claveId, cred.clave)
                valores++
            }
            if (valores > 0) {
                b.addDataset(d.build())
                hay = true
                conjuntos++
            }
        }
        if (campos.claveId != null) {
            b.setSaveInfo(SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, idsDe(campos)).build())
            hay = true
        }
        Log.d("BovedaAutofill", "respuesta con datos: coincidencias=" + conjuntos + " guardar=" + (campos.claveId != null))
        return Resultado(if (hay) b.build() else null, conjuntos)
    }
}

class BovedaAutofillService : AutofillService() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    override fun onConnected() {
        Log.d("BovedaAutofill", "servicio conectado")
    }

    override fun onDisconnected() {
        Log.d("BovedaAutofill", "servicio desconectado")
    }

    override fun onFillRequest(peticion: FillRequest, cancelacion: CancellationSignal, callback: FillCallback) {
        try {
            val estructura = peticion.fillContexts.last().structure
            val campos = ParserEstructura.analizar(estructura)
            val enLinea = Build.VERSION.SDK_INT >= 30 && peticion.inlineSuggestionsRequest != null
            Log.d(
                "BovedaAutofill",
                "petición: app=" + campos.paquete + " web=" + campos.dominioWeb + " " + campos.resumen +
                    " bloqueada=" + !SesionBoveda.desbloqueada + " enLinea=" + enLinea
            )
            // No se ofrece nada dentro de La Bóveda misma, ni donde no hay campos de acceso.
            if (!campos.hayCampos || campos.paquete == packageName) {
                Log.d("BovedaAutofill", "sin campos de acceso: no se ofrece nada")
                callback.onSuccess(null)
                return
            }
            if (!SesionBoveda.desbloqueada) {
                // Si el formulario ya tiene una contraseña tecleada (por ejemplo, un registro), se recuerda unos minutos en memoria.
                PendienteGuardar.guardar(campos.usuarioValor, campos.claveValor, campos.dominioWeb, campos.paquete)
                Log.d("BovedaAutofill", "bóveda cerrada: se ofrece desbloquear (datos retenidos=" + PendienteGuardar.hay() + ")")
                callback.onSuccess(ConstructorRespuesta.pedirDesbloqueo(this, campos, peticion))
                return
            }
            val trabajo: Job = ambito.launch {
                try {
                    callback.onSuccess(ConstructorRespuesta.conDatos(applicationContext, campos, peticion))
                } catch (e: Exception) {
                    Log.e("BovedaAutofill", "error al preparar las sugerencias", e)
                    callback.onSuccess(null)
                }
            }
            cancelacion.setOnCancelListener { trabajo.cancel() }
        } catch (e: Throwable) {
            Log.e("BovedaAutofill", "error en onFillRequest", e)
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(peticion: SaveRequest, callback: SaveCallback) {
        val estructura = peticion.fillContexts.last().structure
        val campos = ParserEstructura.analizar(estructura)
        if (campos.claveValor.isEmpty()) {
            callback.onSuccess()
            return
        }
        if (!SesionBoveda.desbloqueada) {
            PendienteGuardar.guardar(campos.usuarioValor, campos.claveValor, campos.dominioWeb, campos.paquete)
            Log.d("BovedaAutofill", "guardar con la bóveda cerrada: se pide desbloquear")
            if (Build.VERSION.SDK_INT >= 28) {
                val intent = Intent(this, DesbloqueoActivity::class.java)
                intent.putExtra(ExtrasAutofill.ES_GUARDAR, true)
                val pendiente = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                callback.onSuccess(pendiente.intentSender)
            } else {
                callback.onFailure("La Bóveda está bloqueada. Desbloquéala y guarda la contraseña desde la app.")
            }
            return
        }
        ambito.launch {
            try {
                val dao = BovedaDb.actual()?.credenciales()
                if (dao == null) {
                    callback.onFailure("La Bóveda está bloqueada.")
                    return@launch
                }
                val ahora = System.currentTimeMillis()
                val existente = dao.todas().firstOrNull { it.usuario == campos.usuarioValor && ConstructorRespuesta.encaja(it, campos.dominioWeb, campos.paquete) }
                if (existente != null) {
                    dao.actualizar(existente.copy(clave = campos.claveValor, modificado = ahora))
                } else {
                    val titulo = campos.dominioWeb ?: campos.paquete
                    dao.insertar(
                        Credencial(
                            titulo = titulo, usuario = campos.usuarioValor, clave = campos.claveValor,
                            url = if (campos.dominioWeb != null) "https://" + campos.dominioWeb else "",
                            appPaquete = if (campos.dominioWeb == null) campos.paquete else "",
                            notas = "", favorito = false, creado = ahora, modificado = ahora
                        )
                    )
                }
                callback.onSuccess()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(applicationContext, "Guardado en La Bóveda", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                callback.onFailure("No se pudo guardar: " + e.javaClass.simpleName)
            }
        }
    }
}
