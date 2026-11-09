package app.opah.tv.ui.views

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.isEmpty
import androidx.core.graphics.ColorUtils
import app.opah.tv.setup.GuidedSetupStore
import app.opah.tv.setup.SetupProgress
import app.opah.tv.setup.SetupServices
import app.opah.tv.setup.SetupStatus
import app.opah.tv.setup.SetupStep
import app.opah.tv.setup.finishShowsCameras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** The wizard never infers delivery from a preview or applies choices merely by revisiting a step. */
@SuppressLint("SetTextI18n")
internal class NativeGuidedSetup(
    private val activity: ComponentActivity,
    private val actions: GuidedSetupActions,
    private val store: GuidedSetupStore = GuidedSetupStore(activity),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progress = store.read()
    private var status = actions.status()
    private var intro = progress.services != null || status.frigateSaved || status.haSaved
    private var closed = false
    private var working = false
    private var operation: Job? = null
    private var operationVersion = 0L
    private var renderedFrigateConnected: Boolean? = null
    private var frigate: NativeSetupForm? = null
    private var homeAssistant: NativeSetupForm? = null
    private var inlineShown = false
    private var popupShown = false
    private var checkedFrigate: Boolean? = null
    private var checkedHa: Boolean? = null
    private var checkBinding = status.binding
    private var checkEpoch = status.privacyEpoch
    private val labels = linkedMapOf<String, TextView>()
    private val buttons = linkedMapOf<String, Button>()
    private val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val view: View get() = root
    private val heading = label("", 21f)
    private val position = label("", 12f).apply { tag = "setup:position" }
    private val body = FrameLayout(activity)
    private val feedback = label("", 13f).apply { tag = "setup:feedback" }
    private val back = button("Back", "back") { handleBack() }
    private val later = button("Set up later", "later") { advance() }
    private val next = button("Continue", "continue") { continueStep() }
    private val lifecycle = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) { cancelOperation(); frigate?.hide(); homeAssistant?.hide() }
    }

    init {
        root.addView(heading); root.addView(position)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(feedback)
        root.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            for (control in listOf(back, later, next)) addView(control, LinearLayout.LayoutParams(0, activity.dp(42), 1f))
        })
        root.setPadding(activity.dp(8), 0, activity.dp(8), 0)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = close()
        })
        activity.lifecycle.addObserver(lifecycle)
        render()
        scope.launch {
            while (isActive) {
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) refresh()
                delay(600)
            }
        }
    }
    fun close() {
        if (closed) return
        closed = true; scope.cancel(); frigate?.close(); homeAssistant?.close(); actions.close()
        activity.lifecycle.removeObserver(lifecycle)
    }
    fun handleBack(): Boolean {
        if (closed) return false
        if (intro || progress.step == SetupStep.CHOOSE) { actions.finish(progress, false); return true }
        cancelOperation(); currentForm()?.hide()
        progress = progress.back(); save(); render()
        return true
    }
    private fun currentForm() = when (progress.step) {
        SetupStep.FRIGATE -> frigate
        SetupStep.HOME_ASSISTANT -> homeAssistant
        else -> null
    }
    private fun save() { if (!store.write(progress)) feedback.text = "Setup progress could not be saved — you can still continue" }
    private fun advance(cancelWork: Boolean = true) {
        if (closed || !actions.status().canManage) return
        if (cancelWork) cancelOperation()
        currentForm()?.hide()
        progress = progress.next(); save(); render()
    }
    private fun continueStep() {
        if (closed || working || currentForm()?.busy == true || !actions.status().canManage) return
        if (intro) return
        if (progress.step == SetupStep.FINISH) {
            progress = progress.copy(completed = true); save(); actions.finish(progress, true)
        } else if (progress.step == SetupStep.PERMISSIONS && notificationChoicesChanged()) {
            launchOperation {
                val issue = actions.applyNotifications(progress)
                refresh()
                if (issue == null) advance(cancelWork = false) else feedback.text = issue
            }
        } else advance()
    }
    private fun notificationChoicesChanged() =
        progress.services?.frigate == true && progress.frigateAlerts?.let { it != status.frigateAlerts } == true ||
            progress.services?.homeAssistant == true && progress.homeAssistantNotifications?.let { it != status.haNotifications } == true ||
            progress.popups?.let { it != status.popups } == true

    private fun render() {
        labels.clear(); buttons.clear(); body.removeAllViews(); feedback.text = ""
        status = actions.status()
        if (!status.canManage) { heading.text = "Setup is protected"; close(); return }
        if (intro) { renderIntro(); return }
        heading.text = progress.step.title
        val steps = progress.steps()
        position.text = "Step ${steps.indexOf(progress.step) + 1} of ${steps.size}"
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun text(value: String) { column.addView(label(value, 14f)) }
        fun action(key: String, title: String, run: () -> Unit) { column.addView(button(title, key, run).also { buttons[key] = it }) }
        fun row(key: String, title: String) { column.addView(label(title, 14f).also { it.tag = "setup:status:$key"; labels[key] = it }) }
        when (progress.step) {
            SetupStep.CHOOSE -> {
                text("What would you like to connect?")
                SetupServices.entries.forEach { service ->
                    action("services:${service.name}", when (service) { SetupServices.FRIGATE -> "Frigate"; SetupServices.HOME_ASSISTANT -> "Home Assistant"; SetupServices.BOTH -> "Both" }) {
                        progress = progress.choose(service); save(); refresh()
                    }
                }
                action("order", "Connect Home Assistant first") { progress = progress.copy(homeAssistantFirst = !progress.homeAssistantFirst); save(); refresh() }
                text("Each connection works independently — either one can be set up later")
            }
            SetupStep.FRIGATE -> {
                renderedFrigateConnected = status.frigateConnected
                if (status.frigateConnected) {
                    text("Frigate is connected\nKeep this connection and continue without signing in again")
                    action("check:frigate", "Check saved connection") { launchOperation { checkedFrigate = actions.checkFrigate(); refresh() } }
                    row("frigate-check", "")
                } else {
                    frigate = frigate ?: actions.frigateForm()
                    mount(requireNotNull(frigate).view)
                }
            }
            SetupStep.HOME_ASSISTANT -> {
                homeAssistant = homeAssistant ?: actions.homeAssistantForm()
                mount(requireNotNull(homeAssistant).view)
            }
            SetupStep.NOTIFICATIONS -> {
                text("Choose what this TV receives\nChanging these choices does not enable anything until you apply them")
                if (progress.services?.frigate == true) action("notifications:frigate", "") {
                    progress = progress.copy(frigateAlerts = !(progress.frigateAlerts ?: status.frigateAlerts)); save(); refresh()
                }
                if (progress.services?.homeAssistant == true) action("notifications:ha", "") {
                    progress = progress.copy(homeAssistantNotifications = !(progress.homeAssistantNotifications ?: status.haNotifications)); save(); refresh()
                }
                action("notifications:popups", "") { progress = progress.copy(popups = !(progress.popups ?: status.popups)); save(); refresh() }
                text("TV pop-ups use one shared setting for both services\nPictures, interactive Frigate alerts, and automation buttons keep their current settings")
            }
            SetupStep.PRIVACY -> {
                text("Choose what this TV can show\nExisting privacy choices are preserved unless you change them here")
                row("privacy", "")
                action("text-only", "Use text-only TV alerts") { launchOperation { actions.textOnlyPrivacy(); refresh() } }
                if (progress.services?.homeAssistant == true) action("ha-details", "") { launchOperation { actions.messageDetails(!status.haDetails); refresh() } }
                row("pin", "")
                action("pin", if (status.pinConfigured) "Manage privacy and PIN" else "Protect settings with a PIN") {
                    if (status.pinConfigured) actions.managePrivacy() else actions.configurePin()
                }
                text("Message details start hidden on a new connection\nPictures and automation buttons are separate opt-ins in Home Assistant settings")
            }
            SetupStep.PERMISSIONS -> {
                text("Allow only the notifications you selected\nPermissions alone do not enable either service")
                row("notifications-permission", ""); row("overlay-permission", "")
                action("notifications-permission", "Allow Android notifications") {
                    actions.requestNotifications()?.let { feedback.text = it }
                }
                action("overlay-permission", "Allow TV pop-ups") { actions.requestPopups() }
                text("Use Apply choices to enable your selections, or Set up later to continue without enabling them")
            }
            SetupStep.CHECKS -> {
                addChecks(column)
                action("run-checks", "Run connection checks") { runChecks() }
                val previewHost = FrameLayout(activity)
                action("inline-preview", "Preview in Opah") {
                    val preview = actions.inlinePreview()
                    previewHost.removeAllViews(); previewHost.addView(preview)
                    preview.post { if (!closed && preview.isAttachedToWindow) { inlineShown = true; refresh() } }
                }
                column.addView(previewHost)
                action("popup-preview", "Show a fictional TV pop-up") { launchOperation {
                    popupShown = actions.popupPreview()
                    if (!popupShown) feedback.text = "The pop-up could not appear — check pop-up permission and duration"
                    refresh()
                } }
                if (progress.services?.homeAssistant == true) {
                    action("delivery-check", "Listen for a Home Assistant test") {
                        if (actions.beginDeliveryCheck() == null) feedback.text = "Turn on Home Assistant notifications and wait for Connected first"
                        refresh()
                    }
                    row("delivery-instructions", "")
                }
                text("A fictional preview tests appearance only\nHome Assistant delivery is checked only by a matching message received from Home Assistant")
            }
            SetupStep.FINISH -> {
                text("Your connections are saved independently\nYou can finish now and return to anything marked as unfinished")
                addChecks(column)
            }
        }
        if (body.isEmpty()) mount(ScrollView(activity).apply { addView(column); isFillViewport = false })
        refresh()
        body.post { if (!closed) body.findViewWithTag<View>("setup:services:${progress.services?.name ?: SetupServices.FRIGATE.name}")?.requestFocus() }
    }
    private fun renderIntro() {
        heading.text = "Setup and connection checks"; position.text = "Connect either service, or check what is already ready"
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        if (progress.services != null && !progress.completed) column.addView(button("Resume setup", "resume") { intro = false; render() })
        column.addView(button("Start guided setup", "start") { progress = SetupProgress(); intro = false; save(); render() })
        column.addView(button("Check setup", "check-setup") {
            progress = SetupProgress(services = when { status.frigateSaved && !status.haSaved -> SetupServices.FRIGATE
                status.haSaved && !status.frigateSaved -> SetupServices.HOME_ASSISTANT; else -> SetupServices.BOTH }, step = SetupStep.CHECKS)
            intro = false; save(); render(); runChecks()
        })
        mount(column); next.visibility = View.GONE; later.visibility = View.GONE
    }
    private fun addChecks(column: LinearLayout) {
        fun row(key: String) { column.addView(label("", 14f).also { it.tag = "setup:status:$key"; labels[key] = it }) }
        if (progress.services?.frigate == true) { row("check:frigate"); row("check:frigate-notifications") }
        if (progress.services?.homeAssistant == true) { row("check:ha-signin"); row("check:ha-notifications"); row("check:ha-channel"); row("check:ha-delivery") }
        row("check:shared-pause"); row("check:permissions"); row("check:popups"); row("check:inline"); row("check:popup-preview")
    }
    private fun refresh() {
        if (closed) return
        status = actions.status()
        if (!status.canManage) { operation?.cancel(); close(); return }
        if (!intro) {
            val steps = progress.steps()
            position.text = "Step ${steps.indexOf(progress.step) + 1} of ${steps.size}"
        }
        if (checkBinding != status.binding || checkEpoch != status.privacyEpoch) {
            checkBinding = status.binding; checkEpoch = status.privacyEpoch; checkedFrigate = null; checkedHa = null
        }
        if (!status.overlayPermission || !status.popups) popupShown = false
        if (!intro && progress.step == SetupStep.FRIGATE && renderedFrigateConnected != status.frigateConnected) {
            if (!status.frigateConnected) { frigate?.close(); frigate = null }
            render(); return
        }
        fun row(key: String, value: String) { labels[key]?.text = value }
        fun toggle(key: String, title: String, on: Boolean) { buttons[key]?.text = "$title: ${if (on) "On" else "Off"}" }
        for (service in SetupServices.entries) buttons["services:${service.name}"]?.apply {
            isSelected = progress.services == service
            text = (if (isSelected) "✓ " else "") + when (service) { SetupServices.FRIGATE -> "Frigate"; SetupServices.HOME_ASSISTANT -> "Home Assistant"; SetupServices.BOTH -> "Both" }
        }
        buttons["order"]?.apply { visibility = if (progress.services == SetupServices.BOTH) View.VISIBLE else View.GONE
            text = if (progress.homeAssistantFirst) "First connection: Home Assistant" else "First connection: Frigate" }
        toggle("notifications:frigate", "Frigate alerts", progress.frigateAlerts ?: status.frigateAlerts)
        toggle("notifications:ha", "Home Assistant messages", progress.homeAssistantNotifications ?: status.haNotifications)
        toggle("notifications:popups", "TV pop-ups", progress.popups ?: status.popups)
        toggle("ha-details", "Show message details", status.haDetails)
        buttons["pin"]?.text = if (status.pinConfigured) "Manage privacy and PIN" else "Protect settings with a PIN"
        row("privacy", "Preview privacy: ${status.previewPrivacy}"); row("pin", if (status.pinConfigured) "PIN is configured" else "Local PIN: Not set")
        row("frigate-check", when (checkedFrigate) { true -> "Saved connection checked"; false -> "Connection needs attention"; null -> "Saved connection not checked yet" })
        val wanted = progress.services?.frigate == true && (progress.frigateAlerts ?: status.frigateAlerts) ||
            progress.services?.homeAssistant == true && (progress.homeAssistantNotifications ?: status.haNotifications)
        row("notifications-permission", "Android notifications: ${if (status.notificationPermission) "Allowed" else if (wanted) "Permission needed" else "Not requested"}")
        row("overlay-permission", "TV pop-ups: ${if (!(progress.popups ?: status.popups)) "Off" else if (status.overlayPermission) "Allowed" else "Permission needed"}")
        buttons["notifications-permission"]?.visibility = if (wanted) View.VISIBLE else View.GONE
        buttons["overlay-permission"]?.visibility = if (wanted && (progress.popups ?: status.popups)) View.VISIBLE else View.GONE
        buttons["popup-preview"]?.isEnabled = !working && status.overlayPermission && status.popups && !status.notificationsPaused
        row("check:frigate", "Frigate: ${if (checkedFrigate == true) "Connection checked" else if (checkedFrigate == false) "Needs attention" else if (status.frigateConnected) "Connected — not checked here" else if (status.frigateSaved) "Sign in needed" else "Not connected"}")
        row("check:frigate-notifications", "Frigate alerts: ${if (status.frigateAlerts) "On" else "Off"}")
        row("check:ha-signin", "Home Assistant sign-in: ${if (checkedHa == true) "Checked" else if (checkedHa == false) "Needs attention" else if (status.haSaved) "Saved — not checked here" else "Not connected"}")
        row("check:ha-notifications", "Home Assistant notifications: ${if (status.haNotifications && !status.homeAssistantChannelAllowed) "Blocked in Android settings" else if (status.haNotifications) "On" else "Off"}")
        row("check:ha-channel", "Home Assistant connection: ${if (status.haConnected) "Connected" else if (status.haNotifications) "Not connected yet" else "Notifications off"}")
        row("check:ha-delivery", "Home Assistant delivery: ${status.delivery}")
        row("check:shared-pause", "Shared notification pause: ${status.sharedPause}")
        val receiving = progress.services?.frigate == true && status.frigateAlerts || progress.services?.homeAssistant == true && status.haNotifications
        row("check:permissions", "Android notifications: ${if (status.notificationPermission) "Allowed" else if (receiving) "Permission needed" else "Not requested"}")
        row("check:popups", "TV pop-ups: ${if (!status.popups) "Off" else if (status.overlayPermission) "Allowed" else if (receiving) "Permission needed" else "Not requested"}")
        row("check:inline", "In-app preview: ${if (inlineShown) "Preview shown" else "Not tested"}")
        row("check:popup-preview", "TV pop-up preview: ${if (popupShown) "Preview shown" else "Not tested"}")
        row("delivery-instructions", status.deliveryInstruction?.let { "In Home Assistant, choose this TV's Mobile App notification action\nSend this exact message:\n$it" }
            ?: "A real message from Home Assistant is required — a local preview does not count")
        next.visibility = if (intro) View.GONE else View.VISIBLE
        later.visibility = if (intro || progress.step in setOf(SetupStep.CHOOSE, SetupStep.FINISH)) View.GONE else View.VISIBLE
        next.text = if (progress.step == SetupStep.FINISH) { if (progress.finishShowsCameras(status)) "View cameras" else "Done" }
            else if (progress.step == SetupStep.PERMISSIONS && notificationChoicesChanged()) "Apply choices" else "Continue"
        next.isEnabled = !working && currentForm()?.busy != true && (progress.services != null || intro)
        for ((key, button) in buttons) if (key != "popup-preview") button.isEnabled = !working
        buttons["ha-details"]?.isEnabled = !working && status.haSaved
        buttons["notifications-permission"]?.text = if (status.notificationPermission) "Android notification settings" else "Allow Android notifications"
        buttons["overlay-permission"]?.text = if (status.overlayPermission) "TV pop-up settings" else "Allow TV pop-ups"
    }
    private fun runChecks() = launchOperation {
        val expected = actions.status()
        val results = supervisorScope {
            val first = async { if (progress.services?.frigate == true && expected.frigateSaved) readCheck(actions::checkFrigate) else null }
            val second = async { if (progress.services?.homeAssistant == true && expected.haSaved) readCheck(actions::checkHomeAssistant) else null }
            first.await() to second.await()
        }
        if (!closed && actions.status().binding == expected.binding && actions.status().privacyEpoch == expected.privacyEpoch) {
            checkedFrigate = results.first; checkedHa = results.second; checkBinding = expected.binding
        }
        refresh()
    }
    private fun launchOperation(work: suspend () -> Unit) {
        if (closed || working || !actions.status().canManage) return
        val version = ++operationVersion
        operation = scope.launch {
            working = true; refresh()
            try { work() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!closed) feedback.text = "This step could not finish — try again or set it up later" }
            finally { if (operationVersion == version) { working = false; refresh() } }
        }
    }
    private fun cancelOperation() { operationVersion++; operation?.cancel(); operation = null; working = false }
    private suspend fun readCheck(read: suspend () -> Boolean): Boolean = try { read() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    private fun mount(child: View) {
        (child.parent as? ViewGroup)?.removeView(child)
        body.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private fun label(value: String, size: Float) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(NativeTheme.palette.text)
        setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
    }
    private fun button(value: String, key: String, clicked: () -> Unit) = Button(activity).apply {
        text = value; tag = "setup:$key"; textSize = 14f; minHeight = 0; minimumHeight = 0; isAllCaps = false
        background = activity.nativeFlatFocusableBackground()
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(NativeTheme.palette.text, 100), NativeTheme.palette.text)))
        setPadding(activity.dp(6), activity.dp(5), activity.dp(6), activity.dp(5))
        setOnClickListener { if (!closed && actions.status().canManage) clicked() }
    }
}
