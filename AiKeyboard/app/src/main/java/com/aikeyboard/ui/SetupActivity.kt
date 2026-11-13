package com.aikeyboard.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.aikeyboard.BuildConfig
import com.aikeyboard.R
import com.aikeyboard.data.ApiClient
import com.aikeyboard.data.AuthResult
import com.aikeyboard.data.PrefsManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

class SetupActivity : AppCompatActivity() {

    private val api by lazy { ApiClient(this) }

    private lateinit var cardSignedOut: View
    private lateinit var cardSignedIn: View
    private lateinit var inputEmail: EditText
    private lateinit var inputPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnSignup: Button
    private lateinit var btnGoogle: Button
    private lateinit var tvAuthStatus: TextView
    private lateinit var tvSignedInEmail: TextView
    private lateinit var tvKbStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        cardSignedOut = findViewById(R.id.card_signed_out)
        cardSignedIn = findViewById(R.id.card_signed_in)
        inputEmail = findViewById(R.id.input_email)
        inputPassword = findViewById(R.id.input_password)
        btnLogin = findViewById(R.id.btn_login)
        btnSignup = findViewById(R.id.btn_signup)
        btnGoogle = findViewById(R.id.btn_google)
        tvAuthStatus = findViewById(R.id.tv_auth_status)
        tvSignedInEmail = findViewById(R.id.tv_signed_in_email)
        tvKbStatus = findViewById(R.id.tv_kb_status)

        btnLogin.setOnClickListener { doAuth(signUp = false) }
        btnSignup.setOnClickListener { doAuth(signUp = true) }
        btnGoogle.setOnClickListener { googleSignIn() }
        findViewById<Button>(R.id.btn_logout).setOnClickListener {
            api.logout(); refreshUi()
        }

        findViewById<Button>(R.id.btn_enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_select_keyboard).setOnClickListener {
            // Android doesn't let apps switch the default keyboard silently; this opens the system picker.
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        val switchVibration = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_vibration)
        switchVibration.isChecked = PrefsManager.isVibrationEnabled(this)
        switchVibration.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setVibrationEnabled(this, isChecked)
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val session = try { PrefsManager.getSession(this) } catch (e: Exception) { null }
        cardSignedOut.visibility = if (session == null) View.VISIBLE else View.GONE
        cardSignedIn.visibility = if (session != null) View.VISIBLE else View.GONE
        tvSignedInEmail.text = session?.email?.ifEmpty { "Signed in" } ?: ""

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            .orEmpty().startsWith("$packageName/")
        tvKbStatus.text = when {
            current -> "✓ AI Keyboard is your active keyboard"
            enabled -> "Enabled. Now set it as your default."
            else -> "Not enabled yet."
        }
    }

    // ── email / password ──

    private fun doAuth(signUp: Boolean) {
        val email = inputEmail.text.toString().trim()
        val pw = inputPassword.text.toString()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { status("Enter a valid email address.", true); return }
        if (pw.length < 6) { status("Password must be at least 6 characters.", true); return }
        setBusy(true)
        lifecycleScope.launch {
            handle(if (signUp) api.signUp(email, pw) else api.login(email, pw))
        }
    }

    private fun handle(r: Result<AuthResult>) {
        setBusy(false)
        r.onSuccess {
            if (it.loggedIn) { inputPassword.setText(""); status("", false); refreshUi() }
            else status(it.message, false)
        }.onFailure { status(it.message ?: "Something went wrong.", true) }
    }

    // ── Google (Credential Manager -> our backend -> Supabase) ──

    private fun googleSignIn() {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            status("Google sign-in isn't configured yet (see README, section 'Google sign-in').", true)
            return
        }
        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = CredentialManager.create(this@SetupActivity).getCredential(this@SetupActivity, request)
                val cred = result.credential
                if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    handle(api.googleLogin(idToken, rawNonce))
                } else {
                    setBusy(false); status("Unexpected credential type.", true)
                }
            } catch (e: GetCredentialException) {
                setBusy(false); status(e.message ?: "Google sign-in cancelled.", true)
            } catch (e: Exception) {
                setBusy(false); status(e.message ?: "Google sign-in failed.", true)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        listOf(btnLogin, btnSignup, btnGoogle).forEach { it.isEnabled = !busy }
    }

    private fun status(msg: String, error: Boolean) {
        tvAuthStatus.text = msg
        tvAuthStatus.setTextColor(Color.parseColor(if (error) "#FF4F6E" else "#00E5A0"))
    }
}
