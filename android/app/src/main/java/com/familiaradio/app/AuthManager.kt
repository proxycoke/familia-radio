package com.familiaradio.app

import android.app.Activity
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
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
    private val auth = FirebaseAuth.getInstance()
    private var verificationId: String? = null
    private var pendingPhoneCredential: PhoneAuthCredential? = null

    val isAuthenticated: Boolean get() = auth.currentUser != null

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
                onCodeSent()
            }
        }
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
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
                    ?.addOnFailureListener { e -> onError(e.message ?: "No se pudo vincular el teléfono") }
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
            .addOnFailureListener { e -> onError(e.message ?: "Código inválido") }
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

    // Bloqueante: solo se debe llamar desde un hilo secundario.
    fun getIdTokenBlocking(): String? {
        val user = auth.currentUser ?: return null
        return try {
            Tasks.await(user.getIdToken(false)).token
        } catch (e: Exception) {
            null
        }
    }
}
