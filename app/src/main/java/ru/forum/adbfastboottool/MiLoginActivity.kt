package ru.forum.adbfastboottool

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран входа в Mi-аккаунт через официальную веб-страницу Xiaomi.
 *
 * Логика основана на проекте MiTools (offici5l, Apache 2.0): открываем
 * официальную страницу serviceLogin Xiaomi в WebView, после успешного входа
 * забираем куки (passToken, deviceId, userId) и возвращаем их вызывающему.
 *
 * Никаких сторонних серверов — только account.xiaomi.com. Это обычный вход
 * пользователя в свой собственный Mi-аккаунт.
 */
class MiLoginActivity : AppCompatActivity() {

    private val initialUrl =
        "https://account.xiaomi.com/pass/serviceLogin?sid=unlockApi&checkSafeAddress=true"
    private val endPattern = "{\"R\":\"\",\"S\":\"OK\"}"
    private var monitoringEnded = false
    private var passToken: String? = null
    private var deviceId: String? = null
    private var userId: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var webView: WebView
    private lateinit var continueButton: Button
    private lateinit var logoutButton: Button
    private lateinit var loginMessage: TextView
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Программный layout (без отдельного XML): прогресс, сообщение, кнопки, WebView.
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0B0B0D"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        loginMessage = TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#F5F5F7"))
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(dp(6), dp(6), dp(6), dp(10))
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        continueButton = Button(this).apply {
            text = getString(R.string.mi_login_continue)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { returnResults() }
        }
        logoutButton = Button(this).apply {
            text = getString(R.string.mi_login_logout)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { doLogout() }
        }
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(loginMessage)
        root.addView(progressBar)
        root.addView(continueButton)
        root.addView(logoutButton)
        root.addView(webView)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this) { finish() }

        if (checkExistingCookies()) {
            webView.visibility = View.GONE
            progressBar.visibility = View.GONE
            continueButton.visibility = View.VISIBLE
            logoutButton.visibility = View.VISIBLE
            loginMessage.visibility = View.VISIBLE
            loginMessage.text = getString(R.string.mi_login_already, userId ?: "")
        } else {
            setupWebView()
        }
    }

    private fun checkExistingCookies(): Boolean {
        val cookieString = CookieManager.getInstance().getCookie("https://account.xiaomi.com")
            ?: return false
        passToken = extractCookie(cookieString, "passToken")
        deviceId = extractCookie(cookieString, "deviceId")
        userId = extractCookie(cookieString, "userId")
        return !userId.isNullOrEmpty() && !passToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()
    }

    private fun extractCookie(cookieString: String, key: String): String? =
        cookieString.split(";").map { it.trim() }
            .find { it.startsWith("$key=") }?.split("=")?.getOrNull(1)

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "(Android) Mobile"
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                handler.removeCallbacksAndMessages(null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!monitoringEnded) {
                    progressBar.visibility = View.GONE
                    extractCookies()
                    checkForEndSignal(view)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                progressBar.visibility = View.GONE
                monitoringEnded = true
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
        CookieManager.getInstance().run {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.loadUrl(initialUrl)
    }

    private fun extractCookies() {
        CookieManager.getInstance().getCookie("https://account.xiaomi.com")?.let { cookieString ->
            passToken = passToken ?: extractCookie(cookieString, "passToken")
            deviceId = deviceId ?: extractCookie(cookieString, "deviceId")
            userId = userId ?: extractCookie(cookieString, "userId")
        }
    }

    private fun checkForEndSignal(view: WebView) {
        view.evaluateJavascript("document.documentElement.outerHTML") { html ->
            val cleaned = html.replace("\\u003C", "<").replace("\\u003E", ">")
                .replace("\\\"", "\"").replace("\\\\", "\\")
            if (cleaned.contains(endPattern)) {
                if (passToken.isNullOrEmpty() || deviceId.isNullOrEmpty() || userId.isNullOrEmpty()) {
                    view.loadUrl(initialUrl)
                } else {
                    monitoringEnded = true
                    loginMessage.text = getString(R.string.mi_login_success, userId ?: "")
                    loginMessage.visibility = View.VISIBLE
                    handler.postDelayed({ returnResults() }, 1500)
                }
            }
        }
    }

    private fun doLogout() {
        CookieManager.getInstance().removeAllCookies {
            passToken = null; deviceId = null; userId = null; monitoringEnded = false
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        CookieManager.getInstance().flush()
    }

    private fun returnResults() {
        val ok = !passToken.isNullOrEmpty() && !deviceId.isNullOrEmpty() && !userId.isNullOrEmpty()
        if (ok) {
            setResult(Activity.RESULT_OK, Intent().apply {
                putExtra("passToken", passToken)
                putExtra("deviceId", deviceId)
                putExtra("userId", userId)
            })
            finish()
        } else {
            webView.loadUrl(initialUrl)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
