package com.familiaradio.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Servidor de tokens y familias: desplegado en Render, accesible desde cualquier red.
private const val TOKEN_SERVER_URL = "https://familia-radio-server.onrender.com"

// Paleta cálida y de alto contraste para el modo del familiar mayor.
private val AbuelaColors = lightColorScheme(
    primary = Color(0xFFD2691E),
    onPrimary = Color.White,
    secondary = Color(0xFFB3541A),
    background = Color(0xFFFFF6EC),
    surface = Color(0xFFFFF6EC),
    onBackground = Color(0xFF3A2A1A),
    onSurface = Color(0xFF3A2A1A),
    error = Color(0xFFB3261E)
)

// Paleta más sobria para el panel del cuidador.
private val CuidadorColors = lightColorScheme(
    primary = Color(0xFF2F6F6B),
    onPrimary = Color.White,
    secondary = Color(0xFF1E4D4A),
    background = Color(0xFFF1F8F7),
    surface = Color(0xFFF1F8F7),
    onBackground = Color(0xFF1B2E2C),
    onSurface = Color(0xFF1B2E2C),
    error = Color(0xFFB3261E)
)

private val NeutralColors = lightColorScheme(
    primary = Color(0xFF5B4B8A),
    onPrimary = Color.White,
    background = Color(0xFFF7F4FB),
    surface = Color(0xFFF7F4FB),
    onBackground = Color(0xFF2A2438),
    onSurface = Color(0xFF2A2438)
)

private fun colorsForRole(role: Role?) = when (role) {
    Role.ABUELA -> AbuelaColors
    Role.CUIDADOR -> CuidadorColors
    null -> NeutralColors
}

private data class Membership(
    val familyId: Int,
    val inviteCode: String,
    val channelName: String,
    val agoraUid: Int,
    val role: Role
)

private object MembershipStore {
    private const val PREFS = "familia_radio_prefs"

    fun load(context: Context): Membership? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val familyId = prefs.getInt("family_id", -1)
        if (familyId == -1) return null
        val inviteCode = prefs.getString("invite_code", null) ?: return null
        val channelName = prefs.getString("channel_name", null) ?: return null
        val agoraUid = prefs.getInt("agora_uid", -1)
        if (agoraUid == -1) return null
        val roleName = prefs.getString("role", null) ?: return null
        val role = runCatching { Role.valueOf(roleName) }.getOrNull() ?: return null
        return Membership(familyId, inviteCode, channelName, agoraUid, role)
    }

    fun save(context: Context, membership: Membership) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("family_id", membership.familyId)
            .putString("invite_code", membership.inviteCode)
            .putString("channel_name", membership.channelName)
            .putInt("agora_uid", membership.agoraUid)
            .putString("role", membership.role.name)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * MVP de walkie-talkie: dos roles se unen al mismo canal de voz Agora.
 * No hay "llamada" ni "contestar": el canal siempre está conectado,
 * el micrófono está muteado salvo mientras se mantiene presionado el botón.
 *
 * El familiar mayor necesita poder recibir audio sin tener la app abierta,
 * así que su conexión vive en [RadioService] (foreground service) en vez de
 * atada al ciclo de vida de esta Activity. El cuidador solo se conecta
 * mientras tiene la app en primer plano.
 */
class MainActivity : ComponentActivity() {

    private var onMicPermissionResult: ((Boolean) -> Unit)? = null
    private var localConnectionManager: AgoraConnectionManager? = null
    private var isServiceBound = false
    private val connectionManagerState = mutableStateOf<AgoraConnectionManager?>(null)
    private val authManager = AuthManager()
    private var onGoogleSignInResult: ((String?, String?) -> Unit)? = null

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            onGoogleSignInResult?.invoke(account.idToken, null)
        } catch (e: ApiException) {
            // 12501 (GoogleSignInStatusCodes.SIGN_IN_CANCELLED) = el usuario canceló el
            // selector de cuenta; 13 (CommonStatusCodes.CANCELED) puede darse en otros
            // casos de cancelación. Ninguno de los dos es un error real que mostrar.
            val cancelled = e.statusCode == com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.SIGN_IN_CANCELLED ||
                e.statusCode == com.google.android.gms.common.api.CommonStatusCodes.CANCELED
            onGoogleSignInResult?.invoke(null, if (cancelled) "" else (e.message ?: "Google sign-in falló"))
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connectionManagerState.value = (service as RadioService.LocalBinder).connectionManager
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            connectionManagerState.value = null
        }
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onMicPermissionResult?.invoke(granted) }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        MembershipStore.load(applicationContext)?.let { activateConnectionFor(it.role) }

        setContent {
            FamiliaRadioApp(
                isAuthenticated = { authManager.isAuthenticated },
                authCallbacks = AuthCallbacks(
                    signInWithEmail = { email, password, onSuccess, onError ->
                        authManager.signInWithEmail(email, password, onSuccess, onError)
                    },
                    signInWithGoogle = { onSuccess, onError ->
                        onGoogleSignInResult = { idToken, error ->
                            if (idToken != null) {
                                authManager.signInWithGoogleIdToken(idToken, onSuccess, onError)
                            } else {
                                onError(error ?: getString(R.string.error_google_sign_in_failed))
                            }
                        }
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(getString(R.string.default_web_client_id))
                            .requestEmail()
                            .build()
                        googleSignInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
                    },
                    sendPhoneCode = { phone, onCodeSent, onAutoVerified, onError ->
                        authManager.sendPhoneCode(this, phone, onCodeSent, onAutoVerified, onError)
                    },
                    confirmPhoneCode = { code, onSuccess, onError ->
                        authManager.confirmPhoneCode(code, onSuccess, onError)
                    },
                    completeRegistration = { email, password, onSuccess, onError ->
                        authManager.completeRegistration(email, password, onSuccess, onError)
                    },
                    saveProfile = { nombres, apellidos, fechaIso, email, onSuccess, onError ->
                        saveUserProfile(nombres, apellidos, fechaIso, email, onSuccess, onError)
                    },
                    signInWithPendingPhoneCredential = { onSuccess, onError ->
                        authManager.signInWithPendingPhoneCredential(onSuccess, onError)
                    },
                    setNewPassword = { newPassword, onSuccess, onError ->
                        authManager.setNewPassword(newPassword, onSuccess, onError)
                    },
                    signOut = { authManager.signOut() },
                    fetchRecoveryOptions = { email, onResult, onError ->
                        fetchRecoveryOptions(email, onResult, onError)
                    },
                    sendEmailOtp = { email, onSuccess, onError -> sendEmailOtp(email, onSuccess, onError) },
                    verifyEmailOtp = { email, code, onSuccess, onError -> verifyEmailOtp(email, code, onSuccess, onError) },
                    resetPasswordViaEmail = { email, code, newPassword, onSuccess, onError ->
                        resetPasswordViaEmail(email, code, newPassword, onSuccess, onError)
                    },
                    hasVerifiedPhone = { authManager.hasVerifiedPhone },
                    linkPendingPhone = { onSuccess, onError ->
                        authManager.linkPendingPhoneToCurrentUser(onSuccess, onError)
                    }
                ),
                loadMembership = { MembershipStore.load(applicationContext) },
                saveMembership = { membership ->
                    MembershipStore.save(applicationContext, membership)
                    activateConnectionFor(membership.role)
                },
                forgetMembership = { MembershipStore.clear(applicationContext) },
                createFamily = { role, onResult, onError -> createFamily(role, onResult, onError) },
                joinFamily = { code, role, onResult, onError -> joinFamily(code, role, onResult, onError) },
                hasMicPermission = { hasRecordAudioPermission() },
                requestMicPermission = { callback ->
                    onMicPermissionResult = callback
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                connectionManager = connectionManagerState,
                onActivateConnection = { role -> activateConnectionFor(role) },
                onStopConnection = { role -> stopConnectionFor(role) }
            )
        }
    }

    private fun activateConnectionFor(role: Role) {
        if (role == Role.ABUELA) {
            if (isServiceBound) return
            // Android exige que el permiso de micrófono ya esté otorgado antes de
            // poder arrancar un foreground service de tipo "microphone".
            if (hasRecordAudioPermission()) {
                startAndBindRadioService()
            } else {
                onMicPermissionResult = { granted -> if (granted) startAndBindRadioService() }
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            val manager = localConnectionManager ?: AgoraConnectionManager(applicationContext).also {
                localConnectionManager = it
            }
            connectionManagerState.value = manager
        }
    }

    private fun startAndBindRadioService() {
        val intent = Intent(this, RadioService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        isServiceBound = true
    }

    private fun stopConnectionFor(role: Role) {
        if (role == Role.ABUELA) {
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
            stopService(Intent(this, RadioService::class.java))
            connectionManagerState.value = null
        } else {
            localConnectionManager?.leaveChannel()
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun httpGet(path: String, onResult: (Int, String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val url = URL("$TOKEN_SERVER_URL$path")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                authManager.getIdTokenBlocking()?.let {
                    connection.setRequestProperty("Authorization", "Bearer $it")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.readText() ?: ""
                runOnUiThread { onResult(code, body) }
            } catch (e: Exception) {
                runOnUiThread { onError(getString(R.string.error_contacting_server, e.message)) }
            }
        }.start()
    }

    private fun httpPostJson(
        path: String,
        body: JSONObject,
        onResult: (Int, String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val url = URL("$TOKEN_SERVER_URL$path")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                authManager.getIdTokenBlocking()?.let {
                    connection.setRequestProperty("Authorization", "Bearer $it")
                }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader()?.readText() ?: ""
                runOnUiThread { onResult(code, responseBody) }
            } catch (e: Exception) {
                runOnUiThread { onError(getString(R.string.error_contacting_server, e.message)) }
            }
        }.start()
    }

    private fun parseMembership(json: JSONObject): Membership = Membership(
        familyId = json.getInt("familyId"),
        inviteCode = json.getString("inviteCode"),
        channelName = json.getString("channelName"),
        agoraUid = json.getInt("agoraUid"),
        role = Role.valueOf(json.getString("role"))
    )

    private fun createFamily(role: Role, onResult: (Membership) -> Unit, onError: (String) -> Unit) {
        val body = JSONObject().apply {
            put("role", role.name)
        }
        httpPostJson("/families", body, onResult = { code, responseBody ->
            if (code == 201) {
                onResult(parseMembership(JSONObject(responseBody)))
            } else {
                onError(errorMessageFrom(responseBody, getString(R.string.error_create_family, code)))
            }
        }, onError = onError)
    }

    private fun joinFamily(
        inviteCode: String,
        role: Role,
        onResult: (Membership) -> Unit,
        onError: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("role", role.name)
        }
        httpPostJson("/families/${inviteCode.trim().uppercase()}/join", body, onResult = { code, responseBody ->
            when (code) {
                201, 200 -> onResult(parseMembership(JSONObject(responseBody)))
                404 -> onError(getString(R.string.error_family_not_found))
                409 -> onError(errorMessageFrom(responseBody, getString(R.string.error_role_full)))
                else -> onError(errorMessageFrom(responseBody, getString(R.string.error_join_family, code)))
            }
        }, onError = onError)
    }

    private fun saveUserProfile(
        nombres: String,
        apellidos: String,
        fechaNacimientoIso: String,
        email: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("nombres", nombres)
            put("apellidos", apellidos)
            put("fechaNacimiento", fechaNacimientoIso)
            put("email", email)
        }
        httpPostJson("/users/profile", body, onResult = { code, responseBody ->
            if (code == 201) {
                onSuccess()
            } else {
                onError(errorMessageFrom(responseBody, "No se pudo guardar el perfil ($code)"))
            }
        }, onError = onError)
    }

    // Estos 4 llamados son públicos (sin token de Firebase, porque el usuario todavía
    // no inició sesión mientras recupera la contraseña) — los maneja el propio backend,
    // no Firebase, porque Firebase no tiene "código de 6 dígitos por correo" nativo.
    private fun fetchRecoveryOptions(
        email: String,
        onResult: (phone: String?, phoneMasked: String?, emailMasked: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val body = JSONObject().apply { put("email", email) }
        httpPostJson("/auth/recovery-options", body, onResult = { code, responseBody ->
            if (code == 200) {
                runCatching {
                    val json = JSONObject(responseBody)
                    val phone = if (json.has("phone") && !json.isNull("phone")) json.getString("phone") else null
                    val phoneMasked = if (json.has("phoneMasked") && !json.isNull("phoneMasked")) json.getString("phoneMasked") else null
                    Triple(phone, phoneMasked, json.getString("emailMasked"))
                }.onSuccess { (phone, phoneMasked, emailMasked) ->
                    onResult(phone, phoneMasked, emailMasked)
                }.onFailure {
                    onError("No se pudo leer la respuesta del servidor")
                }
            } else {
                onError(errorMessageFrom(responseBody, "No encontramos una cuenta con ese correo"))
            }
        }, onError = onError)
    }

    private fun sendEmailOtp(email: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val body = JSONObject().apply { put("email", email) }
        httpPostJson("/auth/email-otp/send", body, onResult = { code, responseBody ->
            if (code == 200) onSuccess() else onError(errorMessageFrom(responseBody, "No se pudo enviar el código"))
        }, onError = onError)
    }

    private fun verifyEmailOtp(email: String, otpCode: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val body = JSONObject().apply { put("email", email); put("code", otpCode) }
        httpPostJson("/auth/email-otp/verify", body, onResult = { code, responseBody ->
            if (code == 200) onSuccess() else onError(errorMessageFrom(responseBody, "Código inválido o expirado"))
        }, onError = onError)
    }

    private fun resetPasswordViaEmail(
        email: String,
        otpCode: String,
        newPassword: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val body = JSONObject().apply {
            put("email", email); put("code", otpCode); put("newPassword", newPassword)
        }
        httpPostJson("/auth/reset-password", body, onResult = { code, responseBody ->
            if (code == 200) onSuccess() else onError(errorMessageFrom(responseBody, "No se pudo cambiar la contraseña"))
        }, onError = onError)
    }

    private fun errorMessageFrom(responseBody: String, fallback: String): String =
        runCatching { JSONObject(responseBody).getString("error") }.getOrDefault(fallback)

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        localConnectionManager?.leaveChannel()
        super.onDestroy()
    }
}

private enum class Role { ABUELA, CUIDADOR }

// Compartido entre pantallas para que LanguageToggle (usado en varios archivos/composables)
// pueda animar la misma transición de deslizar+desvanecer que usa AuthFlow entre pantallas,
// antes de disparar el cambio de idioma (que recrea la Activity de golpe). Mientras dura ese
// recreate mostramos un loader con el fondo normal del tema, para que nunca se vea una
// pantalla negra/en blanco de golpe.
private class LanguageTransition {
    val alpha = Animatable(1f)
    val offsetX = Animatable(0f)
    var showLoader by mutableStateOf(false)
}

private val LocalLanguageTransition = compositionLocalOf<LanguageTransition?> { null }

@Composable
private fun FamiliaRadioApp(
    isAuthenticated: () -> Boolean,
    authCallbacks: AuthCallbacks,
    loadMembership: () -> Membership?,
    saveMembership: (Membership) -> Unit,
    forgetMembership: () -> Unit,
    createFamily: (Role, (Membership) -> Unit, (String) -> Unit) -> Unit,
    joinFamily: (String, Role, (Membership) -> Unit, (String) -> Unit) -> Unit,
    hasMicPermission: () -> Boolean,
    requestMicPermission: ((Boolean) -> Unit) -> Unit,
    connectionManager: State<AgoraConnectionManager?>,
    onActivateConnection: (Role) -> Unit,
    onStopConnection: (Role) -> Unit
) {
    val context = LocalContext.current
    var authenticated by remember { mutableStateOf(isAuthenticated()) }
    var membership by remember { mutableStateOf(loadMembership()) }
    var connected by remember { mutableStateOf(false) }
    var sessionActive by remember { mutableStateOf(true) }
    var statusMessage by remember { mutableStateOf("") }
    var retryCount by remember { mutableStateOf(0) }
    val languageTransition = remember { LanguageTransition() }

    // Al entrar (arranque normal o recién recreada la Activity por un cambio de idioma),
    // el contenido se desliza y desvanece hacia adentro — la misma sensación que
    // AuthFlow usa entre sus pantallas, para que el cambio de idioma no sea un corte seco.
    LaunchedEffect(Unit) {
        languageTransition.alpha.snapTo(0f)
        languageTransition.offsetX.snapTo(60f)
        coroutineScope {
            launch { languageTransition.alpha.animateTo(1f, tween(260)) }
            launch { languageTransition.offsetX.animateTo(0f, tween(260)) }
        }
    }

    MaterialTheme(colorScheme = colorsForRole(membership?.role)) {
      CompositionLocalProvider(LocalLanguageTransition provides languageTransition) {
        Box(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize().graphicsLayer {
            alpha = languageTransition.alpha.value
            translationX = languageTransition.offsetX.value
        }) {
            // Antes esto era un corte seco (varios "return@Surface" intercambiando contenido
            // sin animar). Ahora el paso de login a home (y viceversa, al cerrar sesión) usa
            // el mismo deslizar+desvanecer que el resto de transiciones de la app.
            AnimatedContent(
                targetState = authenticated,
                transitionSpec = {
                    (slideInHorizontally(initialOffsetX = { it / 3 }) + fadeIn()) togetherWith
                        (slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut())
                },
                label = "auth-home-transition"
            ) { isAuthenticated ->
                if (!isAuthenticated) {
                    AuthFlow(
                        callbacks = authCallbacks,
                        onAuthenticated = { authenticated = true }
                    )
                } else {
                    val currentMembership = membership

                    if (currentMembership == null) {
                        FamilySetupScreen(
                            onCreateFamily = createFamily,
                            onJoinFamily = joinFamily,
                            onRegistered = { newMembership ->
                                saveMembership(newMembership)
                                membership = newMembership
                            },
                            onSignOut = {
                                authCallbacks.signOut()
                                RememberedEmailStore.clear(context)
                                authenticated = false
                            }
                        )
                    } else if (!sessionActive) {
                        IdleScreen(
                            role = currentMembership.role,
                            onConnect = {
                                onActivateConnection(currentMembership.role)
                                sessionActive = true
                                retryCount++
                            },
                            onForgetFamily = {
                                onStopConnection(currentMembership.role)
                                forgetMembership()
                                membership = null
                                sessionActive = true
                                connected = false
                            },
                            onSignOut = {
                                onStopConnection(currentMembership.role)
                                authCallbacks.signOut()
                                RememberedEmailStore.clear(context)
                                forgetMembership()
                                membership = null
                                sessionActive = true
                                connected = false
                                authenticated = false
                            }
                        )
                    } else {
                        val manager = connectionManager.value
                        val connectingLabel = stringResource(R.string.status_connecting)
                        val micPermissionError = stringResource(R.string.error_mic_permission_required)

                        if (!connected) {
                            LaunchedEffect(retryCount, manager) {
                                if (manager == null) return@LaunchedEffect
                                statusMessage = connectingLabel
                                if (hasMicPermission()) {
                                    manager.connect(currentMembership.familyId, {
                                        connected = true
                                        statusMessage = ""
                                        if (currentMembership.role == Role.ABUELA) manager.forceMaxVolume()
                                    }, { error -> statusMessage = error })
                                } else {
                                    requestMicPermission { granted ->
                                        if (granted) {
                                            manager.connect(currentMembership.familyId, {
                                                connected = true
                                                statusMessage = ""
                                                if (currentMembership.role == Role.ABUELA) manager.forceMaxVolume()
                                            }, { error -> statusMessage = error })
                                        } else {
                                            statusMessage = micPermissionError
                                        }
                                    }
                                }
                            }
                            ConnectingScreen(
                                role = currentMembership.role,
                                statusMessage = statusMessage,
                                onRetry = { retryCount++ }
                            )
                        } else {
                            PushToTalkScreen(
                                role = currentMembership.role,
                                onMicMuted = { muted -> manager?.setMicMuted(muted) },
                                isReconnecting = manager?.reconnecting?.value ?: false,
                                onExit = {
                                    onStopConnection(currentMembership.role)
                                    connected = false
                                    sessionActive = false
                                }
                            )
                        }
                    }
                }
            }
        }
        if (languageTransition.showLoader) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        }
      }
    }
}

private enum class SetupStep { CHOOSE, CREATE_ROLE, SHOW_CODE, JOIN_CODE, JOIN_ROLE }

@Composable
private fun FamilySetupScreen(
    onCreateFamily: (Role, (Membership) -> Unit, (String) -> Unit) -> Unit,
    onJoinFamily: (String, Role, (Membership) -> Unit, (String) -> Unit) -> Unit,
    onRegistered: (Membership) -> Unit,
    onSignOut: () -> Unit
) {
    var step by remember { mutableStateOf(SetupStep.CHOOSE) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var pendingMembership by remember { mutableStateOf<Membership?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step) {
            SetupStep.CHOOSE -> {
                Text("📻", fontSize = 56.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.setup_welcome_title),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(40.dp))
                Button(
                    onClick = { errorMessage = ""; step = SetupStep.CREATE_ROLE },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) { Text(stringResource(R.string.setup_create_family_button), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { errorMessage = ""; step = SetupStep.JOIN_CODE },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) { Text(stringResource(R.string.setup_join_family_button), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }

            SetupStep.CREATE_ROLE -> {
                Text(stringResource(R.string.setup_choose_role_title), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))
                RoleCard(
                    emoji = "👵",
                    title = stringResource(R.string.role_elder_title),
                    subtitle = stringResource(R.string.role_elder_subtitle),
                    containerColor = AbuelaColors.primary,
                    enabled = !loading,
                    onClick = {
                        loading = true; errorMessage = ""
                        onCreateFamily(Role.ABUELA, { m -> loading = false; pendingMembership = m; step = SetupStep.SHOW_CODE }, { e -> loading = false; errorMessage = e })
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                RoleCard(
                    emoji = "🧑‍💼",
                    title = stringResource(R.string.role_caregiver_title),
                    subtitle = stringResource(R.string.role_caregiver_subtitle),
                    containerColor = CuidadorColors.primary,
                    enabled = !loading,
                    onClick = {
                        loading = true; errorMessage = ""
                        onCreateFamily(Role.CUIDADOR, { m -> loading = false; pendingMembership = m; step = SetupStep.SHOW_CODE }, { e -> loading = false; errorMessage = e })
                    }
                )
                SetupStatus(loading, errorMessage)
            }

            SetupStep.SHOW_CODE -> {
                val membership = pendingMembership
                Text("✅", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.family_created_title), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.family_created_subtitle),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                InviteCodeBadge(membership?.inviteCode ?: "")
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { membership?.let(onRegistered) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text(stringResource(R.string.action_continue), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }

            SetupStep.JOIN_CODE -> {
                Text(stringResource(R.string.join_code_prompt), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase().take(6) },
                    label = { Text(stringResource(R.string.invite_code_label)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { step = SetupStep.JOIN_ROLE },
                    enabled = codeInput.length >= 4,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text(stringResource(R.string.action_next), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }

            SetupStep.JOIN_ROLE -> {
                Text(stringResource(R.string.setup_choose_role_title), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(32.dp))
                RoleCard(
                    emoji = "👵",
                    title = stringResource(R.string.role_elder_title),
                    subtitle = stringResource(R.string.role_elder_subtitle),
                    containerColor = AbuelaColors.primary,
                    enabled = !loading,
                    onClick = {
                        loading = true; errorMessage = ""
                        onJoinFamily(codeInput, Role.ABUELA, { m -> loading = false; onRegistered(m) }, { e -> loading = false; errorMessage = e })
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                RoleCard(
                    emoji = "🧑‍💼",
                    title = stringResource(R.string.role_caregiver_title),
                    subtitle = stringResource(R.string.role_caregiver_subtitle),
                    containerColor = CuidadorColors.primary,
                    enabled = !loading,
                    onClick = {
                        loading = true; errorMessage = ""
                        onJoinFamily(codeInput, Role.CUIDADOR, { m -> loading = false; onRegistered(m) }, { e -> loading = false; errorMessage = e })
                    }
                )
                SetupStatus(loading, errorMessage)
            }
        }
    }
    // Regresar va siempre fijo arriba a la izquierda con el mismo padding que el resto de
    // la app (ver CenteredScreenWithBack en AuthScreens.kt) — nunca como parte del
    // contenido centrado, para que no flote en un lugar distinto según cuánto contenido
    // tenga cada paso.
    if (step == SetupStep.CREATE_ROLE || step == SetupStep.JOIN_CODE || step == SetupStep.JOIN_ROLE) {
        Row(modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 16.dp, vertical = 12.dp)) {
            BackButton(onBack = {
                errorMessage = ""
                step = if (step == SetupStep.JOIN_ROLE) SetupStep.JOIN_CODE else SetupStep.CHOOSE
            })
        }
    }
    // Provisorio: "home" mientras no hay una pantalla de ajustes — Cerrar sesión solo
    // aparece en el primer paso (CHOOSE), no en medio de crear/unirse a una familia.
    if (step == SetupStep.CHOOSE) {
        TextButton(
            onClick = onSignOut,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.action_sign_out), color = MaterialTheme.colorScheme.error)
        }
    }
    }
}

@Composable
fun SetupStatus(loading: Boolean, errorMessage: String) {
    if (loading) {
        Spacer(modifier = Modifier.height(24.dp))
        CircularProgressIndicator()
    }
    if (errorMessage.isNotBlank()) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(errorMessage, fontSize = 15.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

@Composable
private fun InviteCodeBadge(code: String) {
    val context = LocalContext.current
    val clipLabel = stringResource(R.string.clip_label_family_code)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            Text(
                code,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, code))
            }) { Text(stringResource(R.string.action_copy_code)) }
        }
    }
}

@Composable
private fun IdleScreen(
    role: Role,
    onConnect: () -> Unit,
    onForgetFamily: () -> Unit,
    onSignOut: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("📻", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.status_disconnected), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (role == Role.ABUELA) stringResource(R.string.role_label_elder) else stringResource(R.string.role_label_caregiver),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) { Text(stringResource(R.string.action_connect), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onForgetFamily) { Text(stringResource(R.string.action_change_family)) }
        }
        // Provisorio: por ahora el único lugar para cerrar sesión es acá abajo, en la
        // pantalla de reposo. Limpia todo (Firebase + membresía + correo recordado).
        TextButton(
            onClick = onSignOut,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.action_sign_out), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun LanguageToggle() {
    val context = LocalContext.current
    val transition = LocalLanguageTransition.current
    val scope = rememberCoroutineScope()
    // Se lee una sola vez por composición: alcanza para decidir si el idioma tocado ya es
    // el actual, y evitar así un recreate (y toda la animación) totalmente innecesarios.
    val currentLanguage = remember { context.resources.configuration.locales[0].language }
    val changeLanguage: (String) -> Unit = { tag ->
        if (tag != currentLanguage) {
            if (transition != null) {
                transition.showLoader = true
                scope.launch {
                    coroutineScope {
                        launch { transition.alpha.animateTo(0f, tween(200)) }
                        launch { transition.offsetX.animateTo(-60f, tween(200)) }
                    }
                    setAppLanguage(context, tag)
                }
            } else {
                setAppLanguage(context, tag)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { changeLanguage("es") }) {
            Text(stringResource(R.string.language_spanish), fontSize = 13.sp)
        }
        TextButton(onClick = { changeLanguage("en") }) {
            Text(stringResource(R.string.language_english), fontSize = 13.sp)
        }
    }
}

// AppCompatDelegate.setApplicationLocales() depende de que la Activity extienda
// AppCompatActivity para recrearse sola; como esta app usa ComponentActivity,
// en Android 13+ llamamos directo al LocaleManager del sistema (no requiere eso).
private fun setAppLanguage(context: Context, languageTag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
            android.os.LocaleList.forLanguageTags(languageTag)
    } else {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }
}

@Composable
private fun RoleCard(
    emoji: String,
    title: String,
    subtitle: String,
    containerColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            Text(emoji, fontSize = 34.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ConnectingScreen(
    role: Role,
    statusMessage: String,
    onRetry: () -> Unit
) {
    val connectingLabel = stringResource(R.string.status_connecting)
    val isError = statusMessage.isNotBlank() && statusMessage != connectingLabel
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isError) {
            Text("⚠️", fontSize = 48.sp)
        } else {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 5.dp,
                modifier = Modifier.size(56.dp)
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            if (isError) statusMessage else stringResource(R.string.status_connecting_radio),
            fontSize = if (role == Role.ABUELA) 24.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (isError) {
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) { Text(stringResource(R.string.action_retry), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PushToTalkScreen(
    role: Role,
    onMicMuted: (Boolean) -> Unit,
    isReconnecting: Boolean,
    onExit: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isAbuela = role == Role.ABUELA

    LaunchedEffect(isPressed) {
        onMicMuted(!isPressed)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isReconnecting) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            ) {
                Text(
                    stringResource(R.string.status_reconnecting),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
        Text(
            if (isAbuela) stringResource(R.string.ptt_hint_elder) else stringResource(R.string.ptt_hint_caregiver),
            fontSize = if (isAbuela) 26.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(48.dp))
        val buttonSize = if (isAbuela) 240.dp else 200.dp
        Button(
            onClick = {},
            interactionSource = interactionSource,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPressed) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.primary
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
            modifier = Modifier.size(buttonSize)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isPressed) "🔊" else "🎙️", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (isPressed) stringResource(R.string.ptt_talking) else stringResource(R.string.ptt_hold_hint),
                    fontSize = if (isAbuela) 22.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(modifier = Modifier.height(48.dp))
        TextButton(onClick = onExit) {
            Text(stringResource(R.string.action_exit), fontSize = if (isAbuela) 16.sp else 14.sp)
        }
    }
}
