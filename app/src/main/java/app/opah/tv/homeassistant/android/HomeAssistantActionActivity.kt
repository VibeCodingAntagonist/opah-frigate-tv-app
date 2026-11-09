package app.opah.tv.homeassistant.android

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import app.opah.tv.OpahApplication
import app.opah.tv.R
import app.opah.tv.privacy.PinUnlockResult
import app.opah.tv.ui.pinPadDisplay
import app.opah.tv.ui.views.NativeTheme
import app.opah.tv.ui.views.nativeGlassPanelBackground
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** A separate short-lived task returns to the app the viewer was using. */
internal class HomeAssistantActionActivity : ComponentActivity() {
    private val container get() = (application as OpahApplication).container
    private val platform get() = container.homeAssistantNotifications
    private val handler = Handler(Looper.getMainLooper())
    private val entered = StringBuilder()
    private var ticket: String? = null
    private var busy = false
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var pin: TextView
    private lateinit var keypad: GridLayout
    private lateinit var send: Button
    private val recheck = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (!busy && ticket?.let(platform::actionStatus) == HomeAssistantActionResult.UNAVAILABLE) {
                complete(HomeAssistantActionResult.UNAVAILABLE)
            } else {
                if (!busy && ::keypad.isInitialized && ticket?.let(platform::actionStatus) == HomeAssistantActionResult.NEEDS_PIN && keypad.visibility != ViewGroup.VISIBLE) {
                    keypad.visibility = ViewGroup.VISIBLE; pin.visibility = ViewGroup.VISIBLE
                    status.setText(R.string.ha_action_pin); send.isEnabled = entered.length >= 4
                    keypad.getChildAt(0).requestFocus()
                }
                handler.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ticket = HomeAssistantActionIntents.ticket(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
        lifecycleScope.launch {
            container.privacyRepository.initialize()
            val current = ticket?.let(platform::actionStatus) ?: HomeAssistantActionResult.UNAVAILABLE
            if (current == HomeAssistantActionResult.UNAVAILABLE) { complete(current); return@launch }
            val appearance = app.opah.tv.notifications.android.readTvAlertOverlayAppearance(container.settingsRepository.settings)
            val theme = NativeTheme.snapshot(this@HomeAssistantActionActivity, appearance)
            fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
            fun label(resource: Int, size: Float) = TextView(this@HomeAssistantActionActivity).apply {
                setText(resource); textSize = size; setTextColor(theme.palette.text); gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            column = LinearLayout(this@HomeAssistantActionActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
                background = this@HomeAssistantActionActivity.nativeGlassPanelBackground(theme)
            }
            column.addView(label(R.string.ha_action_title, 21f))
            column.addView(label(R.string.ha_action_title, 17f).apply {
                text = getString(R.string.ha_action_selected, platform.actionTitle(ticket.orEmpty()).orEmpty())
            })
            status = label(if (current == HomeAssistantActionResult.NEEDS_PIN) R.string.ha_action_pin else R.string.ha_action_confirm, 15f)
            column.addView(status)
            pin = label(R.string.ha_action_pin_empty, 20f)
            column.addView(pin)
            keypad = GridLayout(this@HomeAssistantActionActivity).apply { columnCount = 3 }
            column.addView(keypad)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", getString(R.string.ha_action_clear), "0", getString(R.string.ha_action_delete))
            keys.forEachIndexed { index, key ->
                keypad.addView(Button(this@HomeAssistantActionActivity).apply {
                    text = key; tag = "ha:pin:$index"; minHeight = 0; minimumHeight = 0
                    setOnClickListener {
                        if (busy) return@setOnClickListener
                        when (index) { 9 -> entered.setLength(0); 11 -> if (entered.isNotEmpty()) entered.deleteCharAt(entered.lastIndex)
                            else -> if (entered.length < 8) entered.append(key) }
                        pin.text = pinPadDisplay(entered.toString())
                        send.isEnabled = entered.length >= 4
                    }
                }, GridLayout.LayoutParams(GridLayout.spec(index / 3), GridLayout.spec(index % 3, 1f)).apply { width = 0; height = dp(40) })
            }
            send = Button(this@HomeAssistantActionActivity).apply { setText(R.string.ha_action_send); tag = "ha:send"
                isEnabled = current == HomeAssistantActionResult.READY; setOnClickListener { submit() } }
            column.addView(send)
            column.addView(Button(this@HomeAssistantActionActivity).apply { setText(R.string.ha_action_cancel); setOnClickListener { finish() } })
            if (current != HomeAssistantActionResult.NEEDS_PIN) { pin.visibility = TextView.GONE; keypad.visibility = TextView.GONE }
            setContentView(FrameLayout(this@HomeAssistantActionActivity).apply {
                setBackgroundColor(theme.palette.background)
                addView(column, FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            })
            if (current == HomeAssistantActionResult.NEEDS_PIN) keypad.getChildAt(0).requestFocus() else send.requestFocus()
            handler.post(recheck)
        }
    }

    private fun submit() {
        if (busy) return
        val token = ticket ?: return
        busy = true
        send.isEnabled = false
        lifecycleScope.launch {
            try {
                if (platform.actionStatus(token) == HomeAssistantActionResult.NEEDS_PIN) {
                    if (entered.length < 4) {
                        keypad.visibility = ViewGroup.VISIBLE; pin.visibility = ViewGroup.VISIBLE
                        status.setText(R.string.ha_action_pin); keypad.getChildAt(0).requestFocus()
                        return@launch
                    }
                    val supplied = entered.toString().toCharArray()
                    entered.setLength(0)
                    pin.setText(R.string.ha_action_pin_empty)
                    if (container.pinCredentialService.verify(supplied) !is PinUnlockResult.Unlocked) {
                        status.setText(R.string.ha_action_pin_failed)
                        return@launch
                    }
                }
                status.setText(R.string.ha_action_sending)
                complete(platform.executeAction(token))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { complete(HomeAssistantActionResult.UNCERTAIN) }
            finally { busy = false; send.isEnabled = platform.actionStatus(token) == HomeAssistantActionResult.READY || entered.length >= 4 }
        }
    }
    private fun complete(result: HomeAssistantActionResult) {
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        finish()
    }
    override fun onStart() {
        super.onStart()
        container.onHomeAssistantActionUiStarted()
        if (::column.isInitialized) handler.post(recheck)
    }
    override fun onStop() {
        entered.setLength(0)
        handler.removeCallbacks(recheck)
        container.onHomeAssistantActionUiStopped()
        super.onStop()
    }
}
