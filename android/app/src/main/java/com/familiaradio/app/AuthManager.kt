package com.familiaradio.app

import android.app.Activity
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

/**
 * Todo el login de la app: correo/contraseña, teléfono + OTP (registro),
 * Google, y recuperación de contraseña vía teléfono. La identidad real
 * ante el servidor sigue siendo el número de teléfono verificado — por
 * eso el registro por teléfono vincula ese teléfono a la cuenta de
 * correo/contraseña recién creada (así ambos métodos abren la misma
 * cuenta, y la recuperación de contraseña por teléfono puede autenticar
 * y después cambiar la contraseña de esa misma cuenta).
 */
class AuthManager {
    companion object {
        // Sentinel devuelto en vez del mensaje crudo de Firebase cuando el código OTP
        // ingresado es incorrecto — la UI (que sí tiene acceso a stringResource) lo
        // traduce al mensaje amigable en el idioma correspondiente.
        const val ERR_INVALID_OTP = "__ERR_INVALID_OTP__"
    }

    private val auth = FirebaseAuth.getInstance()
    private var verificationId: String? = null
    private var pendingPhoneCredential: PhoneAuthCredential? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null
    private var lastPhoneNumber: String? = null

    val isAuthenticated: Boolean get() = auth.currentUser != null

    // El backend exige un token con teléfono verificado (ver requireVerifiedPhone en el
    // servidor) — un login por Google solo no trae eso, hay que vincular un teléfono aparte.
    val hasVerifiedPhone: Boolean get() = auth.currentUser?.phoneNumber != null

    fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(e.message ?: "No se pudo iniciar sesión") }
    }

    fun signInWithGoogleIdToken(idToken: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(e.message ?: "No se pudo iniciar sesión con Google") }
    }

    // Paso 1 del registro por teléfono (y también de "olvidé mi contraseña"): mandar OTP por SMS.
    // Si ya se le pidió un código a este mismo número antes, usa el "resend token" de Firebase
    // para que sea un reenvío real — sin eso, Firebase puede ignorar en silencio los pedidos
    // repetidos al mismo número en poco tiempo (protección antiabuso) y el callback nunca llega.
    fun sendPhoneCode(
        activity: Activity,
        phoneNumber: String,
        onCodeSent: () -> Unit,
        onAutoVerified: () -> Unit,
        onError: (String) -> Unit
    ) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                pendingPhoneCredential = credential
                onAutoVerified()
            }

            override fun onVerificationFailed(e: FirebaseException) {
                onError(e.message ?: "No se pudo verificar el número")
            }

            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                verificationId = id
                resendToken = token
                onCodeSent()
            }
        }
        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
        val tokenToReuse = resendToken
        if (phoneNumber == lastPhoneNumber && tokenToReuse != null) {
            builder.setForceResendingToken(tokenToReuse)
        }
        lastPhoneNumber = phoneNumber
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    // La validación real del código OTP no pasa acá (confirmPhoneCode solo arma el
    // credential) sino en el signInWithCredential/linkWithCredential que se hace después,
    // así que ahí es donde Firebase avisa si el código estaba mal.
    private fun mapPhoneError(e: Exception, fallback: String): String {
        val code = (e as? FirebaseAuthException)?.errorCode
        return if (code == "ERROR_INVALID_VERIFICATION_CODE") ERR_INVALID_OTP else (e.message ?: fallback)
    }

    // Confirma el código y lo deja "pendiente" para el paso siguiente (crear cuenta o cambiar contraseña).
    fun confirmPhoneCode(code: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val id = verificationId
        if (id == null) {
            onError("Pedí un código nuevo antes de confirmar")
            return
        }
        pendingPhoneCredential = PhoneAuthProvider.getCredential(id, code)
        onSuccess()
    }

    // Paso final del registro: crea la cuenta de correo/contraseña y le vincula el teléfono ya verificado.
    fun completeRegistration(email: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val phoneCredential = pendingPhoneCredential
        if (phoneCredential == null) {
            onError("Verificá tu teléfono antes de continuar")
            return
        }
        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                auth.currentUser?.linkWithCredential(phoneCredential)
                    ?.addOnSuccessListener { onSuccess() }
                    ?.addOnFailureListener { e -> onError(mapPhoneError(e, "No se pudo vincular el teléfono")) }
                    ?: onError("No se pudo crear la cuenta")
            }
            .addOnFailureListener { e -> onError(e.message ?: "No se pudo crear la cuenta") }
    }

    // "Olvidé mi contraseña": el código ya confirmado (confirmPhoneCode) autentica como el dueño
    // de ese teléfono, siempre que ya esté vinculado a una cuenta — eso habilita setNewPassword().
    fun signInWithPendingPhoneCredential(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val credential = pendingPhoneCredential
        if (credential == null) {
            onError("Verificá el código primero")
            return
        }
        auth.signInWithCredential(credential)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(mapPhoneError(e, "Código inválido")) }
    }

    // Vincula el teléfono recién verificado al usuario YA autenticado (por ejemplo, con
    // Google) — a diferencia de completeRegistration(), acá no se crea ninguna cuenta nueva.
    fun linkPendingPhoneToCurrentUser(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val credential = pendingPhoneCredential
        if (credential == null) {
            onError("Verificá el código primero")
            return
        }
        val user = auth.currentUser
        if (user == null) {
            onError("Sesión no encontrada")
            return
        }
        user.linkWithCredential(credential)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(mapPhoneError(e, "No se pudo vincular el teléfono")) }
    }

    fun setNewPassword(newPassword: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onError("Sesión no encontrada")
            return
        }
        user.updatePassword(newPassword)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(e.message ?: "No se pudo cambiar la contraseña") }
    }

    fun signOut() {
        auth.signOut()
    }

    // Bloqueante: solo se debe llamar desde un hilo secundario. Fuerza refresco (true) porque
    // justo después de vincular un teléfono necesitamos que el token ya traiga phone_number —
    // un token cacheado podría no reflejar el vínculo recién hecho.
    fun getIdTokenBlocking(): String? {
        val user = auth.currentUser ?: return null
        return try {
            Tasks.await(user.getIdToken(true)).token
        } catch (e: Exception) {
            null
        }
    }
}
