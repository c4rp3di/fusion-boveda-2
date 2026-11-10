package com.vigia.boveda

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.ResultReceiver
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.vigia.core.R
import com.vigia.core.red.UtilRed
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puerta de entrada del puente con el Teclado Soberano (el botón 🔑).
 *
 * Seguridad:
 *  - Está protegida en el Manifest por un permiso de firma: el sistema solo deja abrirla a apps firmadas con la
 *    misma llave que La Bóveda. Ninguna otra app puede lanzarla ni recibir la respuesta.
 *  - Cada petición exige autenticación nueva: se cierra la sesión y se pasa por DesbloqueoActivity
 *    (huella/rostro o contraseña maestra).
 *  - La persona elige la credencial en esta pantalla (la contraseña nunca se muestra ni se lista en el teclado).
 *  - La respuesta viaja cifrada (ECDH efímero + AES-GCM, ver PuenteCanal) por el ResultReceiver que
 *    el teclado incluyó en el Intent. No se usa el portapapeles ni se escribe nada en logs.
 */
class PuenteTecladoActivity : ActividadBoveda() {

    // La autenticación se gestiona aquí a mano: no queremos la redirección automática de la base.
    override val requiereDesbloqueo: Boolean get() = false

    private var receptor: ResultReceiver? = null
    private var idPeticion = ByteArray(0)
    private var clavePublicaTeclado = ByteArray(0)
    private var destino = ""
    private var respondido = false

    private var credenciales: List<Credencial> = emptyList()
    private var filtro = ""
    private var lista: LinearLayout? = null

    private val lanzadorDesbloqueo = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && SesionBoveda.desbloqueada) {
            mostrarSelector()
        } else {
            responder(PuenteContrato.COD_CANCELADO)
        }
    }

    override fun alCrear(estado: Bundle?) {
        receptor = IntentCompat.getParcelableExtra(intent, PuenteContrato.EXTRA_RECEPTOR, ResultReceiver::class.java)
        if (receptor == null) {
            finish()
            return
        }
        // Si la pantalla se recrea (p. ej. el proceso murió), la petición original ya no es válida.
        if (estado != null) {
            responder(PuenteContrato.COD_CANCELADO)
            return
        }

        val version = intent.getIntExtra(PuenteContrato.EXTRA_VERSION, 0)
        idPeticion = intent.getByteArrayExtra(PuenteContrato.EXTRA_ID) ?: ByteArray(0)
        clavePublicaTeclado = intent.getByteArrayExtra(PuenteContrato.EXTRA_CLAVE_PUBLICA) ?: ByteArray(0)
        destino = intent.getStringExtra(PuenteContrato.EXTRA_DESTINO) ?: ""

        val valida = version == PuenteContrato.VERSION &&
            idPeticion.size == 16 &&
            destino.isNotEmpty() && destino.length <= 255 &&
            PuenteCanal.esPublicaValida(clavePublicaTeclado)
        if (!valida) {
            responder(PuenteContrato.COD_ERROR)
            return
        }

        if (!ClavesBoveda.existe(this)) {
            responder(PuenteContrato.COD_NO_INICIALIZADA)
            return
        }

        // Autenticación nueva en cada petición: se cierra la sesión y se pide huella/rostro o contraseña maestra.
        SesionBoveda.bloquear()
        lanzadorDesbloqueo.launch(
            Intent(this, DesbloqueoActivity::class.java).putExtra(PuenteContrato.ES_PUENTE, true)
        )
    }

    // ================================================================== SELECTOR

    private fun mostrarSelector() {
        lifecycleScope.launch {
            credenciales = withContext(Dispatchers.IO) {
                BovedaDb.actual()?.credenciales()?.todas() ?: emptyList()
            }
            construirUi()
        }
    }

    private fun construirUi() {
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Elegir credencial"), Vistas.anchoCompleto(this, 2))
        raiz.addView(
            Vistas.texto(this, "El Teclado Soberano la escribirá en: " + etiquetaDestino(), true),
            Vistas.anchoCompleto(this, 10)
        )

        val buscador = EditText(this)
        buscador.hint = "Buscar…"
        buscador.setSingleLine(true)
        buscador.setTextColor(Vistas.color(this, R.color.vc_texto))
        buscador.setHintTextColor(Vistas.color(this, R.color.vc_texto_sec))
        buscador.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        buscador.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filtro = s?.toString() ?: ""
                pintar()
            }
        })
        raiz.addView(buscador, Vistas.anchoCompleto(this, 6))

        val contenedor = LinearLayout(this)
        contenedor.orientation = LinearLayout.VERTICAL
        lista = contenedor
        val scroll = ScrollView(this)
        scroll.addView(contenedor)
        raiz.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        raiz.addView(
            Vistas.boton(this, "Cancelar", TipoBoton.TONAL) { responder(PuenteContrato.COD_CANCELADO) },
            Vistas.anchoCompleto(this, 0)
        )
        setContentView(raiz)
        pintar()
    }

    private fun vinculada(c: Credencial): Boolean = c.appPaquete.isNotEmpty() && c.appPaquete == destino

    private fun pintar() {
        val l = lista ?: return
        l.removeAllViews()
        val q = filtro.trim().lowercase()
        val coincide = { c: Credencial ->
            q.isEmpty() || c.titulo.lowercase().contains(q) || c.usuario.lowercase().contains(q) || c.url.lowercase().contains(q)
        }
        val vinculadas = credenciales.filter { vinculada(it) && coincide(it) }
        val otras = credenciales.filter { !vinculada(it) && coincide(it) }

        if (credenciales.isEmpty()) {
            l.addView(Vistas.texto(this, "La bóveda todavía no tiene contraseñas.", true))
            return
        }
        if (vinculadas.isNotEmpty()) {
            l.addView(Vistas.subtitulo(this, "Vinculadas a esta app"), Vistas.anchoCompleto(this, 6))
            for (c in vinculadas) l.addView(tarjetaDe(c, true), Vistas.anchoCompleto(this, 8))
        } else if (q.isEmpty()) {
            val aviso = Vistas.texto(
                this,
                "Ninguna contraseña está vinculada a esta app. Si estás en un navegador, elige la del sitio web con cuidado.",
                false
            )
            aviso.setTextColor(Vistas.color(this, R.color.vc_aviso))
            l.addView(aviso, Vistas.anchoCompleto(this, 8))
        }
        if (otras.isNotEmpty()) {
            l.addView(Vistas.subtitulo(this, "Otras contraseñas"), Vistas.anchoCompleto(this, 6))
            for (c in otras) l.addView(tarjetaDe(c, false), Vistas.anchoCompleto(this, 8))
        }
        if (vinculadas.isEmpty() && otras.isEmpty()) {
            l.addView(Vistas.texto(this, "Nada coincide con la búsqueda.", true))
        }
    }

    private fun tarjetaDe(c: Credencial, vinculada: Boolean): View {
        val t = Vistas.tarjeta(this)
        val nombre = (if (c.favorito) "★ " else "") + c.titulo
        t.addView(Vistas.texto(this, nombre).also { it.setTypeface(it.typeface, Typeface.BOLD); it.textSize = 16f })
        if (c.usuario.isNotEmpty()) t.addView(Vistas.texto(this, c.usuario, true))
        val host = if (c.url.isEmpty()) null else UtilRed.extraerHost(c.url)
        if (host != null) t.addView(Vistas.texto(this, host, true))
        t.setOnClickListener { if (vinculada) entregar(c) else confirmar(c) }
        return t
    }

    /** Las credenciales no vinculadas a la app de destino piden una confirmación extra (evita escribirlas donde no toca). */
    private fun confirmar(c: Credencial) {
        val d = AlertDialog.Builder(this)
            .setTitle("Credencial no vinculada")
            .setMessage("«" + c.titulo + "» no está vinculada a " + etiquetaDestino() + ". Se escribirá allí. ¿Continuar?")
            .setPositiveButton("Escribir") { _, _ -> entregar(c) }
            .setNegativeButton("Cancelar", null)
            .create()
        mostrarSeguro(d)
    }

    private fun etiquetaDestino(): String {
        return try {
            val info = packageManager.getApplicationInfo(destino, 0)
            packageManager.getApplicationLabel(info).toString() + " (" + destino + ")"
        } catch (e: Exception) {
            destino
        }
    }

    // ================================================================== RESPUESTA

    private fun entregar(c: Credencial) {
        if (respondido) return
        val u = c.usuario.toByteArray(Charsets.UTF_8)
        val k = c.clave.toByteArray(Charsets.UTF_8)
        val claro = PuenteCanal.empaquetar(u, k)
        u.fill(0)
        k.fill(0)
        try {
            val sobre = PuenteCanal.cifrar(clavePublicaTeclado, idPeticion, destino, claro)
            val datos = Bundle()
            datos.putByteArray(PuenteContrato.RES_PUBLICA_BOVEDA, sobre.publicaBoveda)
            datos.putByteArray(PuenteContrato.RES_IV, sobre.iv)
            datos.putByteArray(PuenteContrato.RES_CIFRADO, sobre.cifrado)
            responder(PuenteContrato.COD_OK, datos)
        } catch (e: Exception) {
            responder(PuenteContrato.COD_ERROR)
        } finally {
            claro.fill(0)
        }
    }

    private fun responder(codigo: Int, datos: Bundle? = null) {
        if (respondido) return
        respondido = true
        try {
            receptor?.send(codigo, datos)
        } catch (e: Exception) {
            // El teclado pudo haberse cerrado: no hay nadie a quien avisar.
        }
        setResult(if (codigo == PuenteContrato.COD_OK) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        // Atrás, cierre desde «recientes», etc.: se avisa al teclado para que no quede esperando.
        if (!respondido) {
            respondido = true
            try {
                receptor?.send(PuenteContrato.COD_CANCELADO, null)
            } catch (e: Exception) {
            }
        }
        super.onDestroy()
    }
}
