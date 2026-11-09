package app.opah.tv

import android.app.PictureInPictureParams
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ClipData
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import app.opah.tv.homeassistant.android.HomeAssistantForegroundService
import androidx.compose.runtime.mutableStateOf
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.notifications.android.TvAlertIntentContract
import app.opah.tv.notifications.android.TvAlertOverlayPermission
import app.opah.tv.ui.OpahApp
import app.opah.tv.ui.Phase0ViewModel
import app.opah.tv.ui.views.NativeHostActions
import app.opah.tv.ui.views.NativeOpahController
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.media3.common.util.UnstableApi
class MainActivity : ComponentActivity() {
    private val pipModeActive = mutableStateOf(false)
    private var fullyDrawnReported = false
    private val viewModel: Phase0ViewModel by viewModels {
        Phase0ViewModel.Factory(application)
    }
    private var pendingNotificationAction = PendingNotificationAction.NONE
    private var awaitingOverlayPermission = false
    private var nativeOpahController: NativeOpahController? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingNotificationAction
        pendingNotificationAction = PendingNotificationAction.NONE
        if (granted) {
            runNotificationAction(action)
        } else {
            viewModel.notificationPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val documentationScenario = intent.documentationExtra(EXTRA_DOCUMENTATION_SCENARIO)
        val documentationDestination = intent.documentationExtra(EXTRA_DOCUMENTATION_DESTINATION)
        val documentationSettingsPage = intent.documentationExtra(EXTRA_DOCUMENTATION_SETTINGS_PAGE)
        val documentationInformationTab = intent.documentationExtra(EXTRA_DOCUMENTATION_INFORMATION_TAB)
        val documentationActivityPage = intent.documentationExtra(EXTRA_DOCUMENTATION_ACTIVITY_PAGE)
        val documentationClipsPage = intent.documentationExtra(EXTRA_DOCUMENTATION_CLIPS_PAGE)
        if (BuildConfig.DOCUMENTATION_MODE) viewModel.setDocumentationScenario(documentationScenario)
        handleTvAlertLaunch(intent)
        handleCameraLaunch(intent)
        if (BuildConfig.NATIVE_VIEW_PRESENTATION) {
            val controller = NativeOpahController(
                activity = this,
                viewModel = viewModel,
                actions = NativeHostActions(
                    onExitRequested = ::finish,
                    onInstallUpdate = ::openUpdateInstaller,
                    onEnableTvAlerts = ::requestEnableTvAlerts,
                    onTestTvAlert = ::requestTestTvAlert,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onOpenOverlaySettings = ::openOverlaySettings,
                    pictureInPictureAvailable = supportsTelevisionPictureInPicture(),
                    onEnterPictureInPicture = ::requestLivePictureInPicture,
                    onShareSnapshot = ::shareSnapshot,
                    onShareClip = ::shareClip,
                    onFullyDrawn = ::reportFullyDrawnOnce,
                ),
                initialDestinationName = documentationDestination,
                initialSettingsPageName = documentationSettingsPage,
                initialInformationTabName = documentationInformationTab,
                initialActivityPageName = documentationActivityPage,
                initialClipsPageName = documentationClipsPage,
                restoredUiState = savedInstanceState?.getBundle("nativeUiState"),
            )
            nativeOpahController = controller
            setContentView(controller.root)
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        controller.handleSystemBack()
                    }
                },
            )
            controller.start()
        } else {
            setContent {
                OpahApp(
                    viewModel = viewModel,
                    pictureInPictureAvailable = supportsTelevisionPictureInPicture(),
                    pictureInPictureActive = pipModeActive.value,
                    onEnterPictureInPicture = ::requestLivePictureInPicture,
                    onFullyDrawn = ::reportFullyDrawnOnce,
                    onExitRequested = ::finish,
                    onInstallUpdate = ::openUpdateInstaller,
                    onShareSnapshot = ::shareSnapshot,
                    onShareClip = ::shareClip,
                    onEnableTvAlerts = ::requestEnableTvAlerts,
                    onTestTvAlert = ::requestTestTvAlert,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onOpenOverlaySettings = ::openOverlaySettings,
                    initialDestinationName = documentationDestination,
                    initialSettingsPageName = documentationSettingsPage,
                    initialInformationTabName = documentationInformationTab,
                    initialActivityPageName = documentationActivityPage,
                    initialClipsPageName = documentationClipsPage,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        nativeOpahController?.let { outState.putBundle("nativeUiState", it.saveUiState()) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        nativeOpahController?.stop()
        nativeOpahController = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTvAlertLaunch(intent)
        handleCameraLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshPrivacyUnlockState()
        viewModel.refreshTvAlertDeliveryStatus()
        if (!BuildConfig.DOCUMENTATION_MODE) {
            lifecycleScope.launch {
                val connection = (application as OpahApplication).container.homeAssistantConnection
                val enabled = withContext(Dispatchers.IO) { connection.enabled() }
                if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    HomeAssistantForegroundService.start(this@MainActivity)
                }
            }
        }
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            val action = pendingNotificationAction
            pendingNotificationAction = PendingNotificationAction.NONE
            if (TvAlertOverlayPermission.isGranted(this)) {
                if (action != PendingNotificationAction.NONE) runNotificationAction(action)
            } else if (action != PendingNotificationAction.NONE) {
                viewModel.onScreenAlertPermissionDenied()
            }
            return
        }
        if (
            pendingNotificationAction != PendingNotificationAction.NONE &&
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            val action = pendingNotificationAction
            pendingNotificationAction = PendingNotificationAction.NONE
            runNotificationAction(action)
        }
    }

    override fun onStop() {
        (application as OpahApplication).container.onMainUiStopped()
        super.onStop()
    }

    private fun handleCameraLaunch(intent: Intent) {
        val uri = intent.data
        val cameraName = cameraNameFromLaunch(
            explicitCameraName = intent.getStringExtra(EXTRA_CAMERA_NAME),
            compatibleCameraName = intent.getStringExtra(EXTRA_CAMERA_NAME_COMPAT),
            action = intent.action,
            scheme = uri?.scheme,
            host = uri?.host,
            pathSegments = uri?.pathSegments.orEmpty(),
        )
        cameraName?.let(viewModel::openCameraByName)
    }

    private fun handleTvAlertLaunch(intent: Intent) {
        if (TvAlertIntentContract.isLocalTestOpen(intent)) {
            viewModel.openLocalTestAlertSettings()
            return
        }
        val payload = TvAlertIntentContract.payload(intent) ?: return
        viewModel.openTvAlert(payload.profileKey, payload.reviewId, payload.actionNonce, payload.preference)
    }

    private fun requestEnableTvAlerts() {
        requestNotificationPermissionOrRun(PendingNotificationAction.ENABLE_ALERTS)
    }

    private fun requestTestTvAlert(includeImage: Boolean) {
        requestNotificationPermissionOrRun(
            if (includeImage) {
                PendingNotificationAction.SEND_TEST_WITH_IMAGE
            } else {
                PendingNotificationAction.SEND_TEST_WITHOUT_IMAGE
            },
        )
    }

    private fun requestNotificationPermissionOrRun(
        action: PendingNotificationAction,
    ) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runNotificationAction(action)
        }
    }

    private fun runNotificationAction(action: PendingNotificationAction) {
        if (action == PendingNotificationAction.NONE) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            pendingNotificationAction = action
            viewModel.notificationDeliveryBlocked()
            openNotificationSettings()
            return
        }
        val onScreenAlertsEnabled =
            (application as OpahApplication).container.tvAlertOverlaySettings()
                .displayDurationSeconds > 0
        if (onScreenAlertsEnabled && !TvAlertOverlayPermission.isGranted(this)) {
            openOverlaySettings(action)
            return
        }
        when (action) {
            PendingNotificationAction.ENABLE_ALERTS -> viewModel.enableTvAlertsAfterPermission()
            PendingNotificationAction.SEND_TEST_WITHOUT_IMAGE -> sendTestAlertFromBackground(false)
            PendingNotificationAction.SEND_TEST_WITH_IMAGE -> sendTestAlertFromBackground(true)
            PendingNotificationAction.NONE -> Unit
        }
    }

    private fun sendTestAlertFromBackground(includeImage: Boolean) {
        viewModel.prepareLocalTestAlert()
        moveTaskToBack(true)
        lifecycleScope.launch {
            delay(TEST_ALERT_POST_DELAY_MILLIS)
            viewModel.sendLocalTestAlert(includeImage)
        }
    }

    private fun openNotificationSettings() {
        app.opah.tv.notifications.android.OpahNotificationSettings.open(this).guidance?.let {
            android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings(
        action: PendingNotificationAction = PendingNotificationAction.NONE,
    ) {
        if (action != PendingNotificationAction.NONE) pendingNotificationAction = action
        awaitingOverlayPermission = true
        val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                data = "package:$packageName".toUri()
            }
        }
        runCatching { startActivity(settingsIntent) }
            .onFailure {
                awaitingOverlayPermission = false
                pendingNotificationAction = PendingNotificationAction.NONE
                viewModel.onScreenAlertSettingsUnavailable()
            }
    }

    private fun openUpdateInstaller(apkPath: String) {
        val updateDirectory = File(cacheDir, "updates").canonicalFile
        val apk = runCatching { File(apkPath).canonicalFile }.getOrNull()
        if (apk == null || !apk.isFile || apk.parentFile != updateDirectory || apk.extension != "apk") {
            viewModel.reportUpdateInstallError()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:$packageName".toUri(),
            )
            runCatching { startActivity(permissionIntent) }
                .onFailure { viewModel.reportUpdateInstallError() }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(installIntent) }
            .onFailure { viewModel.reportUpdateInstallError() }
    }

    private fun shareSnapshot(bitmap: Bitmap, cameraLabel: String): Boolean = runCatching {
        val directory = File(cacheDir, "snapshots").apply { mkdirs() }.canonicalFile
        check(directory.parentFile == cacheDir.canonicalFile)
        val image = File(directory, "opah-snapshot-${System.currentTimeMillis()}.jpg").canonicalFile
        check(image.parentFile == directory)
        FileOutputStream(image).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", image)
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "$cameraLabel snapshot")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        sendIntent.clipData = ClipData.newRawUri("Opah snapshot", uri)
        startActivity(Intent.createChooser(sendIntent, "Share snapshot"))
        true
    }.getOrDefault(false)

    private fun shareClip(export: RecordingExport) {
        lifecycleScope.launch {
            val clip = viewModel.prepareClipShare(export).getOrNull() ?: return@launch
            sharePreparedClip(clip, export.name.replace('_', ' '))
        }
    }

    private fun sharePreparedClip(clip: File, title: String): Boolean = runCatching {
        val directory = File(cacheDir, "shared-clips").canonicalFile
        val verified = clip.canonicalFile
        check(verified.isFile && verified.parentFile == directory && verified.extension == "mp4")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", verified)
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        sendIntent.clipData = ClipData.newRawUri("Opah clip", uri)
        startActivity(Intent.createChooser(sendIntent, "Share clip"))
        true
    }.getOrDefault(false)

    private fun reportFullyDrawnOnce() {
        if (fullyDrawnReported) return
        fullyDrawnReported = true
        StartupTrace.end()
        // The API 34 TV renderer occasionally omits the RenderThread slice that
        // stable Macrobenchmark 1.4.1 expects after reportFullyDrawn(). Keep the
        // production signal, and use OpahStartupToUsable for benchmark TTFD.
        if (BuildConfig.BUILD_TYPE != "benchmark") reportFullyDrawn()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeActive.value = isInPictureInPictureMode
        nativeOpahController?.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (!isInPictureInPictureMode && Build.VERSION.SDK_INT >= 31) {
            disableAutomaticPictureInPictureEntry()
        }
    }

    private fun supportsTelevisionPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun requestLivePictureInPicture(request: PictureInPictureRequest): Boolean =
        if (Build.VERSION.SDK_INT >= 26) enterLivePictureInPicture(request) else false

    @RequiresApi(26)
    private fun enterLivePictureInPicture(request: PictureInPictureRequest): Boolean {
        if (!supportsTelevisionPictureInPicture() || pipModeActive.value) return false
        val builder = PictureInPictureParams.Builder()
        if (Build.VERSION.SDK_INT >= 31) {
            builder
                .setSeamlessResizeEnabled(true)
                .setAutoEnterEnabled(true)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            builder
                .setTitle(request.title)
                .setSubtitle(request.subtitle)
        }
        request.aspectRatio?.let { builder.setAspectRatio(Rational(it.width, it.height)) }
        request.sourceRectHint?.takeUnless { it.isEmpty }?.let(builder::setSourceRectHint)
        val entered = runCatching { enterPictureInPictureMode(builder.build()) }.getOrDefault(false)
        if (!entered && Build.VERSION.SDK_INT >= 31) disableAutomaticPictureInPictureEntry()
        return entered
    }

    @RequiresApi(31)
    private fun disableAutomaticPictureInPictureEntry() {
        setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAutoEnterEnabled(false)
                .build(),
        )
    }

    private fun android.content.Intent.documentationExtra(name: String): String? =
        if (BuildConfig.DOCUMENTATION_MODE) getStringExtra(name) else null

    private companion object {
        const val TEST_ALERT_POST_DELAY_MILLIS = 1_200L
        const val EXTRA_CAMERA_NAME = "app.opah.tv.extra.CAMERA_NAME"
        const val EXTRA_CAMERA_NAME_COMPAT = "camera"
        const val EXTRA_DOCUMENTATION_SCENARIO = "documentationScenario"
        const val EXTRA_DOCUMENTATION_DESTINATION = "documentationDestination"
        const val EXTRA_DOCUMENTATION_SETTINGS_PAGE = "documentationSettingsPage"
        const val EXTRA_DOCUMENTATION_INFORMATION_TAB = "documentationInformationTab"
        const val EXTRA_DOCUMENTATION_ACTIVITY_PAGE = "documentationActivityPage"
        const val EXTRA_DOCUMENTATION_CLIPS_PAGE = "documentationClipsPage"
    }
}

private enum class PendingNotificationAction {
    NONE,
    ENABLE_ALERTS,
    SEND_TEST_WITHOUT_IMAGE,
    SEND_TEST_WITH_IMAGE,
}

internal fun cameraNameFromLaunch(
    explicitCameraName: String?,
    action: String?,
    scheme: String?,
    host: String?,
    pathSegments: List<String>,
    compatibleCameraName: String? = null,
): String? {
    val candidate = explicitCameraName ?: compatibleCameraName ?: pathSegments.singleOrNull()?.takeIf {
        action == Intent.ACTION_VIEW &&
            scheme.equals("opah", ignoreCase = true) &&
            host.equals("live", ignoreCase = true)
    }
    return candidate
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_CAMERA_NAME_LENGTH && it.none(Char::isISOControl) }
}

private const val MAX_CAMERA_NAME_LENGTH = 256
