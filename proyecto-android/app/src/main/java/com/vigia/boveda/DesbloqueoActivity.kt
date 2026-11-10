package com.vigia.boveda

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.vigia.core.R
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Crea la bóveda la primera vez y la desbloquea después (contraseña maestra, huella/rostro o teclado seguro).
 * También es la pantalla de autenticación del autocompletado: si llega con los datos del formulario, al
 * terminar devuelve al sistema las credenciales que encajan.
 */
class DesbloqueoActivity : ActividadBoveda() {

    override val requiereDesbloqueo: Boolean get() = false

    private var crear = false
    private var acepto = false
    private lateinit var campo: EditText
    private var campo2: EditText? = null
    private lateinit var mensaje: TextView
    private lateinit var fuerza: TextView
    private lateinit var boton: MaterialButton
    private lateinit var marcoTeclado: FrameLayout
    private var teclado: TecladoSeguro? = null
    private var tecladoActivo = false

    override fun alCrear(estado: Bundle?) {
        if (SesionBoveda.desbloqueada) {
            terminar()
            return
        }
        crear = !ClavesBoveda.existe(this)
        construirUi()
        if (AjustesBoveda.tecladoSeguroPorDefecto(this)) alternarTeclado()
        if (!crear && BiometriaBoveda.activada(this) && BiometriaBoveda.disponible(this)) pedirBiometria()
    }

    private fun nuevoCampo(pista: String): EditText {
        val e = EditText(this)
        e.hint = pista
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        e.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        e.setTextColor(Vistas.color(this, R.color.vc_texto))
        e.setHintTextColor(Vistas.color(this, R.color.vc_texto_sec))
        e.setOnFocusChangeListener { _, tieneFoco -> if (tieneFoco) teclado?.destino = e }
        return e
    }

    private fun construirUi() {
        val raiz = Vistas.columna(this, 20)
        raiz.addView(Vistas.titulo(this, "La Bóveda"), Vistas.anchoCompleto(this, 2))
        raiz.addView(
            Vistas.texto(this, if (crear) "Crea tu contraseña maestra. Es la única llave de todo lo que guardes aquí." else "Escribe tu contraseña maestra.", true),
            Vistas.anchoCompleto(this, 16)
        )
        campo = nuevoCampo(if (crear) "Contraseña maestra (mínimo 10 caracteres)" else "Contraseña maestra")
        raiz.addView(campo, Vistas.anchoCompleto(this, 8))

        fuerza = Vistas.texto(this, "", true)
        mensaje = Vistas.texto(this, "")
        mensaje.setTextColor(Vistas.color(this, R.color.vc_aviso))

        if (crear) {
            val c2 = nuevoCampo("Repite la contraseña")
            campo2 = c2
            raiz.addView(c2, Vistas.anchoCompleto(this, 8))
            raiz.addView(fuerza, Vistas.anchoCompleto(this, 8))
            campo.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val n = FuerzaClave.nivel(campo.text.toString().toCharArray())
                    fuerza.text = "Fuerza estimada: " + FuerzaClave.etiqueta(n)
                }
            })
            val cb = MaterialCheckBox(this)
            cb.text = "Entiendo que si la olvido NO hay forma de recuperar mis datos."
            cb.setTextColor(Vistas.color(this, R.color.vc_texto))
            cb.setOnCheckedChangeListener { _, v -> acepto = v }
            raiz.addView(cb, Vistas.anchoCompleto(this, 8))
        }

        raiz.addView(mensaje, Vistas.anchoCompleto(this, 8))
        boton = Vistas.boton(this, if (crear) "Crear bóveda" else "Desbloquear") { enviar() }
        raiz.addView(boton, Vistas.anchoCompleto(this, 8))

        if (!crear && BiometriaBoveda.activada(this) && BiometriaBoveda.disponible(this)) {
            raiz.addView(Vistas.boton(this, "Usar huella o rostro", TipoBoton.TONAL) { pedirBiometria() }, Vistas.anchoCompleto(this, 8))
        }
        raiz.addView(Vistas.boton(this, "Teclado seguro", TipoBoton.TONAL) { alternarTeclado() }, Vistas.anchoCompleto(this, 8))
        marcoTeclado = FrameLayout(this)
        raiz.addView(marcoTeclado, Vistas.anchoCompleto(this, 0))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    private fun alternarTeclado() {
        val campos = listOfNotNull(campo, campo2)
        val imm = getSystemService(InputMethodManager::class.java)
        if (tecladoActivo) {
            tecladoActivo = false
            marcoTeclado.removeAllViews()
            teclado = null
            for (c in campos) c.setShowSoftInputOnFocus(true)
            return
        }
        tecladoActivo = true
        for (c in campos) c.setShowSoftInputOnFocus(false)
        imm.hideSoftInputFromWindow(campo.windowToken, 0)
        val destino = campos.firstOrNull { it.hasFocus() } ?: campo
        val t = TecladoSeguro(this, destino)
        teclado = t
        marcoTeclado.removeAllViews()
        marcoTeclado.addView(t)
        destino.requestFocus()
    }

    private fun enviar() {
        val ahora = System.currentTimeMillis()
        val hasta = AjustesBoveda.bloqueadoHasta(this)
        if (!crear && ahora < hasta) {
            mensaje.text = "Demasiados intentos. Espera " + ((hasta - ahora) / 1000 + 1) + " s."
            return
        }
        val a = campo.text.toString().toCharArray()
        if (crear) {
            val b = (campo2?.text?.toString() ?: "").toCharArray()
            if (a.size < 10) {
                mensaje.text = "La contraseña debe tener al menos 10 caracteres."
                return
            }
            if (!a.contentEquals(b)) {
                mensaje.text = "Las dos contraseñas no coinciden."
                return
            }
            if (!acepto) {
                mensaje.text = "Marca la casilla para confirmar que lo entiendes."
                return
            }
        } else if (a.isEmpty()) {
            return
        }
        boton.isEnabled = false
        mensaje.text = if (crear) "Creando la bóveda…" else "Comprobando…"
        lifecycleScope.launch {
            var dek: ByteArray? = null
            var danada = false
            withContext(Dispatchers.IO) {
                try {
                    dek = if (crear) ClavesBoveda.crear(applicationContext, a) else ClavesBoveda.desbloquear(applicationContext, a)
                } catch (e: IllegalArgumentException) {
                    danada = true
                }
            }
            a.fill(' ')
            val clave = dek
            if (clave == null) {
                boton.isEnabled = true
                if (danada) {
                    mensaje.text = "El archivo de la bóveda está dañado."
                } else {
                    AjustesBoveda.registrarFallo(this@DesbloqueoActivity)
                    mensaje.text = "Contraseña incorrecta."
                }
                return@launch
            }
            AjustesBoveda.registrarAcierto(this@DesbloqueoActivity)
            SesionBoveda.abrir(this@DesbloqueoActivity, clave)
            clave.fill(0)
            terminar()
        }
    }

    private fun pedirBiometria() {
        BiometriaBoveda.desbloquear(
            this,
            { clave ->
                AjustesBoveda.registrarAcierto(this)
                SesionBoveda.abrir(this, clave)
                clave.fill(0)
                terminar()
            },
            { texto -> mensaje.text = texto },
            { }
        )
    }

    /** Fin del desbloqueo: o entrega las credenciales al autocompletado, o abre la bóveda. */
    /** Abre el editor con los datos del formulario que se vieron con la bóveda cerrada, para que el usuario los confirme. */
    private fun abrirEditorConPendiente(): Boolean {
        val p = PendienteGuardar.tomar() ?: return false
        val i = Intent(this, EditarCredencialActivity::class.java)
        i.putExtra(EditarCredencialActivity.EXTRA_PRE_TITULO, p.dominio ?: p.paquete)
        i.putExtra(EditarCredencialActivity.EXTRA_PRE_USUARIO, p.usuario)
        i.putExtra(EditarCredencialActivity.EXTRA_PRE_CLAVE, p.clave)
        i.putExtra(EditarCredencialActivity.EXTRA_PRE_URL, if (p.dominio != null) "https://" + p.dominio else "")
        i.putExtra(EditarCredencialActivity.EXTRA_PRE_APP, if (p.dominio == null) p.paquete else "")
        startActivity(i)
        return true
    }

    private fun terminar() {
        if (intent.getBooleanExtra(PuenteContrato.ES_PUENTE, false)) {
            // Autenticación pedida por el puente con el Teclado Soberano: se devuelve el control a PuenteTecladoActivity.
            setResult(Activity.RESULT_OK)
            finish()
        } else if (intent.getBooleanExtra(ExtrasAutofill.ES_GUARDAR, false)) {
            // Vino del aviso «Guardar en La Bóveda» con la bóveda cerrada: ahora que está abierta, se abre el editor relleno.
            if (!abrirEditorConPendiente()) startActivity(Intent(this, PrincipalActivity::class.java))
            setResult(Activity.RESULT_CANCELED)
            finish()
        } else if (intent.getBooleanExtra(ExtrasAutofill.ES_AUTOFILL, false)) {
            val campos = ExtrasAutofill.leer(intent)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { ConstructorRespuesta.construir(applicationContext, campos) }
                android.util.Log.d("BovedaAutofill", "desbloqueo terminado: coincidencias=" + r.coincidencias)
                val respuesta = r.respuesta
                if (respuesta != null && r.coincidencias > 0) {
                    PendienteGuardar.olvidar()
                    setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, respuesta))
                } else {
                    // Sin credenciales para este sitio: si se estaba rellenando un registro, se ofrece guardarlo ya relleno.
                    setResult(Activity.RESULT_CANCELED)
                    abrirEditorConPendiente()
                }
                finish()
            }
        } else {
            startActivity(Intent(this, PrincipalActivity::class.java))
            finish()
        }
    }
}
