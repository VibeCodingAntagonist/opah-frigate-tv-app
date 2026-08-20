package app.opah.tv

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import app.opah.tv.ui.OpahApp
import app.opah.tv.ui.Phase0ViewModel

class MainActivity : ComponentActivity() {
    private val pipModeActive = mutableStateOf(false)
    private var fullyDrawnReported = false
    private val viewModel: Phase0ViewModel by viewModels {
        Phase0ViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val documentationScenario = intent.documentationExtra(EXTRA_DOCUMENTATION_SCENARIO)
        val documentationDestination = intent.documentationExtra(EXTRA_DOCUMENTATION_DESTINATION)
        val documentationSettingsPage = intent.documentationExtra(EXTRA_DOCUMENTATION_SETTINGS_PAGE)
        val documentationInformationTab = intent.documentationExtra(EXTRA_DOCUMENTATION_INFORMATION_TAB)
        if (BuildConfig.DOCUMENTATION_MODE) viewModel.setDocumentationScenario(documentationScenario)
        handleCameraLaunch(intent)
        setContent {
            OpahApp(
                viewModel = viewModel,
                pictureInPictureAvailable = supportsTelevisionPictureInPicture(),
                pictureInPictureActive = pipModeActive.value,
                onEnterPictureInPicture = ::requestLivePictureInPicture,
                onFullyDrawn = ::reportFullyDrawnOnce,
                onExitRequested = ::finish,
                initialDestinationName = documentationDestination,
                initialSettingsPageName = documentationSettingsPage,
                initialInformationTabName = documentationInformationTab,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCameraLaunch(intent)
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
        if (!isInPictureInPictureMode && Build.VERSION.SDK_INT >= 34) {
            disableAutomaticPictureInPictureEntry()
        }
    }

    private fun supportsTelevisionPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun requestLivePictureInPicture(request: PictureInPictureRequest): Boolean =
        if (Build.VERSION.SDK_INT >= 34) enterLivePictureInPicture(request) else false

    @RequiresApi(34)
    private fun enterLivePictureInPicture(request: PictureInPictureRequest): Boolean {
        if (!supportsTelevisionPictureInPicture() || pipModeActive.value) return false
        val builder = PictureInPictureParams.Builder()
            .setTitle(request.title)
            .setSubtitle(request.subtitle)
            .setSeamlessResizeEnabled(true)
            .setAutoEnterEnabled(true)
        request.aspectRatio?.let { builder.setAspectRatio(Rational(it.width, it.height)) }
        request.sourceRectHint?.takeUnless { it.isEmpty }?.let(builder::setSourceRectHint)
        val entered = runCatching { enterPictureInPictureMode(builder.build()) }.getOrDefault(false)
        if (!entered) disableAutomaticPictureInPictureEntry()
        return entered
    }

    @RequiresApi(34)
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
        const val EXTRA_CAMERA_NAME = "app.opah.tv.extra.CAMERA_NAME"
        const val EXTRA_CAMERA_NAME_COMPAT = "camera"
        const val EXTRA_DOCUMENTATION_SCENARIO = "documentationScenario"
        const val EXTRA_DOCUMENTATION_DESTINATION = "documentationDestination"
        const val EXTRA_DOCUMENTATION_SETTINGS_PAGE = "documentationSettingsPage"
        const val EXTRA_DOCUMENTATION_INFORMATION_TAB = "documentationInformationTab"
    }
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
