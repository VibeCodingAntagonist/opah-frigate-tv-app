package app.opah.tv.ui.views

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import app.opah.tv.OpahApplication
import app.opah.tv.homeassistant.HomeAssistantConnectionPhase
import app.opah.tv.homeassistant.homeAssistantNotificationKey
import app.opah.tv.homeassistant.homeAssistantRegistrationKey
import app.opah.tv.homeassistant.android.HomeAssistantForegroundService
import app.opah.tv.notifications.AndroidNotificationIdentity
import app.opah.tv.notifications.android.AndroidNotificationChannels
import app.opah.tv.notifications.android.OpahNotificationSettings
import app.opah.tv.notifications.android.TvAlertOverlayPermission
import app.opah.tv.notifications.android.TvAlertOverlaySettings
import app.opah.tv.notifications.android.TvAlertOverlayPresenter
import app.opah.tv.privacy.NotificationDisclosure
import app.opah.tv.privacy.PrivacyRepositoryState
import app.opah.tv.setup.SetupProgress
import app.opah.tv.setup.SetupStatus
import app.opah.tv.ui.Phase0ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.util.UUID

internal class LiveGuidedSetupActions(
    private val activity: ComponentActivity,
    private val viewModel: Phase0ViewModel,
    private val pin: () -> Unit,
    private val privacy: () -> Unit,
    private val finished: (SetupProgress, Boolean) -> Unit,
) : GuidedSetupActions {
    private val container get() = (activity.application as OpahApplication).container
    private val ha get() = container.homeAssistantConnection
    private var closed = false
    private var previewActive = false
    private val previewId = AndroidNotificationIdentity("opah_setup_${UUID.randomUUID()}", 3)
    private val permission = activity.activityResultRegistry.register("setup-${UUID.randomUUID()}", ActivityResultContracts.RequestPermission()) { /* Never enable a service in a permission callback. */ }
    private fun allowed() = !closed && container.canManageConnectionSetup()

    override fun status(): SetupStatus {
        val state = viewModel.state.value
        val session = runCatching { container.homeAssistantSecretStore.read() }.getOrNull()
        val overlay = container.tvAlertOverlaySettings()
        val proof = ha.setupProbe.current()
        val profile = state.activeProfile ?: state.savedProfile
        val binding = profile?.let { homeAssistantNotificationKey(it.apiBaseUrl, it.username) }.orEmpty() + "/" +
            session?.let(::homeAssistantRegistrationKey).orEmpty()
        val ready = container.privacyRepository.state.value as? PrivacyRepositoryState.Ready
        return SetupStatus(allowed(), binding, ready?.policy?.epoch ?: -1,
            frigateSaved = profile != null, frigateConnected = state.activeProfile != null && state.snapshot != null,
            haSaved = session != null, haConnected = ha.state.value.phase == HomeAssistantConnectionPhase.CONNECTED,
            frigateAlerts = state.tvAlerts.enabled, haNotifications = session?.enabled == true, haDetails = session?.showDetails == true,
            previewPrivacy = when (ready?.policy?.globalNotificationDisclosure) {
                NotificationDisclosure.FULL_PREVIEW -> "Pictures permitted"
                NotificationDisclosure.BLURRED_PREVIEW -> "Blurred pictures"
                NotificationDisclosure.NEVER -> "Notifications hidden"
                else -> "Text only"
            }, pinConfigured = ready?.pinConfigured == true,
            notificationPermission = notificationsAllowed(), overlayPermission = TvAlertOverlayPermission.isGranted(activity),
            homeAssistantChannelAllowed = Build.VERSION.SDK_INT < 26 || activity.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(AndroidNotificationChannels.HOME_ASSISTANT_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE,
            popups = overlay.displayDurationSeconds > 0,
            delivery = if (proof.received) "Test message received" else if (proof.instruction != null) "Waiting for test message" else "Not tested",
            deliveryInstruction = proof.instruction, sharedPause = notificationPauseLabel(container.notificationPause.snapshot()),
            notificationsPaused = container.notificationPause.snapshot().paused)
    }
    override fun frigateForm(): NativeSetupForm = NativeFrigateSetup(activity, viewModel.state.value.savedProfile, ::allowed) { test, values, result ->
        if (test) viewModel.testSetupConnection(values[0], values[1], values[2], values[3], values[4], result)
        else viewModel.connect(values[0], values[1], values[2], values[3], values[4], result)
    }
    override fun homeAssistantForm(): NativeSetupForm {
        val setup = NativeHomeAssistantSetup(activity, canManage = ::allowed, setupOnly = true, closeWhenDetached = false)
        return object : NativeSetupForm {
            override val view: View = setup.createContent()
            override val busy get() = setup.signingIn
            override fun hide() = setup.leaveSetupStep()
            override fun close() = setup.close()
        }
    }
    override suspend fun checkFrigate(): Boolean {
        if (!allowed()) return false
        val profile = viewModel.state.value.activeProfile ?: return false
        val expected = status()
        return try {
            withTimeoutOrNull(10_000) { container.apiClient.refreshSession(profile); container.apiClient.getVersion(profile); true } == true &&
                allowed() && status().binding == expected.binding && status().privacyEpoch == expected.privacyEpoch
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    }
    override suspend fun checkHomeAssistant(): Boolean {
        val expected = status()
        if (!allowed()) return false
        return try { withTimeoutOrNull(10_000) {
            ha.checkSavedSignIn { allowed() && status().binding == expected.binding && status().privacyEpoch == expected.privacyEpoch }
        } == true } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    }
    override suspend fun applyNotifications(progress: SetupProgress): String? {
        if (!allowed()) return "Unlock settings to apply these choices"
        val before = status()
        val popups = progress.popups ?: before.popups
        if (progress.popups != null && progress.popups != before.popups) container.setTvAlertOverlaySettings(
            container.tvAlertOverlaySettings().copy(displayDurationSeconds = if (popups) 10 else 0))
        val issues = mutableListOf<String>()
        if (progress.services?.frigate == true && progress.frigateAlerts != null && progress.frigateAlerts != before.frigateAlerts) {
          try {
            if (progress.frigateAlerts) {
                if (!before.notificationPermission || popups && !before.overlayPermission) issues += "Allow the selected Frigate notifications first"
                else if (!before.frigateConnected || container.enableTvAlertsFromVisibleUserAction().name != "STARTED") issues += "Frigate alerts need attention"
            } else if (!container.disableTvAlerts()) issues += "Frigate alerts could not be turned off"
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { issues += "Frigate alerts need attention" }
        }
        currentCoroutineContext().ensureActive()
        if (!allowed()) return "Settings were locked — review the remaining choices"
        if (progress.services?.homeAssistant == true && progress.homeAssistantNotifications != null && progress.homeAssistantNotifications != before.haNotifications) {
          try {
            if (!before.haSaved) issues += "Connect Home Assistant first"
            else if (progress.homeAssistantNotifications && (!before.notificationPermission || !before.homeAssistantChannelAllowed || popups && !before.overlayPermission))
                issues += "Allow the selected Home Assistant notifications first"
            else if (ha.setEnabled(progress.homeAssistantNotifications)) {
                if (progress.homeAssistantNotifications) {
                    if (!HomeAssistantForegroundService.start(activity)) { ha.setEnabled(false); ha.deliveryUnavailable(); issues += "Home Assistant notifications could not start" }
                } else HomeAssistantForegroundService.stop(activity)
            } else issues += "Home Assistant notifications need attention"
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { issues += "Home Assistant notifications need attention" }
        }
        return issues.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
    override suspend fun textOnlyPrivacy() {
        if (!allowed()) return
        val policy = (container.privacyRepository.state.value as? PrivacyRepositoryState.Ready)?.policy ?: return
        container.privacyRepository.setDisclosurePolicy(policy.ownerRecognitionDisclosure, policy.guestRecognitionDisclosure,
            NotificationDisclosure.TEXT_ONLY, policy.guestNotificationDisclosure)
    }
    override suspend fun messageDetails(show: Boolean) { if (allowed()) ha.setShowDetails(show) }
    override fun configurePin() { if (allowed()) pin() }
    override fun managePrivacy() { if (allowed()) privacy() }
    override fun requestNotifications(): String? {
        if (!allowed()) return "Unlock settings to change notifications"
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            return OpahNotificationSettings.open(activity).guidance
        }
        return null
    }
    override fun requestPopups() {
        if (allowed()) runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri())) }
    }
    override fun inlinePreview(): View = TvAlertOverlayPresenter(activity).buildCard(activity, "Opah setup preview",
        "Fictional preview — no message was sent by Home Assistant", null, TvAlertOverlaySettings(), viewModel.state.value.settings)
    override suspend fun popupPreview(): Boolean {
        if (!allowed() || !status().overlayPermission || !status().popups) return false
        container.dismissSetupPreview(previewId); previewActive = true
        val shown = withTimeoutOrNull(5_000) { suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { previewActive = false; container.dismissSetupPreview(previewId) }
            container.showSetupPreview(previewId, viewModel.state.value.settings, {
                previewActive && allowed() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }) {
                if (continuation.isActive) continuation.resume(true)
            }
        } } == true
        if (!shown) { previewActive = false; container.dismissSetupPreview(previewId) }
        return shown
    }
    override fun beginDeliveryCheck(): String? = if (allowed()) ha.beginSetupDeliveryCheck() else null
    override fun finish(progress: SetupProgress, completed: Boolean) { if (allowed()) finished(progress, completed) }
    override fun close() { if (!closed) { closed = true; previewActive = false; permission.unregister(); container.dismissSetupPreview(previewId) } }
    private fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(activity).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
}
