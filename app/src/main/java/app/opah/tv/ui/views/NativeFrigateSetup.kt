package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.isVisible
import androidx.core.graphics.ColorUtils
import app.opah.tv.R
import app.opah.tv.data.ConnectionProfileFactory
import app.opah.tv.data.model.ConnectionProfile
import app.opah.tv.ui.ConnectionFormSubmission
import kotlinx.coroutines.Job

internal interface NativeSetupForm {
    val view: View
    val busy: Boolean
    fun hide()
    fun close()
}

/** Fields live only for the open wizard. Going Back retains them; leaving clears the password. */
@SuppressLint("SetTextI18n")
internal class NativeFrigateSetup(context: Context, initial: ConnectionProfile?,
    private val canManage: () -> Boolean,
    private val submit: (Boolean, List<String>, Boolean, (Result<String>) -> Unit) -> Job?,
) : NativeSetupForm {
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(8), 0, context.dp(8), 0) }
    override val view = ScrollView(context).apply { addView(column); isFillViewport = false }
    override var busy = false
        private set
    private val fields = mutableListOf<EditText>()
    private val controls = mutableListOf<Button>()
    private var closed = false
    private var connected = false
    private var useNoSignIn = initial?.withoutSignIn == true
    private val isInternalPort: Boolean
        get() = ConnectionProfileFactory.targetsDefaultInternalPort(fields[0].text.toString())
    private val withoutSignIn: Boolean get() = isInternalPort || useNoSignIn
    private val feedback = TextView(context).apply { textSize = 14f; setTextColor(NativeTheme.palette.text); tag = "setup:frigate:feedback" }
    private val submission = ConnectionFormSubmission(onStatus = { working, message ->
        busy = working
        fields.forEachIndexed { index, field -> field.isEnabled = !working && !(withoutSignIn && index in 1..2) }
        controls.forEach { it.isEnabled = !working }
        feedback.text = message.trimEnd('.')
    }, onConnected = { connected = true; fields[2].text?.clear(); fields.forEach { it.isEnabled = false }; controls.forEach { it.isEnabled = false }
        feedback.text = "Connected — this connection is saved" })

    init {
        fun label(text: String) = TextView(context).apply { this.text = text; textSize = 13f; setTextColor(NativeTheme.palette.secondaryText) }
        fun field(name: String, value: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT, parent: LinearLayout = column): EditText {
            parent.addView(label(name))
            return EditText(context).apply {
                setText(value); this.hint = hint; inputType = type; isSingleLine = true; textSize = 15f
                filters = arrayOf(InputFilter.LengthFilter(2_048)); setSelectAllOnFocus(true)
                isSaveEnabled = false
                tag = "setup:frigate:field:${fields.size}"; fields += this
                setTextColor(NativeTheme.palette.text)
                setHintTextColor(NativeTheme.palette.secondaryText)
                background = context.nativeFlatFieldBackground()
                setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
                parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(42)))
            }
        }
        field("Frigate address", initial?.apiBaseUrl.orEmpty(), "https://frigate.example:8971", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        fun button(name: String, action: () -> Unit) = Button(context).apply {
            text = name; textSize = 14f; minHeight = 0; minimumHeight = 0; isAllCaps = false
            background = context.nativeFlatFocusableBackground()
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(ColorUtils.setAlphaComponent(NativeTheme.palette.text, 100), NativeTheme.palette.text)))
            setOnClickListener { if (!closed && canManage()) action() }; controls += this
            column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(42)))
        }
        val credentials = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val notice = label(context.getString(R.string.frigate_without_sign_in_warning))
        lateinit var accessChoice: Button
        lateinit var saveConnection: Button
        fun updateAccess() {
            accessChoice.isVisible = !isInternalPort
            accessChoice.setText(if (useNoSignIn) R.string.frigate_connect_without_sign_in_on
                else R.string.frigate_connect_without_sign_in_off)
            notice.isVisible = withoutSignIn
            credentials.isVisible = !withoutSignIn
            fields[1].isEnabled = !withoutSignIn && !busy
            fields[2].isEnabled = !withoutSignIn && !busy
            if (withoutSignIn) fields[2].text?.clear()
            saveConnection.text = if (withoutSignIn) "Connect and save" else "Sign in and save"
        }
        accessChoice = button(context.getString(R.string.frigate_connect_without_sign_in_off)) {
            useNoSignIn = !useNoSignIn
            updateAccess()
        }.apply { tag = "setup:frigate:without-sign-in" }
        column.addView(notice)
        field("Username", initial?.takeUnless { it.withoutSignIn }?.username.orEmpty(), "Your Frigate account", parent = credentials)
        field("Password", "", "Your Frigate password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, credentials)
        column.addView(credentials)
        val advanced = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        button("Advanced live video options") { advanced.isVisible = !advanced.isVisible }.tag = "setup:frigate:advanced"
        field("Live video host", initial?.rtspHostOverride.orEmpty(), "Use the Frigate host", parent = advanced)
        field("Live video port", (initial?.rtspPort ?: 8554).toString(), "8554", InputType.TYPE_CLASS_NUMBER, advanced)
        column.addView(advanced)
        column.addView(feedback)
        button("Check connection") { run(test = true) }.tag = "setup:frigate:test"
        saveConnection = button("Sign in and save") { run(test = false) }.apply { tag = "setup:frigate:connect" }
        fields[0].doAfterTextChanged {
            useNoSignIn = false
            updateAccess()
        }
        updateAccess()
    }
    private fun run(test: Boolean) {
        if (connected) return
        submission.submit(test) { callback -> submit(test, fields.map { it.text.toString() }, withoutSignIn, callback) }
    }
    override fun hide() { submission.cancelPending() }
    override fun close() { closed = true; submission.close(); fields[2].text?.clear() }
}
