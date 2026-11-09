package app.opah.tv.ui.views

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.InputFilter
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import app.opah.tv.BuildConfig
import app.opah.tv.OpahApplication
import app.opah.tv.homeassistant.HomeAssistantConnectionPhase
import app.opah.tv.homeassistant.HomeAssistantConnection
import app.opah.tv.homeassistant.HomeAssistantSession
import app.opah.tv.homeassistant.HomeAssistantDevice
import app.opah.tv.homeassistant.HomeAssistantException
import app.opah.tv.homeassistant.HomeAssistantSignIn
import app.opah.tv.homeassistant.homeAssistantBaseUrl
import app.opah.tv.homeassistant.safeNotificationText
import app.opah.tv.homeassistant.android.HomeAssistantForegroundService
import app.opah.tv.notifications.android.TvAlertOverlayPermission
import app.opah.tv.notifications.android.TvAlertOverlaySettingsStore
import app.opah.tv.notifications.android.AndroidNotificationChannels
import app.opah.tv.notifications.android.OpahNotificationSettings
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// The platform can detect missing providers on API 26+. Older TVs use the
// guarded WebView constructor below, without adding an AndroidX WebKit dependency.
@SuppressLint("WebViewApiAvailability")
private fun homeAssistantSignInAvailable(): Boolean =
    Build.VERSION.SDK_INT < 26 || WebView.getCurrentWebViewPackage() != null

/** A connection form shared by the Connections page and the local presentation checks. */
@SuppressLint("SetTextI18n")
internal class NativeHomeAssistantSetup(
    private val activity: ComponentActivity,
    private val dialog: Dialog? = null,
    private val connection: HomeAssistantConnection = (activity.application as OpahApplication).container.homeAssistantConnection,
    private val readSavedSession: () -> HomeAssistantSession? = { (activity.application as OpahApplication).container.homeAssistantSecretStore.read() },
    private val canManage: () -> Boolean = { true },
    private val confirmDisconnect: ((() -> Unit) -> Unit) = { it() },
    private val signInAvailable: Boolean = homeAssistantSignInAvailable(),
    private val changePopupDuration: ((() -> Unit) -> Unit)? = null,
    private val canEnableActions: () -> Boolean = {
        (activity.application as? OpahApplication)?.container?.homeAssistantRichPolicy()?.actionsProtected == true
    },
    private val setupOnly: Boolean = false,
    private val closeWhenDetached: Boolean = true,
    private val manageNotificationPause: (() -> Unit)? = null,
) {
    private var embedded = false
    private var built = false
    private var enableAfterPermission = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(22), activity.dp(16), activity.dp(22), activity.dp(16))
        background = activity.nativeGlassPanelBackground(strong = true)
    }
    private val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val formScroll = object : ScrollView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maximum = (activity.resources.displayMetrics.heightPixels - activity.dp(150)).coerceAtLeast(activity.dp(120))
            super.onMeasure(widthMeasureSpec, if (embedded) heightMeasureSpec else
                MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST))
        }
    }.apply { isFillViewport = false; addView(form) }
    private val address = field("Server address", "https://homeassistant.example", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    private val deviceName = field("TV name", "Opah ${Build.MODEL}".take(64), InputType.TYPE_CLASS_TEXT)
    private val status = label("Connect Home Assistant to send messages to this TV", 14f)
    private val signIn = button("Sign in") { beginSignIn() }
    private val enabled = button("Receive notifications") { enableOrDisable() }
    private val details = button("Show message details: Off") {
        if (!allowed()) return@button
        scope.launch {
            try { connection.setShowDetails(!connection.showDetails()); render() }
            catch (_: Exception) { status.text = "Opah could not save this setting" }
        }
    }
    private val disconnect = button("Disconnect") {
        if (!allowed()) return@button
        confirmDisconnect {
            if (allowed()) scope.launch {
                try { connection.disconnect(); HomeAssistantForegroundService.stop(activity); render() }
                catch (_: Exception) { status.text = "Opah could not disconnect — please try again" }
            }
        }
    }
    private val pictures = button("Show pictures: Off") {
        if (!allowed()) return@button
        scope.launch {
            try { connection.setRichNotifications(images = !connection.showImages()); render() }
            catch (_: Exception) { status.text = "Opah could not save this setting" }
        }
    }
    private val actions = button("Allow automation buttons: Off") {
        if (!allowed()) return@button
        fun save(value: Boolean) { scope.launch {
            if (!allowed() || value && !canEnableActions()) return@launch
            try { connection.setRichNotifications(actions = value); render() }
            catch (_: Exception) { status.text = "Opah could not save this setting" }
        } }
        if (connection.allowActions()) save(false)
        else if (!canEnableActions()) status.text = "Set a PIN and lock Home Assistant actions in Settings > Privacy"
        else android.app.AlertDialog.Builder(activity).setTitle("Allow automation buttons?")
            .setMessage("Buttons can change your home and temporarily take remote control\nEach request needs your Opah PIN authorization")
            .setNegativeButton("Keep off", null).setPositiveButton("Allow buttons") { _, _ -> save(true) }.show()
    }
    private val popups = button("Allow TV pop-ups") {
        if (!allowed()) return@button
        runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri())) }
            .onFailure { status.text = "Open Android settings and allow Opah to appear over other apps" }
    }
    private val notificationPermission = button("Allow notifications") {
        if (!allowed()) return@button
        requestNotifications(enableAfter = false)
    }
    private val popupDuration = button("TV pop-up duration") {
        if (allowed()) changePopupDuration?.invoke { if (!closed) render() }
    }
    private val insecureNotice = label("Your sign-in would travel without encryption\nContinue only on a private network you trust", 13f).apply { visibility = View.GONE }
    private val continueInsecure = button("Continue on this private network") { beginSignIn(allowHttp = true) }.apply { visibility = View.GONE }
    private val cancelInsecure = button("Cancel") { showInsecureConfirmation(false); signIn.requestFocus() }.apply { visibility = View.GONE }
    private var browser: WebView? = null
    private var signInJob: kotlinx.coroutines.Job? = null
    private var attempt: HomeAssistantSignIn? = null
    private var callbackTaken = false
    private var closed = false
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            if (dialog != null) dialog.dismiss() else if (browser != null) endLogin("Sign-in cancelled")
        } else if (event == Lifecycle.Event.ON_START && !closed) {
            connection.loadState()
            render()
        }
    }
    private val permission = activity.activityResultRegistry.register("opah-ha-${UUID.randomUUID()}", ActivityResultContracts.RequestPermission()) { granted ->
        if (allowed()) {
            val enable = enableAfterPermission
            enableAfterPermission = false
            if (granted && enable && !connection.enabled()) enableOrDisable()
            else if (granted) render()
            else status.text = "Allow notifications in Android settings to receive messages"
        }
    }

    fun show() {
        check(dialog != null)
        initialize(embedded = false)
        dialog.setContentView(root)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && browser != null) {
                if (event.action == KeyEvent.ACTION_UP) endLogin("Sign-in cancelled")
                true
            } else false
        }
        dialog.show()
        resize(login = false)
    }

    val view: View get() = root

    fun createContent(): View {
        initialize(embedded = true)
        return root
    }

    private fun initialize(embedded: Boolean) {
        check(!built)
        built = true
        this.embedded = embedded
        if (embedded) {
            root.setBackgroundColor(Color.TRANSPARENT)
            root.setPadding(activity.dp(2), 0, activity.dp(2), activity.dp(2))
        }
        connection.loadState()
        val saved = runCatching { readSavedSession() }.getOrNull()
        if (saved != null) { address.setText(saved.server); deviceName.setText(saved.deviceName) }
        else deviceName.setText("Opah ${Build.MODEL}".take(64))
        if (!embedded) root.addView(label("Home Assistant", 22f))
        root.addView(formScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (embedded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, if (embedded) 1f else 0f))
        form.addView(label("Server address", 13f)); form.addView(address)
        form.addView(label("TV name", 13f)); form.addView(deviceName)
        form.addView(status)
        form.addView(signIn); form.addView(insecureNotice); form.addView(continueInsecure); form.addView(cancelInsecure)
        form.addView(enabled); form.addView(details); form.addView(pictures); form.addView(actions)
        if (!setupOnly) form.addView(label("Pictures and button labels need Show message details\nPictures also follow Opah privacy settings", 12f))
        form.addView(notificationPermission); form.addView(popups); form.addView(popupDuration); form.addView(disconnect)
        if (!setupOnly && manageNotificationPause != null) form.addView(button("Pause all TV notifications") { manageNotificationPause.invoke() })
        if (!setupOnly) form.addView(label("Messages arrive while this TV is connected\nChoose this TV in your Home Assistant automations", 12f))
        if (!embedded) root.addView(button("Done") { dialog?.dismiss() })
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (closeWhenDetached) close()
            }
        })
        activity.lifecycle.addObserver(lifecycleObserver)
        resize(login = false)
        scope.launch { connection.state.collect { if (browser == null) render() } }
        render()
        if (saved == null) address.requestFocus() else enabled.requestFocus()
    }

    fun close() {
        if (closed) return
        closed = true
        disposeBrowser()
        permission.unregister()
        activity.lifecycle.removeObserver(lifecycleObserver)
        scope.cancel()
    }

    fun handleBack(): Boolean {
        if (browser == null) return false
        endLogin("Sign-in cancelled")
        return true
    }

    private fun allowed() = !closed && canManage()
    val signingIn: Boolean get() = browser != null || !connection.hasSession() && connection.state.value.phase == HomeAssistantConnectionPhase.CONNECTING
    fun leaveSetupStep() {
        signInJob?.cancel(); signInJob = null
        if (browser != null) endLogin("Sign-in cancelled")
    }

    private fun render() {
        if (closed) return
        val state = connection.state.value
        val configured = connection.hasSession()
        address.isEnabled = !configured
        deviceName.isEnabled = !configured
        signIn.visibility = if (configured || !signInAvailable || insecureNotice.isVisible) View.GONE else View.VISIBLE
        enabled.visibility = if (configured) View.VISIBLE else View.GONE
        details.visibility = enabled.visibility
        pictures.visibility = enabled.visibility
        actions.visibility = enabled.visibility
        disconnect.visibility = if (configured || state.phase == HomeAssistantConnectionPhase.ERROR) View.VISIBLE else View.GONE
        popups.visibility = if (TvAlertOverlayPermission.isGranted(activity)) View.GONE else View.VISIBLE
        notificationPermission.visibility = if (configured && !notificationsAllowed()) View.VISIBLE else View.GONE
        popupDuration.visibility = if (configured && changePopupDuration != null) View.VISIBLE else View.GONE
        val seconds = TvAlertOverlaySettingsStore(activity).load().displayDurationSeconds
        popupDuration.text = if (seconds == 0) "TV pop-ups: Off" else "TV pop-up duration: $seconds seconds"
        val busy = !configured && state.phase == HomeAssistantConnectionPhase.CONNECTING
        signIn.isEnabled = !busy && allowed()
        enabled.isEnabled = !busy && allowed()
        details.isEnabled = !busy && allowed()
        pictures.isEnabled = !busy && allowed()
        actions.isEnabled = !busy && allowed()
        disconnect.isEnabled = !busy && allowed()
        popups.isEnabled = !busy && allowed()
        popupDuration.isEnabled = !busy && allowed()
        notificationPermission.isEnabled = !busy && allowed()
        if (setupOnly) listOf(enabled, details, pictures, actions, disconnect, popups, popupDuration, notificationPermission).forEach { it.visibility = View.GONE }
        enabled.text = if (state.enabled) "Receive notifications: On" else "Receive notifications: Off"
        details.text = if (connection.showDetails()) "Show message details: On" else "Show message details: Off"
        pictures.text = if (connection.showImages()) "Show pictures: On" else "Show pictures: Off"
        actions.text = if (connection.allowActions()) "Allow automation buttons: On" else "Allow automation buttons: Off"
        status.text = if (!configured && !signInAvailable) "Home Assistant sign-in is unavailable on this TV\nCheck that Android System WebView is enabled in TV settings"
        else state.message ?: when (state.phase) {
            HomeAssistantConnectionPhase.DISCONNECTED -> "Connect Home Assistant to send messages to this TV"
            HomeAssistantConnectionPhase.READY -> if (state.enabled) "Starting notifications" else if (setupOnly) "Signed in — continue to choose notifications" else "Signed in — turn on notifications to receive messages"
            HomeAssistantConnectionPhase.CONNECTING -> "Connecting to Home Assistant"
            HomeAssistantConnectionPhase.CONNECTED -> "Connected — Home Assistant can send messages to this TV"
            HomeAssistantConnectionPhase.RETRYING -> "Reconnecting to Home Assistant"
            HomeAssistantConnectionPhase.SIGN_IN_REQUIRED -> if (setupOnly) "Open Home Assistant in Connections to reconnect" else "Disconnect and sign in again"
            HomeAssistantConnectionPhase.ERROR -> "Home Assistant needs attention"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun beginSignIn(allowHttp: Boolean = false) {
        if (!allowed() || !signInAvailable) return
        val pending = try {
            val name = deviceName.text.toString().safeNotificationText(64).replace('\n', ' ').takeIf(String::isNotBlank)
                ?: run { status.text = "Enter a name for this TV"; return }
            val server = homeAssistantBaseUrl(address.text.toString())
            if (server.scheme == "http" && !allowHttp) {
                showInsecureConfirmation(true)
                continueInsecure.requestFocus()
                return
            }
            HomeAssistantSignIn(server, name, SystemClock.elapsedRealtime())
        } catch (error: HomeAssistantException) { status.text = error.problem.explanation; return }
        showInsecureConfirmation(false)
        attempt = pending
        callbackTaken = false
        formScroll.visibility = View.GONE
        status.text = "Sign in on the Home Assistant page"
        val web = try { WebView(activity).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (pending.isCallback(url)) {
                        if (request.isForMainFrame) finishSignIn(pending, url)
                        return true
                    }
                    if (!pending.allowsLoginNavigation(url)) {
                        if (request.isForMainFrame) endLogin("This sign-in method could not be opened on the TV")
                        return true
                    }
                    return false
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (!pending.isCallback(url)) return null
                    if (request.isForMainFrame) view.post { finishSignIn(pending, url) }
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    if (browser === view) endLogin("Check the Home Assistant HTTPS certificate before signing in")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && browser === view) endLogin("The Home Assistant sign-in page could not be loaded")
                }
            }
        } } catch (_: RuntimeException) {
            endLogin("Home Assistant sign-in could not be opened on this TV")
            return
        }
        browser = web
        root.addView(web, root.childCount - 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        resize(login = true)
        web.loadUrl(pending.authorizeUrl.toString())
        web.requestFocus()
    }

    private fun finishSignIn(pending: HomeAssistantSignIn, url: String) {
        if (!allowed() || callbackTaken || attempt !== pending) return
        callbackTaken = true
        val code = try { pending.takeCode(url, SystemClock.elapsedRealtime()) }
        catch (error: HomeAssistantException) { endLogin(error.problem.explanation); return }
        disposeBrowser()
        formScroll.visibility = View.VISIBLE
        resize(login = false)
        signInJob = scope.launch {
            try {
                connection.connect(pending, code, HomeAssistantDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME), canManage)
                render()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.text = "Home Assistant could not finish connecting" }
        }
    }

    private fun endLogin(message: String) {
        disposeBrowser()
        formScroll.visibility = View.VISIBLE
        resize(login = false)
        render()
        status.text = message
        signIn.requestFocus()
    }

    private fun disposeBrowser() {
        attempt = null
        browser?.let { web ->
            browser = null
            web.stopLoading()
            root.removeView(web)
            web.clearHistory()
            web.clearCache(true)
            web.destroy()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }

    private fun enableOrDisable() {
        if (!allowed()) return
        if (!connection.enabled()) {
            if (!notificationsAllowed()) {
                requestNotifications(enableAfter = true)
                return
            }
            if (!TvAlertOverlayPermission.isGranted(activity) && TvAlertOverlaySettingsStore(activity).load().displayDurationSeconds > 0) {
                status.text = "Allow TV pop-ups first, then turn notifications on"
                popups.requestFocus()
                return
            }
        }
        scope.launch {
            try {
                val next = !connection.enabled()
                if (connection.setEnabled(next)) {
                    if (next) {
                        if (!HomeAssistantForegroundService.start(activity)) {
                            connection.setEnabled(false)
                            connection.deliveryUnavailable()
                        }
                    } else HomeAssistantForegroundService.stop(activity)
                }
                render()
            } catch (_: Exception) { status.text = "Opah could not change notifications" }
        }
    }

    private fun resize(login: Boolean) {
        if (embedded || dialog == null) { root.requestLayout(); return }
        val metrics = activity.resources.displayMetrics
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(if (login) metrics.widthPixels - activity.dp(48) else minOf(activity.dp(650), metrics.widthPixels - activity.dp(48)),
            if (login) metrics.heightPixels - activity.dp(36) else ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return Build.VERSION.SDK_INT < 26 || activity.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(AndroidNotificationChannels.HOME_ASSISTANT_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun requestNotifications(enableAfter: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            enableAfterPermission = enableAfter
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else OpahNotificationSettings.open(activity).guidance?.let { status.text = it }
    }

    private fun showInsecureConfirmation(show: Boolean) {
        insecureNotice.visibility = if (show) View.VISIBLE else View.GONE
        continueInsecure.visibility = insecureNotice.visibility
        cancelInsecure.visibility = insecureNotice.visibility
        render()
        resize(login = false)
    }

    private fun label(value: String, size: Float) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(NativeTheme.palette.text)
        setPadding(0, activity.dp(5), 0, activity.dp(5))
    }
    private fun field(tagValue: String, placeholder: String, type: Int) = EditText(activity).apply {
        tag = "ha:$tagValue"; hint = placeholder; inputType = type; setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(if (tagValue == "Server address") 2_048 else 64))
        setTextColor(NativeTheme.palette.text); setHintTextColor(NativeTheme.palette.secondaryText)
        background = activity.nativeFlatFieldBackground()
        setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(44))
    }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 14f
        setTextColor(NativeTheme.palette.text); background = activity.nativeFlatFocusableBackground()
        setPadding(activity.dp(10), 0, activity.dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(40)).apply { topMargin = activity.dp(4) }
        setOnClickListener { action() }
    }
}
