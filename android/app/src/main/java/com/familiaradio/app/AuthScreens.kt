package com.familiaradio.app

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Callbacks que necesita todo el flujo de autenticación. Se agrupan en una
 * sola clase porque son muchos y así el llamador (MainActivity) los arma
 * una sola vez, y cada pantalla recibe solo lo que necesita de acá.
 */
class AuthCallbacks(
    val signInWithEmail: (String, String, () -> Unit, (String) -> Unit) -> Unit,
    val signInWithGoogle: (() -> Unit, (String) -> Unit) -> Unit,
    val sendPhoneCode: (String, () -> Unit, () -> Unit, (String) -> Unit) -> Unit,
    val confirmPhoneCode: (String, () -> Unit, (String) -> Unit) -> Unit,
    val completeRegistration: (String, String, () -> Unit, (String) -> Unit) -> Unit,
    val saveProfile: (String, String, String, String, () -> Unit, (String) -> Unit) -> Unit,
    val signInWithPendingPhoneCredential: (() -> Unit, (String) -> Unit) -> Unit,
    val setNewPassword: (String, () -> Unit, (String) -> Unit) -> Unit,
    val signOut: () -> Unit,
    val fetchRecoveryOptions: (String, (phone: String?, phoneMasked: String?, emailMasked: String) -> Unit, (String) -> Unit) -> Unit,
    val sendEmailOtp: (String, () -> Unit, (String) -> Unit) -> Unit,
    val verifyEmailOtp: (String, String, () -> Unit, (String) -> Unit) -> Unit,
    val resetPasswordViaEmail: (String, String, String, () -> Unit, (String) -> Unit) -> Unit
)

// Con "Recordar mis datos" tildado, guardamos el correo para que la próxima vez el
// login ya lo tenga escrito — así "¿Olvidaste tu contraseña?" puede ir directo a
// elegir el método de recuperación en vez de pedir el correo primero (como el mockup).
object RememberedEmailStore {
    private const val PREFS = "familia_radio_prefs"
    private const val KEY_EMAIL = "remembered_email"

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EMAIL, null)

    fun save(context: Context, email: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EMAIL, email).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_EMAIL).apply()
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Text("←", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.action_back), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Campo de contraseña con icono para mostrar/ocultar el texto ingresado. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = isError,
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "🙈" else "👁️", fontSize = 16.sp)
            }
        },
        modifier = modifier
    )
}

/** Checkbox + texto donde una parte (linkText) es clickeable para abrir el detalle. */
@Composable
private fun AcceptRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    prefixText: String,
    linkText: String,
    onLinkClick: () -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(prefixText, linkText, linkColor) {
        buildAnnotatedString {
            append(prefixText)
            pushStringAnnotation(tag = "link", annotation = "link")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, color = linkColor)) {
                append(linkText)
            }
            pop()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        ClickableText(
            text = annotated,
            style = LocalTextStyle.current.copy(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
            onClick = { offset ->
                annotated.getStringAnnotations(tag = "link", start = offset, end = offset)
                    .firstOrNull()?.let { onLinkClick() }
            }
        )
    }
}

/**
 * Pantallas de recuperación de contraseña centran su contenido verticalmente,
 * pero el botón "Regresar" debe quedar fijo arriba (si va dentro de esa misma
 * Column centrada, termina flotando en el medio de la pantalla junto al resto).
 */
@Composable
private fun CenteredScreenWithBack(
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
        Row(modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 16.dp, vertical = 12.dp)) {
            BackButton(onBack = onBack)
        }
    }
}

private enum class AuthStep {
    LOGIN,
    REGISTER_FORM, REGISTER_OTP, REGISTER_SUCCESS,
    FORGOT_IDENTIFY, FORGOT_CHOOSE_METHOD,
    FORGOT_PHONE_OTP, FORGOT_EMAIL_OTP,
    FORGOT_NEW_PASSWORD, FORGOT_SUCCESS
}

private enum class RecoveryMethod { PHONE, EMAIL }

@Composable
fun AuthFlow(callbacks: AuthCallbacks, onAuthenticated: () -> Unit) {
    var step by remember { mutableStateOf(AuthStep.LOGIN) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Datos que se van juntando a lo largo del registro.
    var pendingPhone by remember { mutableStateOf("") }
    var pendingNombres by remember { mutableStateOf("") }
    var pendingApellidos by remember { mutableStateOf("") }
    var pendingDay by remember { mutableStateOf(1) }
    var pendingMonth by remember { mutableStateOf(0) }
    var pendingYear by remember { mutableStateOf("") }
    var pendingEmail by remember { mutableStateOf("") }
    var pendingPassword by remember { mutableStateOf("") }

    // Datos de la recuperación de contraseña.
    var recoveryEmail by remember { mutableStateOf("") }
    var recoveryMethod by remember { mutableStateOf(RecoveryMethod.PHONE) }
    var recoveryPhone by remember { mutableStateOf("") }
    var recoveryPhoneMasked by remember { mutableStateOf("") }
    var recoveryEmailMasked by remember { mutableStateOf("") }
    var recoveryEmailCode by remember { mutableStateOf("") }

    fun finishRegistration() {
        loading = true; errorMessage = ""
        callbacks.completeRegistration(pendingEmail, pendingPassword, {
            val fechaIso = "%04d-%02d-%02d".format(
                pendingYear.toIntOrNull() ?: 1900, pendingMonth + 1, pendingDay
            )
            callbacks.saveProfile(pendingNombres, pendingApellidos, fechaIso, pendingEmail, {
                loading = false
                step = AuthStep.REGISTER_SUCCESS
            }, { e -> loading = false; errorMessage = e })
        }, { e -> loading = false; errorMessage = e })
    }

    fun finishPhoneRecoverySignIn() {
        loading = true; errorMessage = ""
        callbacks.signInWithPendingPhoneCredential({
            loading = false
            step = AuthStep.FORGOT_NEW_PASSWORD
        }, { e -> loading = false; errorMessage = e })
    }

    // El mockup no tiene una pantalla separada para "ingresa tu correo": desde el login
    // se va directo a elegir el método (SMS/correo) usando el correo que el usuario ya
    // escribió ahí. Solo si ese campo está vacío mostramos IdentifyEmailScreen como respaldo.
    fun startRecovery(email: String) {
        loading = true; errorMessage = ""
        callbacks.fetchRecoveryOptions(email, { phone, phoneMasked, emailMasked ->
            loading = false
            recoveryEmail = email
            recoveryPhone = phone ?: ""
            recoveryPhoneMasked = phoneMasked ?: ""
            recoveryEmailMasked = emailMasked
            step = AuthStep.FORGOT_CHOOSE_METHOD
        }, { e -> loading = false; errorMessage = e })
    }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            (slideInHorizontally(initialOffsetX = { it / 3 }) + fadeIn()) togetherWith
                (slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut())
        },
        label = "auth-flow"
    ) { currentStep ->
        when (currentStep) {
            AuthStep.LOGIN -> LoginScreen(
                loading = loading,
                errorMessage = errorMessage,
                onLogin = { email, password ->
                    loading = true; errorMessage = ""
                    callbacks.signInWithEmail(email, password, { loading = false; onAuthenticated() }, { e -> loading = false; errorMessage = e })
                },
                onGoogleLogin = {
                    loading = true; errorMessage = ""
                    callbacks.signInWithGoogle({ loading = false; onAuthenticated() }, { e -> loading = false; errorMessage = e })
                },
                onRegister = { errorMessage = ""; step = AuthStep.REGISTER_FORM },
                onForgotPassword = { typedEmail ->
                    errorMessage = ""
                    if (typedEmail.isBlank()) step = AuthStep.FORGOT_IDENTIFY else startRecovery(typedEmail)
                }
            )

            AuthStep.REGISTER_FORM -> RegisterFormScreen(
                loading = loading,
                errorMessage = errorMessage,
                onBack = { errorMessage = ""; step = AuthStep.LOGIN },
                onSubmit = { nombres, apellidos, day, month, year, phone, email, password ->
                    pendingNombres = nombres; pendingApellidos = apellidos
                    pendingDay = day; pendingMonth = month; pendingYear = year
                    pendingPhone = phone; pendingEmail = email; pendingPassword = password
                    loading = true; errorMessage = ""
                    callbacks.sendPhoneCode(
                        phone,
                        { loading = false; step = AuthStep.REGISTER_OTP },
                        { loading = false; finishRegistration() },
                        { e -> loading = false; errorMessage = e }
                    )
                }
            )

            AuthStep.REGISTER_OTP -> OtpEntryScreen(
                title = stringResource(R.string.otp_title),
                subtitle = stringResource(R.string.otp_subtitle, pendingPhone),
                loading = loading,
                errorMessage = errorMessage,
                onBack = { errorMessage = ""; step = AuthStep.REGISTER_FORM },
                onConfirm = { code ->
                    loading = true; errorMessage = ""
                    callbacks.confirmPhoneCode(code, { loading = false; finishRegistration() }, { e -> loading = false; errorMessage = e })
                },
                onResend = { onDone ->
                    callbacks.sendPhoneCode(pendingPhone, { onDone() }, { onDone() }, { onDone() })
                }
            )

            AuthStep.REGISTER_SUCCESS -> SuccessScreen(
                message = stringResource(R.string.register_success_message),
                onTimeout = onAuthenticated
            )

            AuthStep.FORGOT_IDENTIFY -> IdentifyEmailScreen(
                loading = loading,
                errorMessage = errorMessage,
                onBack = { errorMessage = ""; step = AuthStep.LOGIN },
                onNext = { email -> startRecovery(email) }
            )

            AuthStep.FORGOT_CHOOSE_METHOD -> ChooseRecoveryMethodScreen(
                loading = loading,
                errorMessage = errorMessage,
                phoneMasked = recoveryPhoneMasked,
                emailMasked = recoveryEmailMasked,
                onBack = { errorMessage = ""; step = AuthStep.LOGIN },
                onChooseSms = {
                    recoveryMethod = RecoveryMethod.PHONE
                    loading = true; errorMessage = ""
                    callbacks.sendPhoneCode(
                        recoveryPhone,
                        { loading = false; step = AuthStep.FORGOT_PHONE_OTP },
                        { finishPhoneRecoverySignIn() },
                        { e -> loading = false; errorMessage = e }
                    )
                },
                onChooseEmail = {
                    recoveryMethod = RecoveryMethod.EMAIL
                    loading = true; errorMessage = ""
                    callbacks.sendEmailOtp(recoveryEmail, {
                        loading = false
                        step = AuthStep.FORGOT_EMAIL_OTP
                    }, { e -> loading = false; errorMessage = e })
                }
            )

            AuthStep.FORGOT_PHONE_OTP -> OtpEntryScreen(
                title = stringResource(R.string.otp_title_recovery),
                subtitle = stringResource(R.string.otp_subtitle, recoveryPhoneMasked),
                loading = loading,
                errorMessage = errorMessage,
                onBack = { errorMessage = ""; step = AuthStep.FORGOT_CHOOSE_METHOD },
                onConfirm = { code ->
                    loading = true; errorMessage = ""
                    callbacks.confirmPhoneCode(code, { finishPhoneRecoverySignIn() }, { e -> loading = false; errorMessage = e })
                },
                onResend = { onDone ->
                    callbacks.sendPhoneCode(recoveryPhone, { onDone() }, { onDone() }, { onDone() })
                }
            )

            AuthStep.FORGOT_EMAIL_OTP -> OtpEntryScreen(
                title = stringResource(R.string.otp_title_recovery),
                subtitle = stringResource(R.string.otp_subtitle_recovery, recoveryEmailMasked),
                loading = loading,
                errorMessage = errorMessage,
                onBack = { errorMessage = ""; step = AuthStep.FORGOT_CHOOSE_METHOD },
                onConfirm = { code ->
                    loading = true; errorMessage = ""
                    callbacks.verifyEmailOtp(recoveryEmail, code, {
                        loading = false
                        recoveryEmailCode = code
                        step = AuthStep.FORGOT_NEW_PASSWORD
                    }, { e -> loading = false; errorMessage = e })
                },
                onResend = { onDone ->
                    callbacks.sendEmailOtp(recoveryEmail, { onDone() }, { onDone() })
                }
            )

            AuthStep.FORGOT_NEW_PASSWORD -> NewPasswordScreen(
                loading = loading,
                errorMessage = errorMessage,
                onBack = { errorMessage = ""; step = AuthStep.LOGIN },
                onSubmit = { newPassword ->
                    loading = true; errorMessage = ""
                    if (recoveryMethod == RecoveryMethod.PHONE) {
                        callbacks.setNewPassword(newPassword, {
                            loading = false
                            step = AuthStep.FORGOT_SUCCESS
                        }, { e -> loading = false; errorMessage = e })
                    } else {
                        callbacks.resetPasswordViaEmail(recoveryEmail, recoveryEmailCode, newPassword, {
                            loading = false
                            step = AuthStep.FORGOT_SUCCESS
                        }, { e -> loading = false; errorMessage = e })
                    }
                }
            )

            AuthStep.FORGOT_SUCCESS -> SuccessScreen(
                message = stringResource(R.string.password_change_success),
                onTimeout = {
                    callbacks.signOut()
                    step = AuthStep.LOGIN
                }
            )
        }
    }
}

@Composable
private fun LoginScreen(
    loading: Boolean,
    errorMessage: String,
    onLogin: (String, String) -> Unit,
    onGoogleLogin: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: (String) -> Unit
) {
    val context = LocalContext.current
    val remembered = remember { RememberedEmailStore.load(context) }
    var email by remember { mutableStateOf(remembered ?: "") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(remembered != null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LanguageToggle()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.app_name),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.label_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.login_password_label),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
            Text(stringResource(R.string.login_remember_me), fontSize = 13.sp)
        }
        Button(
            onClick = {
                if (rememberMe) RememberedEmailStore.save(context, email.trim()) else RememberedEmailStore.clear(context)
                onLogin(email.trim(), password)
            },
            enabled = !loading && email.isNotBlank() && password.isNotBlank(),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(stringResource(R.string.action_login), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = {
            if (rememberMe && email.isNotBlank()) RememberedEmailStore.save(context, email.trim())
            onForgotPassword(email.trim())
        }) {
            Text(stringResource(R.string.login_forgot_password), fontSize = 13.sp)
        }

        // El loader del botón ya cubre el estado "loading"; acá solo mostramos el error,
        // para que no aparezca un segundo spinner que empuje el resto del contenido hacia abajo.
        SetupStatus(loading = false, errorMessage = errorMessage)

        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.login_or_divider), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onGoogleLogin,
            enabled = !loading,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(stringResource(R.string.login_with_google), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.login_no_account), fontSize = 13.sp)
            TextButton(onClick = onRegister) {
                Text(stringResource(R.string.login_register_here), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun IdentifyEmailScreen(
    loading: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onNext: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    CenteredScreenWithBack(onBack = onBack) {
        Text("🔑", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.forgot_password_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.forgot_password_email_prompt),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.label_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onNext(email.trim()) },
            enabled = !loading && email.trim().isNotBlank(),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(stringResource(R.string.action_next), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        SetupStatus(loading, errorMessage)
    }
}

@Composable
private fun ChooseRecoveryMethodScreen(
    loading: Boolean,
    errorMessage: String,
    phoneMasked: String,
    emailMasked: String,
    onBack: () -> Unit,
    onChooseSms: () -> Unit,
    onChooseEmail: () -> Unit
) {
    // Título/subtítulo van fijos cerca del borde superior, con un espacio fijo (no
    // flexible) antes de las tarjetas para que no queden con un hueco enorme en medio.
    // El loader se dibuja DENTRO de la tarjeta tocada en vez de agregarse debajo, así
    // nada más en la pantalla se mueve mientras se espera la respuesta del servidor.
    var tappedMethod by remember { mutableStateOf<RecoveryMethod?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.forgot_password_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.forgot_password_choose_method),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(40.dp))
            if (phoneMasked.isNotBlank()) {
                RecoveryOptionCard(
                    emoji = "📱",
                    label = stringResource(R.string.recovery_via_sms, phoneMasked),
                    onClick = { tappedMethod = RecoveryMethod.PHONE; onChooseSms() },
                    enabled = !loading,
                    loading = loading && tappedMethod == RecoveryMethod.PHONE
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            RecoveryOptionCard(
                emoji = "✉️",
                label = stringResource(R.string.recovery_via_email, emailMasked),
                onClick = { tappedMethod = RecoveryMethod.EMAIL; onChooseEmail() },
                enabled = !loading,
                loading = loading && tappedMethod == RecoveryMethod.EMAIL
            )
            if (errorMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(errorMessage, fontSize = 15.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 16.dp, vertical = 12.dp)) {
            BackButton(onBack = onBack)
        }
    }
}

@Composable
private fun RecoveryOptionCard(emoji: String, label: String, onClick: () -> Unit, enabled: Boolean, loading: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Text(emoji, fontSize = 24.sp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OtpEntryScreen(
    title: String,
    subtitle: String,
    loading: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit,
    onResend: ((() -> Unit) -> Unit)
) {
    var code by remember { mutableStateOf("") }
    var resendMessage by remember { mutableStateOf("") }
    var resending by remember { mutableStateOf(false) }

    LaunchedEffect(resendMessage) {
        if (resendMessage.isNotBlank()) {
            delay(3000)
            resendMessage = ""
        }
    }

    CenteredScreenWithBack(onBack = onBack) {
        Text("🔐", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            subtitle,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        OtpDigitBoxes(code = code, onCodeChange = { code = it })
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(
            onClick = {
                resending = true
                onResend { resending = false; resendMessage = "resent" }
            },
            enabled = !resending && !loading
        ) {
            Text(stringResource(R.string.action_resend_code), fontSize = 13.sp)
        }
        if (resendMessage.isNotBlank()) {
            Text(
                stringResource(R.string.resend_code_success),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onConfirm(code.trim()) },
            enabled = !loading && code.length == 6,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(stringResource(R.string.action_confirm_code), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        SetupStatus(loading, errorMessage)
    }
}

@Composable
private fun OtpDigitBoxes(code: String, onCodeChange: (String) -> Unit, length: Int = 6) {
    val focusRequesters = remember { List(length) { FocusRequester() } }
    val padded = code.padEnd(length, ' ')
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until length) {
            OutlinedTextField(
                value = if (padded[i] == ' ') "" else padded[i].toString(),
                onValueChange = { newVal ->
                    val digit = newVal.lastOrNull { it.isDigit() }
                    val chars = padded.toCharArray()
                    if (digit != null) {
                        chars[i] = digit
                        onCodeChange(String(chars).trimEnd())
                        if (i < length - 1) focusRequesters[i + 1].requestFocus()
                    } else if (newVal.isEmpty()) {
                        chars[i] = ' '
                        onCodeChange(String(chars).trimEnd())
                        if (i > 0) focusRequesters[i - 1].requestFocus()
                    }
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 22.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.width(48.dp).focusRequester(focusRequesters[i])
            )
        }
    }
}

@Composable
private fun RegisterFormScreen(
    loading: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onSubmit: (
        nombres: String, apellidos: String, day: Int, month: Int, year: String,
        phone: String, email: String, password: String
    ) -> Unit
) {
    var nombres by remember { mutableStateOf("") }
    var apellidos by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(1) }
    var month by remember { mutableStateOf(0) }
    var year by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var acceptedTerms by remember { mutableStateOf(false) }
    var acceptedDataPolicy by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showDataPolicyDialog by remember { mutableStateOf(false) }

    val months = stringArrayResource(R.array.months)
    val allFilled = nombres.isNotBlank() && apellidos.isNotBlank() && year.trim().length == 4 &&
        phoneNumber.trim().length >= 6 && email.isNotBlank() && password.isNotBlank() &&
        acceptedTerms && acceptedDataPolicy

    if (showTermsDialog) {
        AlertDialog(
            onDismissRequest = { showTermsDialog = false },
            title = { Text(stringResource(R.string.terms_dialog_title)) },
            text = { Text(stringResource(R.string.terms_dialog_content), fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { showTermsDialog = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }
    if (showDataPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showDataPolicyDialog = false },
            title = { Text(stringResource(R.string.data_policy_dialog_title)) },
            text = { Text(stringResource(R.string.data_policy_dialog_content), fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { showDataPolicyDialog = false }) { Text(stringResource(R.string.action_close)) } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) { BackButton(onBack = onBack) }
        Text(
            stringResource(R.string.register_form_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = nombres,
            onValueChange = { nombres = it.filter { c -> c.isLetter() || c == ' ' } },
            label = { Text(stringResource(R.string.label_first_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = apellidos,
            onValueChange = { apellidos = it.filter { c -> c.isLetter() || c == ' ' } },
            label = { Text(stringResource(R.string.label_last_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.label_birth_date), fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                label = stringResource(R.string.label_day),
                options = (1..31).map { it.toString() },
                selectedIndex = day - 1,
                onSelected = { day = it + 1 },
                modifier = Modifier.weight(1f)
            )
            Dropdown(
                label = stringResource(R.string.label_month),
                options = months.toList(),
                selectedIndex = month,
                onSelected = { month = it },
                modifier = Modifier.weight(1.5f)
            )
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.label_year)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = "+51",
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(" ") },
                modifier = Modifier.width(72.dp)
            )
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it.filter { c -> c.isDigit() }.take(15) },
                label = { Text(stringResource(R.string.phone_number_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.label_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.label_password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        AcceptRow(
            checked = acceptedTerms,
            onCheckedChange = { acceptedTerms = it },
            prefixText = stringResource(R.string.accept_terms_prefix),
            linkText = stringResource(R.string.accept_terms_link),
            onLinkClick = { showTermsDialog = true }
        )
        AcceptRow(
            checked = acceptedDataPolicy,
            onCheckedChange = { acceptedDataPolicy = it },
            prefixText = stringResource(R.string.accept_data_policy_prefix),
            linkText = stringResource(R.string.accept_data_policy_link),
            onLinkClick = { showDataPolicyDialog = true }
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                onSubmit(
                    nombres.trim(), apellidos.trim(), day, month, year.trim(),
                    "+51${phoneNumber.trim()}", email.trim(), password
                )
            },
            enabled = !loading && allFilled,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp)
                )
            } else {
                Text(stringResource(R.string.action_register), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
        // El loader del botón ya cubre el estado "loading"; acá solo mostramos el error.
        SetupStatus(loading = false, errorMessage = errorMessage)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun NewPasswordScreen(
    loading: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword

    CenteredScreenWithBack(onBack = onBack) {
        Text(
            stringResource(R.string.new_password_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.new_password_hint),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        PasswordField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = stringResource(R.string.label_new_password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        PasswordField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = stringResource(R.string.label_confirm_new_password),
            isError = mismatch,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onSubmit(newPassword) },
            enabled = !loading && newPassword.length >= 6 && newPassword == confirmPassword,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(stringResource(R.string.action_change_password), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        val displayError = if (mismatch) stringResource(R.string.error_passwords_dont_match) else errorMessage
        SetupStatus(loading, displayError)
    }
}

@Composable
private fun SuccessScreen(message: String, onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2500)
        onTimeout()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("✅", fontSize = 56.sp)
        Spacer(modifier = Modifier.height(20.dp))
        Text(message, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(28.dp))
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = options.getOrElse(selectedIndex) { "" },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            label = { Text(label, fontSize = 12.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelected(index); expanded = false }
                )
            }
        }
    }
}
