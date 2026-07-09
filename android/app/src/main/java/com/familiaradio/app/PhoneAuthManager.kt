package com.familiaradio.app

import android.app.Activity
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

/**
 * Envuelve el login por teléfono + OTP de Firebase Auth. La identidad real
 * del usuario ante el servidor pasa a ser el número de teléfono verificado
 * acá, no algo que la app inventa localmente.
 */
class PhoneAuthManager {
    private val auth = FirebaseAuth.getInstance()
    private var verificationId: String? = null

    val isAuthenticated: Boolean get() = auth.currentUser != null

    fun sendCode(
        activity: Activity,
        phoneNumber: String,
        onCodeSent: () -> Unit,
        onAutoVerified: () -> Unit,
        onError: (String) -> Unit
    ) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                signIn(credential, onAutoVerified, onError)
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

    fun confirmCode(code: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val id = verificationId
        if (id == null) {
            onError("Pedí un código nuevo antes de confirmar")
            return
        }
        val credential = PhoneAuthProvider.getCredential(id, code)
        signIn(credential, onSuccess, onError)
    }

    private fun signIn(credential: PhoneAuthCredential, onSuccess: () -> Unit, onError: (String) -> Unit) {
        auth.signInWithCredential(credential)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(e.message ?: "Código inválido") }
    }

    fun signOut() {
        auth.signOut()
    }

    // Bloqueante: solo se debe llamar desde un hilo secundario (ya es el caso
    // en httpGet/httpPostJson, que corren en su propio Thread).
    fun getIdTokenBlocking(): String? {
        val user = auth.currentUser ?: return null
        return try {
            Tasks.await(user.getIdToken(false)).token
        } catch (e: Exception) {
            null
        }
    }
}
