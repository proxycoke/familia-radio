package com.familiaradio.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
    val signOut: () -> Unit
)

@Composable
fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Text("←", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.action_back), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private enum class AuthStep {
    LOGIN,
    REGISTER_PHONE_ENTRY, REGISTER_FORM, REGISTER_OTP, REGISTER_SUCCESS,
    FORGOT_PHONE_ENTRY, FORGOT_OTP, FORGOT_NEW_PASSWORD, FORGOT_SUCCESS
}

@Composable
fun AuthFlow(callbacks: AuthCallbacks, onAuthenticated: () -> Unit) {
    var step by remember { mutableStateOf(AuthStep.LOGIN) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Datos que se van juntando a lo largo del registro / recuperación de contraseña.
    var pendingPhone by remember { mutableStateOf("") }
    var pendingNombres by remember { mutableStateOf("") }
    var pendingApellidos by remember { mutableStateOf("") }
    var pendingDay by remember { mutableStateOf(1) }
    var pendingMonth by remember { mutableStateOf(0) }
    var pendingYear by remember { mutableStateOf("") }
    var pendingEmail by remember { mutableStateOf("") }
    var pendingPassword by remember { mutableStateOf("") }

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

    fun finishPasswordReset() {
        loading = true; errorMessage = ""
        callbacks.signInWithPendingPhoneCredential({
            loading = false
            step = AuthStep.FORGOT_NEW_PASSWORD
        }, { e -> loading = false; errorMessage = e })
    }

    when (step) {
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
            onPhoneLogin = { errorMessage = ""; step = AuthStep.REGISTER_PHONE_ENTRY },
            onForgotPassword = { errorMessage = ""; step = AuthStep.FORGOT_PHONE_ENTRY }
        )

        AuthStep.REGISTER_PHONE_ENTRY -> PhoneEntryScreen(
            titleRes = R.string.phone_auth_title,
            loading = loading,
            errorMessage = errorMessage,
            onBack = { errorMessage = ""; step = AuthStep.LOGIN },
            onNext = { phone -> pendingPhone = phone; errorMessage = ""; step = AuthStep.REGISTER_FORM }
        )

        AuthStep.REGISTER_FORM -> RegisterFormScreen(
            loading = loading,
            errorMessage = errorMessage,
            onBack = { errorMessage = ""; step = AuthStep.REGISTER_PHONE_ENTRY },
            onSubmit = { nombres, apellidos, day, month, year, email, password ->
                pendingNombres = nombres; pendingApellidos = apellidos
                pendingDay = day; pendingMonth = month; pendingYear = year
                pendingEmail = email; pendingPassword = password
                loading = true; errorMessage = ""
                callbacks.sendPhoneCode(
                    pendingPhone,
                    { loading = false; step = AuthStep.REGISTER_OTP },
                    { loading = false; finishRegistration() },
                    { e -> loading = false; errorMessage = e }
                )
            }
        )

        AuthStep.REGISTER_OTP -> OtpEntryScreen(
            loading = loading,
            errorMessage = errorMessage,
            onBack = { errorMessage = ""; step = AuthStep.REGISTER_FORM },
            onConfirm = { code ->
                loading = true; errorMessage = ""
                callbacks.confirmPhoneCode(code, { loading = false; finishRegistration() }, { e -> loading = false; errorMessage = e })
            }
        )

        AuthStep.REGISTER_SUCCESS -> SuccessScreen(
            message = stringResource(R.string.register_success_message),
            onTimeout = onAuthenticated
        )

        AuthStep.FORGOT_PHONE_ENTRY -> PhoneEntryScreen(
            titleRes = R.string.forgot_password_phone_prompt,
            loading = loading,
            errorMessage = errorMessage,
            onBack = { errorMessage = ""; step = AuthStep.LOGIN },
            onNext = { phone ->
                pendingPhone = phone
                loading = true; errorMessage = ""
                callbacks.sendPhoneCode(
                    phone,
                    { loading = false; step = AuthStep.FORGOT_OTP },
                    { finishPasswordReset() },
                    { e -> loading = false; errorMessage = e }
                )
            }
        )

        AuthStep.FORGOT_OTP -> OtpEntryScreen(
            loading = loading,
            errorMessage = errorMessage,
            onBack = { errorMessage = ""; step = AuthStep.FORGOT_PHONE_ENTRY },
            onConfirm = { code ->
                loading = true; errorMessage = ""
                callbacks.confirmPhoneCode(code, { finishPasswordReset() }, { e -> loading = false; errorMessage = e })
            }
        )

        AuthStep.FORGOT_NEW_PASSWORD -> NewPasswordScreen(
            loading = loading,
            errorMessage = errorMessage,
            onBack = { errorMessage = ""; step = AuthStep.LOGIN },
            onSubmit = { newPassword ->
                loading = true; errorMessage = ""
                callbacks.setNewPassword(newPassword, {
                    loading = false
                    step = AuthStep.FORGOT_SUCCESS
                }, { e -> loading = false; errorMessage = e })
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

@Composable
private fun LoginScreen(
    loading: Boolean,
    errorMessage: String,
    onLogin: (String, String) -> Unit,
    onGoogleLogin: () -> Unit,
    onPhoneLogin: () -> Unit,
    onForgotPassword: () -> Unit
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
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
            value = identifier,
            onValueChange = { identifier = it },
            label = { Text(stringResource(R.string.login_identifier_label)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.login_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
            Text(stringResource(R.string.login_remember_me), fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onForgotPassword) {
                Text(stringResource(R.string.login_forgot_password), fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onLogin(identifier.trim(), password) },
            enabled = !loading && identifier.isNotBlank() && password.isNotBlank(),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(stringResource(R.string.action_login), fontSize = 17.sp, fontWeight = FontWeight.Bold) }

        SetupStatus(loading, errorMessage)

        Spacer(modifier = Modifier.height(24.dp))
        Text(stringResource(R.string.login_or_divider), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onPhoneLogin,
            enabled = !loading,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(stringResource(R.string.login_with_phone), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onGoogleLogin,
            enabled = !loading,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(stringResource(R.string.login_with_google), fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PhoneEntryScreen(
    titleRes: Int,
    loading: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onNext: (String) -> Unit
) {
    var phoneInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            BackButton(onBack = onBack)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("📱", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(titleRes),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = phoneInput,
            onValueChange = { phoneInput = it },
            label = { Text(stringResource(R.string.phone_number_label)) },
            placeholder = { Text("+51 999 999 999") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onNext(phoneInput.trim()) },
            enabled = !loading && phoneInput.trim().length >= 8,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(stringResource(R.string.action_next), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        SetupStatus(loading, errorMessage)
    }
}

@Composable
private fun OtpEntryScreen(
    loading: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var codeInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            BackButton(onBack = onBack)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("🔐", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.otp_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = codeInput,
            onValueChange = { codeInput = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text(stringResource(R.string.otp_code_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onConfirm(codeInput.trim()) },
            enabled = !loading && codeInput.length >= 6,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(stringResource(R.string.action_confirm_code), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        SetupStatus(loading, errorMessage)
    }
}

@Composable
private fun RegisterFormScreen(
    loading: Boolean,
    errorMessage: String,
    onBack: () -> Unit,
    onSubmit: (nombres: String, apellidos: String, day: Int, month: Int, year: String, email: String, password: String) -> Unit
) {
    var nombres by remember { mutableStateOf("") }
    var apellidos by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(1) }
    var month by remember { mutableStateOf(0) }
    var year by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val months = stringArrayResource(R.array.months)
    val allFilled = nombres.isNotBlank() && apellidos.isNotBlank() && year.trim().length == 4 &&
        email.isNotBlank() && password.isNotBlank()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            BackButton(onBack = onBack)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.register_form_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedTextField(
            value = nombres,
            onValueChange = { nombres = it },
            label = { Text(stringResource(R.string.label_first_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = apellidos,
            onValueChange = { apellidos = it },
            label = { Text(stringResource(R.string.label_last_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.label_birth_date), fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
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
                modifier = Modifier.weight(1.4f)
            )
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.label_year)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.label_email)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.label_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { onSubmit(nombres.trim(), apellidos.trim(), day, month, year.trim(), email.trim(), password) },
            enabled = !loading && allFilled,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(stringResource(R.string.action_register), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        SetupStatus(loading, errorMessage)
        Spacer(modifier = Modifier.height(20.dp))
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

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            BackButton(onBack = onBack)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.new_password_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it },
            label = { Text(stringResource(R.string.label_new_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = { Text(stringResource(R.string.label_confirm_new_password)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
