package app.opah.tv.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.BuildConfig
import app.opah.tv.R
import app.opah.tv.data.ConnectionProfileFactory
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CameraGroup
import app.opah.tv.data.model.CameraPtzInfo
import app.opah.tv.data.model.CameraStorageUsage
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.DiscoverySnapshot
import app.opah.tv.data.model.FrigateFeature
import app.opah.tv.data.model.FrigateMode
import app.opah.tv.data.model.FrigatePerformanceSummary
import app.opah.tv.data.network.PtzCommand
import app.opah.tv.data.network.PtzConnectionStatus
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.RecordingStorageSummary
import app.opah.tv.data.model.RecordingExport
import app.opah.tv.data.model.SavedCameraView
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.ThemeColorPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class InformationTab(val label: String) {
    CONNECTION("Connection"),
    CONFIG("Config"),
    PERFORMANCE("Performance"),
    STORAGE("Storage"),
}

internal fun informationTabInitialFocusRequester(
    candidate: InformationTab,
    selected: InformationTab,
    requester: FocusRequester?,
): FocusRequester? = requester?.takeIf { candidate == selected }
private const val SETUP_ADDRESS_INPUT = "setup:address"
private const val SETUP_USERNAME_INPUT = "setup:username"
private const val SETUP_PASSWORD_INPUT = "setup:password"
private const val SETUP_RTSP_HOST_INPUT = "setup:rtsp-host"
private const val SETUP_RTSP_PORT_INPUT = "setup:rtsp-port"
private const val SETTINGS_RTSP_HOST_INPUT = "settings:rtsp-host"
private const val SETTINGS_RTSP_PORT_INPUT = "settings:rtsp-port"
private const val MIN_CAMERA_GROUP_SIZE = 2
private const val MAX_CAMERA_GROUP_SIZE = 4
private val CAMERA_GROUP_CARD_WIDTH = 230.dp

internal const val OPAH_REPOSITORY_URL = "https://github.com/VibeCodingAntagonist/opah-frigate-tv-app"
internal const val OPAH_INDEPENDENCE_NOTICE =
    "Opah is an independent community project and is not affiliated with, sponsored by, " +
        "endorsed by, or supported by Frigate, Inc."
internal const val OPAH_TRADEMARK_NOTICE =
    "Frigate and Frigate NVR are trademarks of Frigate, Inc. Opah uses those names only to " +
        "explain what the app works with."
internal const val OPAH_PRIVACY_NOTICE =
    "Opah has no ads and does not track how you use the app. Your saved sign-in is encrypted " +
        "on this device."

internal fun aboutOpahText(versionName: String, connectedServerVersion: String?): String = buildString {
    append("Opah $versionName is an independent community TV app for viewing cameras and activity ")
    append("from the Frigate server you choose. ")
    appendLine(OPAH_PRIVACY_NOTICE)
    appendLine()
    append(OPAH_INDEPENDENCE_NOTICE)
    append(" Opah is not affiliated with, sponsored by, or endorsed by the Home Assistant project or the Open Home Foundation.")
    append(' ')
    appendLine(OPAH_TRADEMARK_NOTICE)
    appendLine()
    if (connectedServerVersion == null) {
        append("This TV is not currently connected to a Frigate server. ")
    } else {
        append("This TV is connected to Frigate $connectedServerVersion. ")
    }
    append("Source code, documentation, and support are available through the project link below. ")
    append("Opah is licensed under the Apache License 2.0 and is provided as-is, without warranty. ")
    append("Copyright © 2026 Opah contributors")
}

@Composable
internal fun StartupLoadingScreen(message: String) {
    val spinnerColor = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "startup loading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 850, easing = LinearEasing)),
        label = "loading spinner rotation",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.opah_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(116.dp),
            )
            Text(
                text = "Opah",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Canvas(
                modifier = Modifier
                    .size(48.dp)
                    .clearAndSetSemantics { contentDescription = "Loading" },
            ) {
                drawArc(
                    color = spinnerColor,
                    startAngle = rotation,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            Text(
                text = message.ifBlank { "Loading Opah…" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Please wait",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun StartupRecoveryScreen(
    message: String?,
    onRetry: () -> Unit,
    onConnectionSettings: () -> Unit,
) {
    val retryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        retryFocusRequester.requestFocus()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.opah_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(104.dp),
            )
            Text(
                text = "Can't reach Frigate",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Your saved sign-in is still on this TV. Check the server or network, then try again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            message?.let { ScreenMessage(it, isError = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocusRequester),
                ) { Text("Retry") }
                Button(onClick = onConnectionSettings) { Text("Connection settings") }
            }
        }
    }
}

@Composable
internal fun ConnectionSetupScreen(
    state: Phase0UiState,
    onDismissError: () -> Unit,
    onTestConnection: (String, String, String, String, String, Boolean) -> Unit,
    onConnect: (String, String, String, String, String, Boolean) -> Unit,
    onForget: () -> Unit,
    onExitRequested: () -> Unit,
) {
    val saved = state.savedProfile
    val savedUsername = saved?.username?.takeUnless {
        saved.withoutSignIn
    }.orEmpty()
    var serverUrl by rememberSaveable { mutableStateOf(saved?.apiBaseUrl.orEmpty()) }
    var useNoSignIn by rememberSaveable { mutableStateOf(saved?.withoutSignIn == true) }
    var username by rememberSaveable { mutableStateOf(savedUsername) }
    var password by remember { mutableStateOf("") }
    var advanced by rememberSaveable {
        mutableStateOf(saved?.let { it.rtspHostOverride != null || it.rtspPort != 8554 } ?: false)
    }
    var rtspHost by rememberSaveable { mutableStateOf(saved?.rtspHostOverride.orEmpty()) }
    var rtspPort by rememberSaveable { mutableStateOf((saved?.rtspPort ?: 8554).toString()) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    val isInternalPort = ConnectionProfileFactory.targetsDefaultInternalPort(serverUrl)
    val withoutSignIn = isInternalPort || useNoSignIn
    val lifecycleOwner = LocalLifecycleOwner.current
    val keepEditingFocusRequester = remember { FocusRequester() }
    val inputFocusCoordinator = remember { TvInputFocusCoordinator() }
    val testConnectionFocusRequester = remember { FocusRequester() }
    val accessChoiceFocusRequester = remember { FocusRequester() }
    val hasDraft = password.isNotEmpty() ||
        serverUrl != saved?.apiBaseUrl.orEmpty() ||
        username != savedUsername ||
        withoutSignIn != (saved?.withoutSignIn == true) ||
        rtspHost != saved?.rtspHostOverride.orEmpty() ||
        rtspPort != (saved?.rtspPort ?: 8554).toString()

    BackHandler(enabled = state.loading || hasDraft) {
        if (!state.loading) showExitConfirmation = true
    }
    LaunchedEffect(showExitConfirmation) {
        if (showExitConfirmation) {
            delay(50)
            keepEditingFocusRequester.requestFocus()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) password = ""
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            password = ""
        }
    }
    LaunchedEffect(saved) {
        saved ?: return@LaunchedEffect
        serverUrl = saved.apiBaseUrl
        useNoSignIn = saved.withoutSignIn
        username = saved.username.takeUnless { saved.withoutSignIn }.orEmpty()
        rtspHost = saved.rtspHostOverride.orEmpty()
        rtspPort = saved.rtspPort.toString()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(0.72f),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.opah_brand_mark),
                    contentDescription = null,
                    modifier = Modifier.size(104.dp),
                )
                Column {
                    Text(
                        text = "Opah",
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = "Security cameras, built for your TV",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Connect directly to your Frigate server. Your password is encrypted on this device after the first successful sign-in.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1.28f)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Connect to Frigate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            item {
                ProductionTvInput(
                    label = "Frigate address",
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        useNoSignIn = false
                        if (ConnectionProfileFactory.targetsDefaultInternalPort(it)) password = ""
                    },
                    placeholder = "https://frigate.example:8971",
                    enabled = !state.loading,
                    keyboardType = KeyboardType.Uri,
                    requestInitialFocus = true,
                    inputKey = SETUP_ADDRESS_INPUT,
                    focusCoordinator = inputFocusCoordinator,
                    nextFocusRequester = if (isInternalPort) testConnectionFocusRequester else accessChoiceFocusRequester,
                )
            }
            if (!isInternalPort) {
                item {
                    Button(
                        onClick = {
                            useNoSignIn = !useNoSignIn
                            if (useNoSignIn) password = ""
                        },
                        enabled = !state.loading,
                        modifier = Modifier.focusRequester(accessChoiceFocusRequester),
                    ) {
                        Text(stringResource(if (useNoSignIn) R.string.frigate_connect_without_sign_in_on
                            else R.string.frigate_connect_without_sign_in_off))
                    }
                }
            }
            if (withoutSignIn) {
                item {
                    ScreenMessage(
                        message = stringResource(R.string.frigate_without_sign_in_warning),
                        isError = false,
                    )
                }
            } else {
                item {
                    ProductionTvInput(
                        label = "Username",
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "Frigate user",
                        enabled = !state.loading,
                        inputKey = SETUP_USERNAME_INPUT,
                        focusCoordinator = inputFocusCoordinator,
                        previousInputKey = SETUP_ADDRESS_INPUT,
                        nextInputKey = SETUP_PASSWORD_INPUT,
                    )
                }
                item {
                    ProductionTvInput(
                        label = "Password",
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Saved securely after Connect",
                        enabled = !state.loading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardType = KeyboardType.Password,
                        imeAction = if (advanced) ImeAction.Next else ImeAction.Done,
                        inputKey = SETUP_PASSWORD_INPUT,
                        focusCoordinator = inputFocusCoordinator,
                        previousInputKey = SETUP_USERNAME_INPUT,
                        nextInputKey = if (advanced) SETUP_RTSP_HOST_INPUT else null,
                        nextFocusRequester = if (advanced) null else testConnectionFocusRequester,
                    )
                }
            }
            if (serverUrl.trim().startsWith("http://", ignoreCase = true)) {
                item {
                    ScreenMessage(
                        message = "HTTP is unencrypted • use HTTPS when available",
                        isError = false,
                    )
                }
            }
            item {
                Button(onClick = { advanced = !advanced }, enabled = !state.loading) {
                    Text(if (advanced) "Hide RTSP routing" else "RTSP routing")
                }
            }
            if (advanced) {
                item {
                    ProductionTvInput(
                        label = "RTSP host override (optional)",
                        value = rtspHost,
                        onValueChange = { rtspHost = it },
                        placeholder = "Defaults to the Frigate API host",
                        enabled = !state.loading,
                        inputKey = SETUP_RTSP_HOST_INPUT,
                        focusCoordinator = inputFocusCoordinator,
                        previousInputKey = if (withoutSignIn) SETUP_ADDRESS_INPUT else SETUP_PASSWORD_INPUT,
                        nextInputKey = SETUP_RTSP_PORT_INPUT,
                    )
                }
                item {
                    ProductionTvInput(
                        label = "RTSP port",
                        value = rtspPort,
                        onValueChange = { rtspPort = it.filter(Char::isDigit) },
                        placeholder = "8554",
                        enabled = !state.loading,
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                        inputKey = SETUP_RTSP_PORT_INPUT,
                        focusCoordinator = inputFocusCoordinator,
                        previousInputKey = SETUP_RTSP_HOST_INPUT,
                        nextFocusRequester = testConnectionFocusRequester,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = { onTestConnection(serverUrl, username, password, rtspHost, rtspPort, withoutSignIn) },
                        enabled = !state.loading,
                        modifier = Modifier.focusRequester(testConnectionFocusRequester),
                    ) { Text("Test connection") }
                    Button(
                        onClick = { onConnect(serverUrl, username, password, rtspHost, rtspPort, withoutSignIn) },
                        enabled = !state.loading,
                    ) { Text("Connect") }
                    if (saved != null) {
                        Button(onClick = onForget, enabled = !state.loading) { Text("Forget server") }
                    }
                }
            }
            item {
                Text(
                    text = state.statusMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.errorMessage?.let { message ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenMessage(message, isError = true)
                        Button(onClick = onDismissError) { Text("Dismiss") }
                    }
                }
            }
        }
    }

    if (showExitConfirmation) {
        Dialog(onDismissRequest = { showExitConfirmation = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 420.dp, max = 560.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        RoundedCornerShape(14.dp),
                    )
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Leave setup?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Your password will be cleared. Stay here if you want to finish connecting.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { showExitConfirmation = false },
                        modifier = Modifier.focusRequester(keepEditingFocusRequester),
                    ) { Text("Keep editing") }
                    Button(
                        onClick = {
                            password = ""
                            onExitRequested()
                        },
                    ) { Text("Leave Opah") }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroPreview(
    cameras: List<Camera>,
    previewState: HomePreviewState,
    previewUpdate: HomePreviewUpdate,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    initialCameraFocusRequester: FocusRequester,
    onMoveUp: (() -> Unit)?,
    onMoveDown: () -> Unit,
    onFocusKeyChanged: (String) -> Unit,
    onPlayCamera: (Camera, String) -> Unit,
    onOpenActions: (String) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val previewCameraName by previewState.cameraName
    val previewCamera = cameras.firstOrNull { it.name == previewCameraName } ?: cameras.first()
    val focusKey = "home:hero"
    MediaTile(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = { onPlayCamera(previewCamera, focusKey) },
        onLongClick = { onOpenActions(previewCamera.name) },
        accessibilityLabel = "Watch ${previewCamera.displayName}",
        externalFocusRequester = initialCameraFocusRequester,
        onFocused = {
            previewUpdate.job?.cancel()
            previewUpdate.pendingCameraName?.let { pending ->
                if (cameras.any { camera -> camera.name == pending }) previewState.cameraName.value = pending
            }
            onFocusKeyChanged(it)
        },
        modifier = modifier
            .then(
                onMoveUp?.let { action ->
                    Modifier.redirectDirectionalFocus(Key.DirectionUp, action)
                } ?: Modifier,
            )
            .redirectDirectionalFocus(Key.DirectionDown, onMoveDown),
    ) {
        CameraSnapshot(
            cameraName = previewCamera.name,
            cachedBitmap = { cachedBitmap(previewCamera.name) },
            refreshBitmap = { refreshBitmap(previewCamera.name, HOME_HERO_IMAGE_HEIGHT) },
            minimumCachedHeight = HOME_HERO_MINIMUM_CACHED_HEIGHT,
            contentScale = ContentScale.Fit,
            containerColor = Color.Black,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onFocusKeyChanged: (String) -> Unit,
    initialCameraFocusRequester: FocusRequester,
    onOpenReview: () -> Unit,
    onPlayBriefing: () -> Unit,
    onReviewBriefing: () -> Unit,
    onDismissBriefing: () -> Unit,
    onOpenCameras: (String) -> Unit,
    onOpenBirdseye: (String) -> Unit,
    onOpenCameraGroup: (String, List<String>, String) -> Unit,
    onStartMonitor: (String, List<String>, String) -> Unit,
    onToggleFavoriteCamera: (String) -> Unit,
    onMoveFavoriteCamera: (String, Int) -> Unit,
    onHideCamera: (String) -> Unit,
    onOpenCameraControls: (Camera) -> Unit,
    onToggleFavoriteView: (String) -> Unit,
    onMoveFavoriteView: (String, Int) -> Unit,
    onRestoreHomeDefaults: () -> Unit,
    onLoadModes: () -> Unit,
    onSwitchMode: (String?) -> Unit,
    onUndoMode: () -> Unit,
    onClearModeMessage: () -> Unit,
    onPlayCamera: (Camera, String) -> Unit,
    onPlayReview: (ReviewItem, String) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    cachedReviewBitmap: (ReviewItem) -> Bitmap?,
    refreshReviewBitmap: suspend (ReviewItem, Int) -> Bitmap?,
) {
    val snapshot = state.snapshot ?: return
    val cameras = orderedHomeCameras(
        cameras = snapshot.cameras,
        favoriteCameraNames = state.settings.favoriteCameraNames,
        hiddenCameraNames = state.settings.hiddenHomeCameraNames,
    )
    val alerts = homeNewAlerts(snapshot.recentReviewItems)
    val recentActivity = homeRecentActivity(snapshot.recentReviewItems)
    val previewState = rememberSaveable(cameras, saver = HomePreviewState.Saver) {
        HomePreviewState(cameras.firstOrNull()?.name)
    }
    val previewUpdateScope = rememberCoroutineScope()
    val previewUpdate = remember(cameras) { HomePreviewUpdate(previewState.cameraName.value) }
    val firstCameraFocusRequester = remember(cameras.firstOrNull()?.name) { FocusRequester() }
    val savedViews = state.settings.savedCameraViews.filter { view ->
        view.cameraNames.all { cameraName -> snapshot.cameras.any { it.name == cameraName } }
    }
    val frigateViews = availableHomeCameraGroups(snapshot.cameraGroups, snapshot.cameras)
    val views = buildList {
        savedViews.forEach { view ->
            add(
                HomeViewChoice(
                    key = "saved:${view.id}",
                    title = view.name,
                    detail = "${view.cameraNames.size} cameras",
                    cameraNames = view.cameraNames,
                ),
            )
        }
        frigateViews.forEach { group ->
            add(
                HomeViewChoice(
                    key = "group:${group.name}",
                    title = group.displayName,
                    detail = if (group.cameraNames.size <= MAX_CAMERA_GROUP_SIZE) {
                        "Frigate group • ${group.cameraNames.size} cameras"
                    } else {
                        "Frigate group • ${group.cameraNames.size} cameras • Choose up to four"
                    },
                    cameraNames = group.cameraNames,
                ),
            )
        }
        if (snapshot.birdseye.enabled) {
            add(HomeViewChoice("birdseye", "All Cameras", "Birdseye", emptyList(), birdseye = true))
        }
    }.let { choices -> orderedHomeViews(choices, state.settings.favoriteViewIds) }
    val hasViews = views.isNotEmpty()
    val firstViewFocusRequester = remember(views.firstOrNull()?.key) { FocusRequester() }
    var cameraActionsName by rememberSaveable { mutableStateOf<String?>(null) }
    var viewActionsKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraGroupChoice by remember { mutableStateOf<CameraGroupChoice?>(null) }
    var modeChooserVisible by rememberSaveable { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<PendingMode?>(null) }
    val modesAvailable = snapshot.capabilities.supports(FrigateFeature.PROFILE_MODES)
    val modeSwitchAvailable = snapshot.capabilities.supports(FrigateFeature.PROFILE_MODE_SWITCH)
    val viewportHeightDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp().value.toInt()
    }
    val homeHeroMaxHeight = homeHeroMaxHeightDp(viewportHeightDp).dp
    val homeListState = rememberLazyListState()
    val briefingFocusRequester = remember { FocusRequester() }
    val focusNavigationScope = rememberCoroutineScope()
    val leadingMessageItemCount = listOf(
        state.modes.errorMessage,
        state.modes.statusMessage,
        state.health.messages.takeIf(List<String>::isNotEmpty),
    ).count { it != null }
    val briefingItemCount = if (state.briefing.summary != null) 1 else 0
    val heroItemIndex = leadingMessageItemCount + briefingItemCount
    val cameraRowItemIndex = leadingMessageItemCount + briefingItemCount + 2
    val viewsRowItemIndex = leadingMessageItemCount + briefingItemCount + 4
    val focusFirstView = {
        if (hasViews) {
            focusNavigationScope.launch {
                scrollThenFocus(homeListState, viewsRowItemIndex, firstViewFocusRequester)
            }
        }
    }
    val focusFirstCamera = {
        if (cameras.isNotEmpty()) {
            focusNavigationScope.launch {
                scrollThenFocus(homeListState, cameraRowItemIndex, firstCameraFocusRequester)
            }
        }
    }
    val focusHero = {
        if (cameras.isNotEmpty()) {
            focusNavigationScope.launch {
                scrollThenFocus(homeListState, heroItemIndex, initialCameraFocusRequester)
            }
        }
    }
    val focusBriefing = state.briefing.summary?.let {
        {
            focusNavigationScope.launch {
                scrollThenFocus(homeListState, leadingMessageItemCount, briefingFocusRequester)
            }
            Unit
        }
    }
    LaunchedEffect(modesAvailable, state.modes.loadedOnce) {
        if (modesAvailable && !state.modes.loadedOnce) onLoadModes()
    }
    LaunchedEffect(state.modes.statusMessage) {
        if (state.modes.statusMessage != null) {
            delay(5_000)
            onClearModeMessage()
        }
    }
    LaunchedEffect(restoreFocusKey, hasViews, viewsRowItemIndex) {
        if (hasViews && restoreFocusKey?.startsWith("home:view:") == true) {
            homeListState.scrollToItem(viewsRowItemIndex)
        }
    }
    val briefingKey = state.briefing.summary?.entries?.joinToString(separator = ":") {
        it.item.id
    }
    LaunchedEffect(briefingKey) {
        if (briefingKey != null && homeListState.firstVisibleItemIndex <= heroItemIndex) {
            scrollThenFocus(
                homeListState,
                leadingMessageItemCount,
                briefingFocusRequester,
            )
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides KeepVisibleBringIntoViewSpec) {
        LazyColumn(
            state = homeListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = OpahDesignTokens.ScreenHorizontalMargin,
                vertical = OpahDesignTokens.ScreenVerticalMargin,
            ),
            verticalArrangement = Arrangement.spacedBy(OpahDesignTokens.SpaceMd),
        ) {
        state.modes.errorMessage?.let { message ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScreenMessage(message, isError = true, modifier = Modifier.weight(1f))
                    SecondaryAction(
                        focusKey = "home:mode-dismiss",
                        label = "Dismiss",
                        onClick = onClearModeMessage,
                    )
                }
            }
        }
        state.modes.statusMessage?.let { message ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScreenMessage(message, isError = false, modifier = Modifier.weight(1f))
                    if (state.modes.undoAvailable) {
                        SecondaryAction(
                            focusKey = "home:mode-undo",
                            label = "Undo",
                            onClick = onUndoMode,
                            enabled = !state.modes.switching,
                        )
                    }
                }
            }
        }
        if (state.health.messages.isNotEmpty()) {
            item(key = "home-health-exceptions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.health.messages.forEach { message ->
                        ScreenMessage(message, isError = true)
                    }
                }
            }
        }
        state.briefing.summary?.let { briefing ->
            item(key = "home-briefing") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                            RoundedCornerShape(OpahDesignTokens.MediaCornerRadius),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        briefing.headline,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        briefing.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryAction(
                            focusKey = "home:briefing-play",
                            label = "Play highlights",
                            onClick = onPlayBriefing,
                            enabled = !state.briefing.loading,
                            externalFocusRequester = briefingFocusRequester,
                            onFocused = { onFocusKeyChanged("home:briefing-play") },
                        )
                        SecondaryAction(
                            focusKey = "home:briefing-review",
                            label = "Open Activity",
                            onClick = onReviewBriefing,
                            enabled = !state.briefing.loading,
                            onFocused = { onFocusKeyChanged("home:briefing-review") },
                        )
                        SecondaryAction(
                            focusKey = "home:briefing-dismiss",
                            label = if (state.briefing.loading) "Saving" else "Dismiss",
                            onClick = onDismissBriefing,
                            enabled = !state.briefing.loading,
                            onFocused = { onFocusKeyChanged("home:briefing-dismiss") },
                        )
                    }
                    state.briefing.errorMessage?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (cameras.isNotEmpty()) {
            item(key = "home-preview") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HomeHeroPreview(
                        cameras = cameras,
                        previewState = previewState,
                        previewUpdate = previewUpdate,
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        initialCameraFocusRequester = initialCameraFocusRequester,
                        onMoveUp = focusBriefing,
                        onMoveDown = focusFirstCamera,
                        onFocusKeyChanged = onFocusKeyChanged,
                        onPlayCamera = onPlayCamera,
                        onOpenActions = { cameraActionsName = it },
                        cachedBitmap = cachedBitmap,
                        refreshBitmap = refreshBitmap,
                        modifier = Modifier
                            .widthIn(max = HOME_HERO_MAX_WIDTH)
                            .heightIn(max = homeHeroMaxHeight)
                            .aspectRatio(16f / 9f),
                    )
                    if (
                        modesAvailable &&
                        modeSwitchAvailable &&
                        state.modes.loadedOnce &&
                        state.modes.errorMessage == null
                    ) {
                        HomeModeAction(
                            activeMode = modeDisplayName(state.modes.activeMode, state.modes.modes),
                            enabled = !state.modes.switching,
                            onClick = { modeChooserVisible = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .focusProperties { down = initialCameraFocusRequester },
                        )
                    }
                }
            }
        }
        if (cameras.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("No cameras are shown on Home", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryAction(
                        focusKey = "home:restore-cameras",
                        label = "Restore cameras",
                        onClick = onRestoreHomeDefaults,
                    )
                }
            }
        }
        item(key = "home-cameras-title") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cameras", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SecondaryAction(
                    focusKey = "home:cameras:view-all",
                    label = "View all",
                    onClick = { onOpenCameras("home:cameras:view-all") },
                    onFocused = { onFocusKeyChanged("home:cameras:view-all") },
                )
            }
        }
        item(key = "home-camera-row") {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardWidth = (maxWidth - 48.dp) / 4
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 3.dp,
                        top = 5.dp,
                        end = 3.dp,
                        bottom = 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(cameras, key = { _, camera -> camera.name }) { index, camera ->
                        val focusKey = "home:camera:${camera.name}"
                        MediaTile(
                            focusKey = focusKey,
                            restoreFocusKey = restoreFocusKey,
                            onFocusRestored = onFocusRestored,
                            onClick = { onPlayCamera(camera, focusKey) },
                            onLongClick = { cameraActionsName = camera.name },
                            selected = previewState.cameraName.value == camera.name,
                            accessibilityLabel = "Watch ${camera.displayName}",
                            externalFocusRequester = firstCameraFocusRequester.takeIf { index == 0 },
                            onFocused = {
                                previewUpdate.pendingCameraName = camera.name
                                previewUpdate.job?.cancel()
                                if (previewState.cameraName.value != camera.name) {
                                    previewUpdate.job = previewUpdateScope.launch {
                                        delay(HOME_PREVIEW_SETTLE_MILLIS)
                                        previewState.cameraName.value = camera.name
                                    }
                                }
                                onFocusKeyChanged(it)
                            },
                            modifier = Modifier
                                .width(cardWidth)
                                .redirectDirectionalFocus(Key.DirectionUp, focusHero)
                                .redirectDirectionalFocus(Key.DirectionDown, focusFirstView),
                        ) {
                            Column {
                                CameraSnapshot(
                                    cameraName = camera.name,
                                    cachedBitmap = { cachedBitmap(camera.name) },
                                    refreshBitmap = { refreshBitmap(camera.name, 240) },
                                    refreshMillis = 30_000L,
                                    initialRefreshDelayMillis = index * CAMERA_REFRESH_STAGGER_MS,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                )
                                Text(
                                    camera.displayName,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (hasViews) {
            item(key = "home-views-title") {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Views and camera groups",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Start Monitor Mode to keep a View open and follow important activity",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item(key = "home-views-row") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 3.dp,
                        top = 5.dp,
                        end = 3.dp,
                        bottom = 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(views, key = { _, view -> view.key }) { index, view ->
                        val key = "home:view:${view.key}"
                        Column(
                            modifier = Modifier.width(270.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            HomeViewTile(
                                title = view.title,
                                detail = view.detail,
                                focusKey = key,
                                cameraName = view.cameraNames.firstOrNull() ?: cameras.firstOrNull()?.name,
                                onClick = {
                                    when {
                                        view.birdseye -> onOpenBirdseye(key)
                                        view.cameraNames.size <= MAX_CAMERA_GROUP_SIZE ->
                                            onOpenCameraGroup(view.title, view.cameraNames, key)
                                        else -> cameraGroupChoice = CameraGroupChoice(
                                            view.title,
                                            view.cameraNames.toSet(),
                                            key,
                                        )
                                    }
                                },
                                onLongClick = { viewActionsKey = view.key },
                                onFocused = onFocusKeyChanged,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = onFocusRestored,
                                externalFocusRequester = firstViewFocusRequester.takeIf { index == 0 },
                                onMoveUp = focusFirstCamera,
                                cachedBitmap = cachedBitmap,
                                refreshBitmap = refreshBitmap,
                            )
                            if (!view.birdseye) {
                                CameraAction(
                                    label = "Start Monitor Mode",
                                    focusKey = "$key:monitor",
                                    restoreFocusKey = restoreFocusKey,
                                    onFocusRestored = onFocusRestored,
                                    onClick = {
                                        if (view.cameraNames.size <= MAX_CAMERA_GROUP_SIZE) {
                                            onStartMonitor(view.title, view.cameraNames, "$key:monitor")
                                        } else {
                                            cameraGroupChoice = CameraGroupChoice(
                                                view.title,
                                                view.cameraNames.toSet(),
                                                "$key:monitor",
                                            )
                                        }
                                    },
                                    accessibilityLabel = "Start Monitor Mode with ${view.title}",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (alerts.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("New alerts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    SecondaryAction(
                        focusKey = "home:open-alerts",
                        label = "View all",
                        onClick = onOpenReview,
                    )
                }
            }
            item {
                ReviewRow(
                    prefix = "home:alert",
                    items = alerts,
                    restoreFocusKey = restoreFocusKey,
                    onFocusRestored = onFocusRestored,
                    onFocused = onFocusKeyChanged,
                    onPlay = onPlayReview,
                    cachedBitmap = cachedReviewBitmap,
                    refreshBitmap = refreshReviewBitmap,
                )
            }
        }
        if (recentActivity.isNotEmpty()) {
            item {
                Text("Recent activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item {
                ReviewRow(
                    prefix = "home:detection",
                    items = recentActivity,
                    restoreFocusKey = restoreFocusKey,
                    onFocusRestored = onFocusRestored,
                    onFocused = onFocusKeyChanged,
                    onPlay = onPlayReview,
                    cachedBitmap = cachedReviewBitmap,
                    refreshBitmap = refreshReviewBitmap,
                )
            }
        }
        if (shouldShowHomeRecentActivityEmptyState(state.recentActivityLoaded, alerts.size + recentActivity.size)) {
            item { ScreenMessage("No recent activity yet", isError = false) }
        }
        }
    }

    cameraGroupChoice?.let { choice ->
        CameraGroupChooserDialog(
            title = choice.title,
            cameras = snapshot.cameras.filter { it.name in choice.cameraNames },
            onDismiss = { cameraGroupChoice = null },
            onWatch = { selected ->
                cameraGroupChoice = null
                onOpenCameraGroup(choice.title, selected, choice.returnFocusKey)
            },
            onMonitor = { selected ->
                cameraGroupChoice = null
                onStartMonitor(choice.title, selected, choice.returnFocusKey)
            },
            onSave = null,
        )
    }
    cameraActionsName?.let { cameraName ->
        val camera = snapshot.cameras.firstOrNull { it.name == cameraName }
        if (camera == null) {
            cameraActionsName = null
        } else {
            val favoriteIndex = state.settings.favoriteCameraNames.indexOf(camera.name)
            HomeCameraActionsDialog(
                camera = camera,
                favorite = favoriteIndex >= 0,
                canMoveEarlier = favoriteIndex > 0,
                canMoveLater = favoriteIndex >= 0 && favoriteIndex < state.settings.favoriteCameraNames.lastIndex,
                hasControls = snapshot.ptzCameras[camera.name]?.hasControls == true,
                onToggleFavorite = {
                    onToggleFavoriteCamera(camera.name)
                    cameraActionsName = null
                },
                onMove = { direction ->
                    onMoveFavoriteCamera(camera.name, direction)
                    cameraActionsName = null
                },
                onHide = {
                    onHideCamera(camera.name)
                    cameraActionsName = null
                },
                onControls = {
                    cameraActionsName = null
                    onOpenCameraControls(camera)
                },
                onDismiss = { cameraActionsName = null },
            )
        }
    }
    viewActionsKey?.let { viewKey ->
        val favoriteIndex = state.settings.favoriteViewIds.indexOf(viewKey)
        HomeViewActionsDialog(
            title = views.firstOrNull { it.key == viewKey }?.title ?: "View",
            favorite = favoriteIndex >= 0,
            canMoveEarlier = favoriteIndex > 0,
            canMoveLater = favoriteIndex >= 0 && favoriteIndex < state.settings.favoriteViewIds.lastIndex,
            onToggleFavorite = {
                onToggleFavoriteView(viewKey)
                viewActionsKey = null
            },
            onMove = { direction ->
                onMoveFavoriteView(viewKey, direction)
                viewActionsKey = null
            },
            onDismiss = { viewActionsKey = null },
        )
    }
    if (modeChooserVisible) {
        ModeChooserDialog(
            modes = state.modes.modes,
            activeMode = state.modes.activeMode,
            onChoose = { name, displayName ->
                modeChooserVisible = false
                if (name != state.modes.activeMode) pendingMode = PendingMode(name, displayName)
            },
            onDismiss = { modeChooserVisible = false },
        )
    }
    pendingMode?.let { mode ->
        ConfirmModeDialog(
            mode = mode,
            switching = state.modes.switching,
            onConfirm = {
                pendingMode = null
                onSwitchMode(mode.name)
            },
            onDismiss = { pendingMode = null },
        )
    }
}

private data class PendingMode(val name: String?, val displayName: String)

@Composable
private fun ModeChooserDialog(
    modes: List<FrigateMode>,
    activeMode: String?,
    onChoose: (String?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(modifier = Modifier.width(520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose a Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Modes change Frigate behavior", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "default") {
                        ChoiceRow(
                            focusKey = "home:mode:default",
                            title = "Default",
                            selected = activeMode == null,
                            onClick = { onChoose(null, "Default") },
                        )
                    }
                    items(modes, key = FrigateMode::name) { mode ->
                        ChoiceRow(
                            focusKey = "home:mode:${mode.name}",
                            title = mode.displayName,
                            selected = activeMode == mode.name,
                            onClick = { onChoose(mode.name, mode.displayName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmModeDialog(
    mode: PendingMode,
    switching: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(modifier = Modifier.width(520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Switch to ${mode.displayName}?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("This changes the active Frigate Mode", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction(
                        focusKey = "home:mode-confirm",
                        label = if (switching) "Switching…" else "Switch Mode",
                        onClick = onConfirm,
                        enabled = !switching,
                    )
                    SecondaryAction(
                        focusKey = "home:mode-cancel",
                        label = "Cancel",
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

private data class HomeViewChoice(
    val key: String,
    val title: String,
    val detail: String,
    val cameraNames: List<String>,
    val birdseye: Boolean = false,
)

internal fun homeNewAlerts(items: List<ReviewItem>): List<ReviewItem> =
    items.asSequence()
        .filter { item -> item.severity == ReviewSeverity.ALERT && !item.hasBeenReviewed }
        .take(HOME_ROW_ITEM_LIMIT)
        .toList()

internal fun homeRecentActivity(items: List<ReviewItem>): List<ReviewItem> =
    items.asSequence()
        .filter { item ->
            item.severity == ReviewSeverity.DETECTION ||
                (item.severity == ReviewSeverity.ALERT && item.hasBeenReviewed)
        }
        .take(HOME_ROW_ITEM_LIMIT)
        .toList()

internal fun availableHomeCameraGroups(
    groups: List<CameraGroup>,
    cameras: List<Camera>,
): List<CameraGroup> {
    val availableCameraNames = cameras.mapTo(hashSetOf(), Camera::name)
    return groups.filter { group ->
        group.cameraNames.size >= MIN_CAMERA_GROUP_SIZE &&
            group.cameraNames.all(availableCameraNames::contains)
    }
}

internal fun orderedHomeCameras(
    cameras: List<Camera>,
    favoriteCameraNames: List<String>,
    hiddenCameraNames: Set<String>,
): List<Camera> {
    val favoriteRank = favoriteCameraNames.withIndex().associate { it.value to it.index }
    return cameras.filterNot { it.name in hiddenCameraNames }
        .sortedWith(compareBy<Camera> { favoriteRank[it.name] ?: Int.MAX_VALUE }.thenBy(Camera::order))
}

private fun orderedHomeViews(
    views: List<HomeViewChoice>,
    favoriteViewIds: List<String>,
): List<HomeViewChoice> {
    val favoriteRank = favoriteViewIds.withIndex().associate { it.value to it.index }
    return views.withIndex()
        .sortedWith(
            compareBy<IndexedValue<HomeViewChoice>> { favoriteRank[it.value.key] ?: Int.MAX_VALUE }
                .thenBy(IndexedValue<HomeViewChoice>::index),
        )
        .map(IndexedValue<HomeViewChoice>::value)
}

@Composable
private fun HomeViewTile(
    title: String,
    detail: String,
    focusKey: String,
    cameraName: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: (String) -> Unit,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    externalFocusRequester: FocusRequester?,
    onMoveUp: () -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    MediaTile(
        focusKey = focusKey,
        accessibilityLabel = "$title, $detail",
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        externalFocusRequester = externalFocusRequester,
        modifier = Modifier
            .width(270.dp)
            .redirectDirectionalFocus(Key.DirectionUp, onMoveUp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(108.dp)) {
            cameraName?.let { name ->
                CameraSnapshot(
                    cameraName = name,
                    cachedBitmap = { cachedBitmap(name) },
                    refreshBitmap = { refreshBitmap(name, 220) },
                    refreshMillis = 30_000L,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = OpahDesignTokens.MediaOverlayOpacity))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeModeAction(
    activeMode: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        focusKey = "home:mode",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        enabled = enabled,
        accessibilityLabel = "Change Mode, current Mode $activeMode",
        style = FocusableSurfaceStyle.SECONDARY_ACTION,
        modifier = modifier.size(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_mode),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun HomeCameraActionsDialog(
    camera: Camera,
    favorite: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    hasControls: Boolean,
    onToggleFavorite: () -> Unit,
    onMove: (Int) -> Unit,
    onHide: () -> Unit,
    onControls: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(modifier = Modifier.width(520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(camera.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Home options", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryAction(
                    focusKey = "home:camera-actions:favorite",
                    label = if (favorite) "Remove favorite" else "Pin as favorite",
                    onClick = onToggleFavorite,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (favorite) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryAction(
                            focusKey = "home:camera-actions:earlier",
                            label = "Move earlier",
                            onClick = { onMove(-1) },
                            enabled = canMoveEarlier,
                        )
                        SecondaryAction(
                            focusKey = "home:camera-actions:later",
                            label = "Move later",
                            onClick = { onMove(1) },
                            enabled = canMoveLater,
                        )
                    }
                }
                if (hasControls) {
                    SecondaryAction(
                        focusKey = "home:camera-actions:controls",
                        label = "Camera controls",
                        onClick = onControls,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SecondaryAction(
                    focusKey = "home:camera-actions:hide",
                    label = "Hide from Home",
                    onClick = onHide,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HomeViewActionsDialog(
    title: String,
    favorite: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onToggleFavorite: () -> Unit,
    onMove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(modifier = Modifier.width(480.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Home options", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryAction(
                    focusKey = "home:view-actions:favorite",
                    label = if (favorite) "Remove favorite" else "Pin as favorite",
                    onClick = onToggleFavorite,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (favorite) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryAction(
                            focusKey = "home:view-actions:earlier",
                            label = "Move earlier",
                            onClick = { onMove(-1) },
                            enabled = canMoveEarlier,
                        )
                        SecondaryAction(
                            focusKey = "home:view-actions:later",
                            label = "Move later",
                            onClick = { onMove(1) },
                            enabled = canMoveLater,
                        )
                    }
                }
            }
        }
    }
}

internal fun shouldShowHomeRecentActivityEmptyState(loaded: Boolean, itemCount: Int): Boolean =
    loaded && itemCount == 0

@Composable
internal fun CamerasScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onPlayCamera: (Camera, String) -> Unit,
    onOpenControls: (Camera) -> Unit,
    onRetryControls: () -> Unit,
    onCloseControls: () -> Unit,
    onPtzCommand: (PtzCommand) -> Unit,
    onOpenCameraGroup: (String, List<String>, String) -> Unit,
    onStartMonitor: (String, List<String>, String) -> Unit,
    onSaveCameraView: (String, List<String>) -> Unit,
    onDeleteCameraView: (String) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    val snapshot = state.snapshot ?: return
    val cameras = snapshot.cameras
    var chooser by remember { mutableStateOf<CameraGroupChoice?>(null) }
    var saveSelection by remember { mutableStateOf<List<String>?>(null) }
    var deleteView by remember { mutableStateOf<SavedCameraView?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Cameras", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "Watch one camera or several together",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (cameras.size >= MIN_CAMERA_GROUP_SIZE) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = "Camera groups",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Watch cameras together or start Monitor Mode to follow important activity",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            CameraGroupRow(
                cameras = cameras,
                frigateGroups = snapshot.cameraGroups,
                savedViews = state.settings.savedCameraViews,
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onChoose = { chooser = it },
                onOpen = onOpenCameraGroup,
                onMonitor = onStartMonitor,
                onDelete = { deleteView = it },
            )
        }
        Text(
            text = "All cameras",
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Box(modifier = Modifier.weight(1f)) {
            CameraGrid(
                cameras = cameras,
                ptzCameras = snapshot.ptzCameras,
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onPlayCamera = onPlayCamera,
                onOpenControls = onOpenControls,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
            )
        }
    }

    chooser?.let { choice ->
        CameraGroupChooserDialog(
            title = choice.title,
            cameras = cameras.filter { it.name in choice.cameraNames },
            onDismiss = { chooser = null },
            onWatch = { selected ->
                chooser = null
                onOpenCameraGroup(choice.title, selected, choice.returnFocusKey)
            },
            onMonitor = { selected ->
                chooser = null
                onStartMonitor(choice.title, selected, choice.returnFocusKey)
            },
            onSave = { selected ->
                chooser = null
                saveSelection = selected
            },
        )
    }
    saveSelection?.let { selected ->
        SaveCameraViewDialog(
            cameras = cameras,
            selectedCameraNames = selected,
            onDismiss = { saveSelection = null },
            onSave = { name ->
                onSaveCameraView(name, selected)
                saveSelection = null
                chooser = null
            },
        )
    }
    deleteView?.let { view ->
        DeleteCameraViewDialog(
            view = view,
            onDismiss = { deleteView = null },
            onDelete = {
                onDeleteCameraView(view.id)
                deleteView = null
            },
        )
    }

    val controlledCamera = state.ptz.cameraName?.let { cameraName ->
        cameras.firstOrNull { it.name == cameraName }
    }
    val ptzInfo = controlledCamera?.let { snapshot.ptzCameras[it.name] }
    if (controlledCamera != null && ptzInfo != null) {
        PtzControlsDialog(
            camera = controlledCamera,
            info = ptzInfo,
            state = state.ptz,
            onRetry = onRetryControls,
            onClose = onCloseControls,
            onCommand = onPtzCommand,
            cachedBitmap = cachedBitmap,
            refreshBitmap = refreshBitmap,
        )
    }
}

private data class CameraGroupChoice(
    val title: String,
    val cameraNames: Set<String>,
    val returnFocusKey: String,
)

@Composable
private fun CameraGroupRow(
    cameras: List<Camera>,
    frigateGroups: List<CameraGroup>,
    savedViews: List<SavedCameraView>,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onChoose: (CameraGroupChoice) -> Unit,
    onOpen: (String, List<String>, String) -> Unit,
    onMonitor: (String, List<String>, String) -> Unit,
    onDelete: (SavedCameraView) -> Unit,
) {
    val availableNames = cameras.mapTo(mutableSetOf(), Camera::name)
    val cameraLabels = cameras.associate { it.name to it.displayName }
    val availableSavedViews = savedViews.filter { view -> view.cameraNames.all { it in availableNames } }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "dual:create") {
            CameraGroupCard(
                focusKey = "dual:create",
                title = "Watch cameras together",
                subtitle = "Choose two to four",
                restoreFocusKey = restoreFocusKey,
                onFocusRestored = onFocusRestored,
                onClick = {
                    onChoose(CameraGroupChoice("Camera group", availableNames, "dual:create"))
                },
            )
        }
        items(availableSavedViews, key = { "dual:saved:${it.id}" }) { view ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CameraGroupCard(
                    focusKey = "dual:saved:${view.id}",
                    title = view.name,
                    subtitle = view.cameraNames.joinToString(" • ") { cameraLabels[it] ?: it },
                    restoreFocusKey = restoreFocusKey,
                    onFocusRestored = onFocusRestored,
                    onClick = { onOpen(view.name, view.cameraNames, "dual:saved:${view.id}") },
                )
                Row(
                    modifier = Modifier.width(CAMERA_GROUP_CARD_WIDTH),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CameraAction(
                        label = "Monitor Mode",
                        focusKey = "dual:monitor:${view.id}",
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        onClick = {
                            onMonitor(view.name, view.cameraNames, "dual:monitor:${view.id}")
                        },
                        accessibilityLabel = "Start Monitor Mode with ${view.name}",
                        modifier = Modifier.weight(1f),
                    )
                    CameraAction(
                        label = "Remove",
                        focusKey = "dual:remove:${view.id}",
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        onClick = { onDelete(view) },
                        accessibilityLabel = "Remove ${view.name}",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        items(frigateGroups, key = { "dual:frigate:${it.name}" }) { group ->
            val names = group.cameraNames.filter { it in availableNames }
            if (names.size >= MIN_CAMERA_GROUP_SIZE) {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    CameraGroupCard(
                        focusKey = "dual:frigate:${group.name}",
                        title = group.displayName,
                        subtitle = if (names.size <= MAX_CAMERA_GROUP_SIZE) {
                            names.joinToString(" • ") { cameraLabels[it] ?: it }
                        } else {
                            "${names.size} cameras • Choose up to four"
                        },
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        onClick = {
                            if (names.size <= MAX_CAMERA_GROUP_SIZE) {
                                onOpen(group.displayName, names, "dual:frigate:${group.name}")
                            } else {
                                onChoose(
                                    CameraGroupChoice(
                                        group.displayName,
                                        names.toSet(),
                                        "dual:frigate:${group.name}",
                                    ),
                                )
                            }
                        },
                    )
                    CameraAction(
                        label = "Start Monitor Mode",
                        focusKey = "dual:monitor:${group.name}",
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        onClick = {
                            if (names.size <= MAX_CAMERA_GROUP_SIZE) {
                                onMonitor(
                                    group.displayName,
                                    names,
                                    "dual:monitor:${group.name}",
                                )
                            } else {
                                onChoose(
                                    CameraGroupChoice(
                                        group.displayName,
                                        names.toSet(),
                                        "dual:monitor:${group.name}",
                                    ),
                                )
                            }
                        },
                        accessibilityLabel = "Start Monitor Mode with ${group.displayName}",
                        modifier = Modifier.width(CAMERA_GROUP_CARD_WIDTH),
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraGroupCard(
    focusKey: String,
    title: String,
    subtitle: String,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onClick: () -> Unit,
) {
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onClick,
        accessibilityLabel = "$title, $subtitle",
        modifier = Modifier
            .width(CAMERA_GROUP_CARD_WIDTH)
            .height(72.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CameraGroupChooserDialog(
    title: String,
    cameras: List<Camera>,
    onDismiss: () -> Unit,
    onWatch: (List<String>) -> Unit,
    onMonitor: (List<String>) -> Unit,
    onSave: ((List<String>) -> Unit)?,
) {
    var selected by remember(title, cameras) { mutableStateOf(emptyList<String>()) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .fillMaxHeight(0.84f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "Choose two to four cameras • ${selected.size} selected",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(cameras, key = Camera::name) { camera ->
                    val isSelected = camera.name in selected
                    FocusCard(
                        focusKey = "dual:choose:${camera.name}",
                        restoreFocusKey = null,
                        onFocusRestored = {},
                        onClick = { selected = toggleCameraGroupSelection(selected, camera.name) },
                        selected = isSelected,
                        accessibilityLabel = if (isSelected) {
                            "${camera.displayName}, selected"
                        } else {
                            "${camera.displayName}, not selected"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(camera.displayName, fontWeight = FontWeight.Bold)
                            if (isSelected) {
                                Text("Selected", color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onWatch(selected) },
                    enabled = selected.size in MIN_CAMERA_GROUP_SIZE..MAX_CAMERA_GROUP_SIZE,
                ) { Text("Watch") }
                Button(
                    onClick = { onMonitor(selected) },
                    enabled = selected.size in MIN_CAMERA_GROUP_SIZE..MAX_CAMERA_GROUP_SIZE,
                ) { Text("Start Monitor Mode") }
                onSave?.let { save ->
                    Button(
                        onClick = { save(selected) },
                        enabled = selected.size in MIN_CAMERA_GROUP_SIZE..MAX_CAMERA_GROUP_SIZE,
                    ) { Text("Save view") }
                }
            }
        }
    }
}

internal fun toggleCameraGroupSelection(selected: List<String>, cameraName: String): List<String> = when {
    cameraName in selected -> selected - cameraName
    selected.size < MAX_CAMERA_GROUP_SIZE -> selected + cameraName
    else -> selected
}

@Composable
private fun SaveCameraViewDialog(
    cameras: List<Camera>,
    selectedCameraNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val labels = cameras.associate { it.name to it.displayName }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Save camera view", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                selectedCameraNames.joinToString(" • ") { labels[it] ?: it },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProductionTvInput(
                label = "View name",
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = "For example, Outside",
                enabled = true,
                imeAction = ImeAction.Done,
                requestInitialFocus = true,
                inputKey = "dual:view-name",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
            }
        }
    }
}

@Composable
private fun DeleteCameraViewDialog(
    view: SavedCameraView,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Remove ${view.name}?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("The cameras will not be changed", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDismiss) { Text("Keep") }
                Button(onClick = onDelete) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun CameraGrid(
    cameras: List<Camera>,
    ptzCameras: Map<String, CameraPtzInfo>,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onPlayCamera: (Camera, String) -> Unit,
    onOpenControls: (Camera) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cardWidth = (maxWidth - 76.dp) / 3
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(cameras, key = { _, camera -> camera.name }) { index, camera ->
                Column(
                    modifier = Modifier.width(cardWidth),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val automaticKey = "cameras:auto:${camera.name}"
                    CameraCard(
                        camera = camera,
                        focusKey = automaticKey,
                        restoreFocusKey = restoreFocusKey,
                        onFocusRestored = onFocusRestored,
                        onClick = { onPlayCamera(camera, automaticKey) },
                        cachedBitmap = cachedBitmap,
                        refreshBitmap = refreshBitmap,
                        initialRefreshDelayMillis = index * CAMERA_REFRESH_STAGGER_MS,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ptzCameras[camera.name]?.hasControls == true) {
                        CameraAction(
                            label = "Controls",
                            focusKey = "cameras:controls:${camera.name}",
                            restoreFocusKey = restoreFocusKey,
                            onFocusRestored = onFocusRestored,
                            onClick = { onOpenControls(camera) },
                            accessibilityLabel = "Control ${camera.displayName}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraAction(
    label: String,
    focusKey: String,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onClick: () -> Unit,
    accessibilityLabel: String,
    modifier: Modifier,
) {
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onClick,
        accessibilityLabel = accessibilityLabel,
        modifier = modifier,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PtzControlsDialog(
    camera: Camera,
    info: CameraPtzInfo,
    state: PtzUiState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onCommand: (PtzCommand) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val directControlFocusRequester = remember(camera.name) { FocusRequester() }
    val shelfFocusRequester = remember(camera.name) { FocusRequester() }
    var directControlFocused by remember(camera.name) { mutableStateOf(false) }
    var movingCommand by remember(camera.name) { mutableStateOf<PtzCommand?>(null) }
    val connected = state.connection.status == PtzConnectionStatus.CONNECTED
    fun stopMovement() {
        if (movingCommand != null) {
            movingCommand = null
            onCommand(PtzCommand.Stop)
        }
    }
    BackHandler {
        stopMovement()
        onClose()
    }
    DisposableEffect(lifecycleOwner, camera.name, connected) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && connected) onCommand(PtzCommand.Stop)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (connected) onCommand(PtzCommand.Stop)
        }
    }
    LaunchedEffect(camera.name, connected) {
        if (connected) {
            withFrameNanos { }
            directControlFocusRequester.requestFocus()
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))
                .focusRequester(directControlFocusRequester)
                .onFocusChanged { directControlFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (!connected || !directControlFocused || !info.canMove) return@onPreviewKeyEvent false
                    val command = when (event.key) {
                        Key.DirectionUp -> PtzCommand.MoveUp
                        Key.DirectionDown -> PtzCommand.MoveDown
                        Key.DirectionLeft -> PtzCommand.MoveLeft
                        Key.DirectionRight -> PtzCommand.MoveRight
                        else -> null
                    }
                    if (command != null) {
                        when (event.type) {
                            KeyEventType.KeyDown -> if (movingCommand == null) {
                                movingCommand = command
                                onCommand(command)
                            }
                            KeyEventType.KeyUp -> stopMovement()
                            else -> Unit
                        }
                        true
                    } else if (
                        event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter)
                    ) {
                        if (movingCommand != null) stopMovement() else shelfFocusRequester.requestFocus()
                        true
                    } else {
                        event.key == Key.DirectionCenter || event.key == Key.Enter
                    }
                }
                .semantics {
                    contentDescription =
                        "Camera control. Arrows move. Center stops. Press center again for zoom, focus, and presets. Back exits."
                }
                .focusable(enabled = connected && info.canMove)
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 1180.dp),
                horizontalArrangement = Arrangement.spacedBy(30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1.45f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(camera.displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    CameraSnapshot(
                        cameraName = camera.name,
                        cachedBitmap = { cachedBitmap(camera.name) },
                        refreshBitmap = { refreshBitmap(camera.name, 540) },
                        refreshMillis = 1_000L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Text(
                        "Arrows move • Center stops • Back exits",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (state.connection.status) {
                        PtzConnectionStatus.CONNECTING, PtzConnectionStatus.DISCONNECTED -> {
                            Text("Getting camera controls ready…")
                            Button(onClick = onClose) { Text("Close") }
                        }
                        PtzConnectionStatus.FAILED -> {
                            Text(state.connection.message ?: "Camera controls could not connect")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onRetry) { Text("Try again") }
                                Button(onClick = onClose) { Text("Close") }
                            }
                        }
                        PtzConnectionStatus.CONNECTED -> {
                            PtzConnectedControls(
                                info,
                                state.errorMessage,
                                shelfFocusRequester,
                                onCommand,
                                onClose,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PtzConnectedControls(
    info: CameraPtzInfo,
    errorMessage: String?,
    initialFocusRequester: FocusRequester,
    onCommand: (PtzCommand) -> Unit,
    onClose: () -> Unit,
) {
    Text("Camera controls", fontWeight = FontWeight.Bold)
    Button(
        onClick = { onCommand(PtzCommand.Stop) },
        modifier = Modifier.focusRequester(initialFocusRequester),
    ) { Text("Stop") }
    if (info.canMove) {
        PtzHoldButton("Up", "↑", PtzCommand.MoveUp, onCommand)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PtzHoldButton("Left", "←", PtzCommand.MoveLeft, onCommand)
            PtzHoldButton("Right", "→", PtzCommand.MoveRight, onCommand)
        }
        PtzHoldButton("Down", "↓", PtzCommand.MoveDown, onCommand)
    }
    if (info.canZoom) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PtzHoldButton("Zoom out", "Zoom −", PtzCommand.ZoomOut, onCommand)
            PtzHoldButton("Zoom in", "Zoom +", PtzCommand.ZoomIn, onCommand)
        }
    }
    if (info.canFocus) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PtzHoldButton("Focus nearer", "Focus −", PtzCommand.FocusOut, onCommand)
            PtzHoldButton("Focus farther", "Focus +", PtzCommand.FocusIn, onCommand)
        }
    }
    if (info.presets.isNotEmpty()) {
        Text("Saved positions", fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(info.presets, key = { "preset:$it" }) { preset ->
                Button(onClick = { onCommand(PtzCommand.Preset(preset)) }) {
                    Text(preset.replace('_', ' ').replaceFirstChar(Char::uppercase))
                }
            }
        }
    }
    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = onClose) { Text("Close") }
}

@Composable
private fun PtzHoldButton(
    accessibilityLabel: String,
    label: String,
    command: PtzCommand,
    onCommand: (PtzCommand) -> Unit,
) {
    var held by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose {
            if (held) onCommand(PtzCommand.Stop)
        }
    }
    Button(
        onClick = {
            scope.launch {
                onCommand(command)
                delay(PTZ_STEP_MILLIS)
                onCommand(PtzCommand.Stop)
            }
        },
        modifier = Modifier
            .onFocusChanged { focus ->
                if (!focus.isFocused && held) {
                    held = false
                    onCommand(PtzCommand.Stop)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionCenter && event.key != Key.Enter) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (!held) {
                        held = true
                        onCommand(command)
                    }
                    KeyEventType.KeyUp -> if (held) {
                        held = false
                        onCommand(PtzCommand.Stop)
                    }
                    else -> Unit
                }
                true
            }
            .semantics { contentDescription = accessibilityLabel },
    ) {
        Text(label)
    }
}

@Composable
private fun CameraCard(
    camera: Camera,
    focusKey: String,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onClick: () -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    initialRefreshDelayMillis: Long = 0L,
    externalFocusRequester: FocusRequester? = null,
    onFocused: (String) -> Unit = {},
    modifier: Modifier,
) {
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onClick,
        accessibilityLabel = "Watch ${camera.displayName}",
        externalFocusRequester = externalFocusRequester,
        onFocused = onFocused,
        modifier = modifier,
    ) {
        Column {
            CameraSnapshot(
                cameraName = camera.name,
                cachedBitmap = { cachedBitmap(camera.name) },
                refreshBitmap = { refreshBitmap(camera.name, 360) },
                initialRefreshDelayMillis = initialRefreshDelayMillis,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(camera.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Watch", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val CAMERA_REFRESH_STAGGER_MS = 140L
private const val HOME_PREVIEW_SETTLE_MILLIS = 300L
private const val HOME_HERO_IMAGE_HEIGHT = 720
private const val HOME_HERO_MINIMUM_CACHED_HEIGHT = 600
private val HOME_HERO_MAX_WIDTH = 680.dp
private const val HOME_INITIAL_SHELF_RESERVE_DP = 236
private const val HOME_ROW_ITEM_LIMIT = 8
private const val PTZ_STEP_MILLIS = 350L
private val KeepVisibleBringIntoViewSpec = object : BringIntoViewSpec {}

internal fun homeHeroMaxHeightDp(viewportHeightDp: Int): Int =
    (viewportHeightDp - HOME_INITIAL_SHELF_RESERVE_DP).coerceIn(240, 360)

private fun Modifier.redirectDirectionalFocus(direction: Key, onMove: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.key != direction) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) onMove()
                true
            }
            KeyEventType.KeyUp -> true
            else -> false
        }
    }

private suspend fun scrollThenFocus(
    listState: LazyListState,
    itemIndex: Int,
    focusRequester: FocusRequester,
) {
    listState.scrollToItem(itemIndex)
    repeat(8) {
        withFrameNanos { }
        if (runCatching(focusRequester::requestFocus).getOrDefault(false)) {
            withFrameNanos { }
            listState.scrollToItem(itemIndex)
            return
        }
    }
}

private class HomePreviewUpdate(initialCameraName: String?) {
    var pendingCameraName: String? = initialCameraName
    var job: Job? = null
}

private class HomePreviewState(initialCameraName: String?) {
    val cameraName = mutableStateOf(initialCameraName)

    companion object {
        val Saver = Saver<HomePreviewState, String>(
            save = { state -> state.cameraName.value.orEmpty() },
            restore = { saved -> HomePreviewState(saved.takeIf(String::isNotEmpty)) },
        )
    }
}

@Composable
private fun ReviewRow(
    prefix: String,
    items: List<ReviewItem>,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onFocused: (String) -> Unit = {},
    onPlay: (ReviewItem, String) -> Unit,
    cachedBitmap: (ReviewItem) -> Bitmap?,
    refreshBitmap: suspend (ReviewItem, Int) -> Bitmap?,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 36.dp) / 3
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items, key = ReviewItem::id) { item ->
                val focusKey = "$prefix:${item.id}"
                FocusCard(
                    focusKey = focusKey,
                    restoreFocusKey = restoreFocusKey,
                    onFocusRestored = onFocusRestored,
                    onFocused = onFocused,
                    onClick = { onPlay(item, focusKey) },
                    enabled = item.recordingAvailable != false,
                    accessibilityLabel = "${item.severity.name.lowercase()} at ${item.camera.replace('_', ' ')}",
                    modifier = Modifier.width(cardWidth),
                ) {
                    Column {
                        ReviewThumbnail(
                            item = item,
                            imageRevision = 0L,
                            cachedBitmap = { cachedBitmap(item) },
                            refreshBitmap = { refreshBitmap(item, 240) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = item.camera.replace('_', ' ').replaceFirstChar(Char::uppercase),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = formatReviewTime(item.startTime),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val description = (item.objects + item.zones).distinct().joinToString(" • ")
                            if (description.isNotBlank()) {
                                Text(
                                    text = description,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    state: Phase0UiState,
    onAppearance: (AppearanceMode) -> Unit,
    onCustomTheme: (CustomThemeColors) -> Unit,
    onStreamPreference: (StreamPreference) -> Unit,
    onPreferRtpTcp: (Boolean) -> Unit,
    onStartMuted: (Boolean) -> Unit,
    onDiagnostics: (Boolean) -> Unit,
    onUpdateRtspRoute: (String, String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    diagnosticsFocusRequester: FocusRequester,
    restoreDiagnosticsFocus: Boolean,
    onDiagnosticsFocusRestored: () -> Unit,
    onSignOut: () -> Unit,
    onForgetServer: () -> Unit,
) {
    val profile = state.activeProfile ?: return
    var rtspHost by rememberSaveable(profile) { mutableStateOf(profile.rtspHostOverride.orEmpty()) }
    var rtspPort by rememberSaveable(profile) { mutableStateOf(profile.rtspPort.toString()) }
    val inputFocusCoordinator = remember { TvInputFocusCoordinator() }
    val saveRtspFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val customThemeVisible = state.settings.appearanceMode == AppearanceMode.CUSTOM
    val supportIndex = if (customThemeVisible) 5 else 4
    LaunchedEffect(restoreDiagnosticsFocus, supportIndex) {
        if (!restoreDiagnosticsFocus) return@LaunchedEffect
        listState.scrollToItem(supportIndex)
        for (attempt in 0 until 8) {
            // LazyColumn may need more than one frame to compose the Support item
            // after returning from the separate Diagnostics page.
            withFrameNanos { }
            if (diagnosticsFocusRequester.requestFocus()) {
                onDiagnosticsFocusRestored()
                break
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Playback defaults and this TV’s Frigate connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SettingsSection("Appearance", "Follow the TV, use a fixed theme, or create your own") {
                ChoiceRow(
                    values = AppearanceMode.entries,
                    selected = state.settings.appearanceMode,
                    label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    keyPrefix = "settings:appearance",
                    onSelect = onAppearance,
                )
            }
        }
        if (customThemeVisible) {
            item {
                SettingsSection(
                    "Custom colors",
                    "Adjust with the D-pad. Opah automatically protects text and focus contrast.",
                ) {
                    ThemeColorEditor(
                        colors = state.settings.customThemeColors,
                        onChange = onCustomTheme,
                    )
                }
            }
        }
        item {
            SettingsSection("Default live video", "Opah uses this choice whenever you open a camera") {
                ChoiceRow(
                    values = StreamPreference.entries,
                    selected = state.settings.streamPreference,
                    label = {
                        when (it) {
                            StreamPreference.AUTOMATIC -> "Automatic"
                            StreamPreference.MAIN -> "Main"
                            StreamPreference.LOW_BANDWIDTH -> "Low bandwidth"
                        }
                    },
                    keyPrefix = "settings:stream",
                    onSelect = onStreamPreference,
                )
            }
        }
        item {
            SettingsSection("Live playback", "Defaults apply the next time a stream opens") {
                SettingToggle("Prefer RTP over TCP", state.settings.preferRtpTcp, onPreferRtpTcp)
                SettingToggle("Start live video muted", state.settings.startLiveMuted, onStartMuted)
                SettingToggle("Show playback Info control", state.settings.diagnosticsEnabled, onDiagnostics)
            }
        }
        item {
            SettingsSection("Support", "Inspect the current connection, camera streams, and TV decoders") {
                FocusCard(
                    focusKey = "settings:diagnostics",
                    restoreFocusKey = null,
                    onFocusRestored = {},
                    onClick = onOpenDiagnostics,
                    selected = false,
                    accessibilityLabel = "Open diagnostics",
                    externalFocusRequester = diagnosticsFocusRequester,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Diagnostics", fontWeight = FontWeight.Bold)
                            Text(
                                "Connection, authorization, streams, and decoder details",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("Open", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        item {
            SettingsSection("Frigate account", "API authentication and RTSP routing are managed separately") {
                ReadOnlyValue("Server", profile.apiBaseUrl)
                ReadOnlyValue("Username", profile.username)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSignOut) { Text("Sign out") }
                    Button(onClick = onForgetServer) { Text("Forget server and password") }
                }
            }
        }
        item {
            SettingsSection("RTSP routing", "Change the live-stream route without signing in again") {
                ProductionTvInput(
                    label = "RTSP host override (optional)",
                    value = rtspHost,
                    onValueChange = { rtspHost = it },
                    placeholder = "Defaults to the Frigate API host",
                    enabled = true,
                    inputKey = SETTINGS_RTSP_HOST_INPUT,
                    focusCoordinator = inputFocusCoordinator,
                    nextInputKey = SETTINGS_RTSP_PORT_INPUT,
                )
                ProductionTvInput(
                    label = "RTSP port",
                    value = rtspPort,
                    onValueChange = { rtspPort = it.filter(Char::isDigit) },
                    placeholder = "8554",
                    enabled = true,
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    inputKey = SETTINGS_RTSP_PORT_INPUT,
                    focusCoordinator = inputFocusCoordinator,
                    previousInputKey = SETTINGS_RTSP_HOST_INPUT,
                    nextFocusRequester = saveRtspFocusRequester,
                )
                Button(
                    onClick = { onUpdateRtspRoute(rtspHost, rtspPort) },
                    modifier = Modifier.focusRequester(saveRtspFocusRequester),
                ) { Text("Save RTSP route") }
            }
        }
    }
}

@Composable
internal fun AboutScreen(
    connectedServerVersion: String? = null,
    onBack: (() -> Unit)? = null,
    initialFocusRequester: FocusRequester? = null,
) {
    val uriHandler = LocalUriHandler.current
    var repositoryButtonFocused by remember { mutableStateOf(false) }
    var repositoryButtonArmed by remember { mutableStateOf(false) }
    LaunchedEffect(repositoryButtonFocused) {
        repositoryButtonArmed = false
        if (repositoryButtonFocused) {
            // Prevent the D-pad release that selected About from activating the
            // first actionable control as focus moves into the new destination.
            delay(400)
            repositoryButtonArmed = true
        }
    }
    FlatSettingsDetailColumn(
        title = "About Opah",
        subtitle = "Independent community TV app",
        onBack = onBack,
    ) {
        item(key = "about:text") {
            Text(
                aboutOpahText(installedAppVersionLabel(), connectedServerVersion),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "about:open-project") {
            FlatSettingsRow(
                focusKey = "settings:about:open-project",
                title = "Open project on GitHub",
                value = null,
                enabled = repositoryButtonArmed,
                focusable = true,
                externalFocusRequester = initialFocusRequester,
                onFocusStateChanged = { repositoryButtonFocused = it },
                onClick = { runCatching { uriHandler.openUri(OPAH_REPOSITORY_URL) } },
            )
        }
    }
}

@Composable
internal fun DiagnosticsScreen(
    state: Phase0UiState,
    onRefresh: () -> Unit,
    onBack: (() -> Unit)? = null,
    initialFocusRequester: FocusRequester? = null,
) {
    val snapshot = state.snapshot ?: return
    val device = state.device
    FlatSettingsDetailColumn(
        title = "Device Info",
        subtitle = "TV and decoder details for troubleshooting",
        onBack = onBack,
    ) {
        item(key = "diagnostics-refresh") {
            FlatSettingsRow(
                focusKey = "settings:advanced:refresh",
                title = if (state.loading) "Refreshing…" else "Refresh device data",
                value = null,
                enabled = !state.loading,
                focusable = true,
                externalFocusRequester = initialFocusRequester,
                onClick = onRefresh,
            )
        }
        item(key = "diagnostics-device") {
            SettingsSection(
                "Device",
                isFocusable = true,
            ) {
                if (device == null) {
                    Text("Device inspection is still loading", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ReadOnlyValue("Model", "${device.manufacturer} ${device.model}")
                    ReadOnlyValue("Android", "${device.androidRelease} (API ${device.apiLevel})")
                    device.codecs.forEach { codec ->
                        val decoders = codec.decoders.joinToString { decoder ->
                            buildString {
                                append(decoder.name)
                                if (decoder.hardwareAccelerated == true) append(" [hardware]")
                                else if (decoder.softwareOnly == true) append(" [software]")
                            }
                        }.ifBlank { "No advertised decoder" }
                        ReadOnlyValue(codec.label, decoders)
                    }
                }
            }
        }
        if (snapshot.warnings.isNotEmpty()) {
            item(key = "diagnostics-warnings") {
                SettingsSection("Warnings", isFocusable = true) {
                    snapshot.warnings.forEach { warning -> ScreenMessage(warning, isError = false) }
                }
            }
        }
    }
}

@Composable
internal fun InformationScreen(
    state: Phase0UiState,
    onLoad: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateLiveRoute: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onForgetServer: () -> Unit,
    onBack: (() -> Unit)? = null,
    initialFocusRequester: FocusRequester? = null,
    initialTabName: String? = null,
) {
    val information = state.information
    val summary = information.summary
    var tabName by rememberSaveable(initialTabName) {
        mutableStateOf(initialTabName ?: InformationTab.PERFORMANCE.name)
    }
    val tab = runCatching { InformationTab.valueOf(tabName) }.getOrDefault(InformationTab.PERFORMANCE)
    val profile = state.activeProfile
    var liveHost by rememberSaveable(profile) { mutableStateOf(profile?.rtspHostOverride.orEmpty()) }
    var livePort by rememberSaveable(profile) { mutableStateOf(profile?.rtspPort?.toString() ?: "8554") }
    val inputFocusCoordinator = remember { TvInputFocusCoordinator() }
    val saveFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.activeProfile, tab) {
        if (tab != InformationTab.CONNECTION) onLoad()
    }
    FlatSettingsDetailColumn(
        title = "Server",
        subtitle = "Connection, configuration, performance, and recording space",
        onBack = onBack,
    ) {
        if (tab != InformationTab.CONNECTION) {
            item(key = "information-refresh") {
                FlatSettingsRow(
                    focusKey = "settings:server:refresh",
                    title = if (information.loading) "Refreshing…" else "Refresh server data",
                    value = null,
                    enabled = !information.loading,
                    focusable = true,
                    onClick = onRefresh,
                )
            }
        }
        item(key = "information-tabs") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InformationTab.entries.forEach { item ->
                    FocusCard(
                        focusKey = "information:tab:${item.name.lowercase()}",
                        restoreFocusKey = null,
                        onFocusRestored = {},
                        onClick = { tabName = item.name },
                        selected = item == tab,
                        accessibilityLabel = item.label,
                        modifier = Modifier.width(170.dp),
                        externalFocusRequester = informationTabInitialFocusRequester(
                            candidate = item,
                            selected = tab,
                            requester = initialFocusRequester,
                        ),
                    ) {
                        Text(
                            item.label,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                            fontWeight = if (item == tab) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        when (tab) {
            InformationTab.CONNECTION -> {
                if (profile == null) {
                    item(key = "server-connection-unavailable") {
                        SettingsSection("Connection", isFocusable = true) {
                            ScreenMessage("No active server connection", isError = false)
                        }
                    }
                } else {
                    item(key = "server-connection-account") {
                        SettingsSection("Account", "The server and account Opah uses on this device") {
                            ReadOnlyValue("Server", profile.apiBaseUrl)
                            ReadOnlyValue("Account", profile.username)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SecondaryAction(
                                    focusKey = "settings:server:connection:sign-out",
                                    label = "Sign out",
                                    onClick = onSignOut,
                                )
                                SecondaryAction(
                                    focusKey = "settings:server:connection:forget",
                                    label = "Forget connection",
                                    onClick = onForgetServer,
                                )
                            }
                        }
                    }
                    item(key = "server-connection-live-route") {
                        SettingsSection("Live video route", "Only change this if live video cannot connect") {
                            ProductionTvInput(
                                label = "Live video host (optional)",
                                value = liveHost,
                                onValueChange = { liveHost = it },
                                placeholder = "Use the same server",
                                enabled = true,
                                inputKey = "settings:server:live-host",
                                focusCoordinator = inputFocusCoordinator,
                                nextInputKey = "settings:server:live-port",
                            )
                            ProductionTvInput(
                                label = "Live video port",
                                value = livePort,
                                onValueChange = { livePort = it.filter(Char::isDigit) },
                                placeholder = "8554",
                                enabled = true,
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                                inputKey = "settings:server:live-port",
                                focusCoordinator = inputFocusCoordinator,
                                previousInputKey = "settings:server:live-host",
                                nextFocusRequester = saveFocusRequester,
                            )
                            Button(
                                onClick = { onUpdateLiveRoute(liveHost, livePort) },
                                modifier = Modifier.focusRequester(saveFocusRequester),
                            ) { Text("Save live video route") }
                        }
                    }
                }
            }

            InformationTab.CONFIG -> {
                val configSnapshot = state.snapshot
                if (configSnapshot == null) {
                    item(key = "server-config-unavailable") {
                        SettingsSection("Config", isFocusable = true) {
                            ScreenMessage("Server configuration is not available", isError = false)
                        }
                    }
                } else {
                    item(key = "server-config-frigate") {
                        ServerFrigateConfig(configSnapshot)
                    }
                    item(key = "server-config-birdseye") {
                        ServerBirdseyeConfig(configSnapshot)
                    }
                }
            }

            InformationTab.PERFORMANCE -> {
                item(key = "performance-overview") {
                    PerformanceOverview(
                        summary = summary?.performance,
                        loading = information.loading,
                        errorMessage = information.errorMessage,
                    )
                }
                summary?.performance?.let { performance ->
                    if (performance.accelerators.isNotEmpty()) {
                        item { PerformanceAccelerators(performance) }
                    }
                    if (performance.detectors.isNotEmpty()) {
                        item { PerformanceDetectors(performance) }
                    }
                    if (performance.cameras.isNotEmpty()) {
                        item { PerformanceCameraHeader() }
                        itemsIndexed(
                            items = performance.cameras,
                            key = { _, camera -> camera.cameraName },
                        ) { index, camera -> PerformanceCameraRow(camera, index) }
                    }
                }
            }

            InformationTab.STORAGE -> {
                item(key = "storage-overview") {
                    StorageOverview(
                        summary = summary?.storage,
                        loading = information.loading,
                        errorMessage = information.errorMessage,
                    )
                }
                summary?.storage?.let { storage ->
                    item { CameraStorageHeader() }
                    itemsIndexed(
                        items = storage.cameras,
                        key = { _, camera -> camera.cameraName },
                    ) { index, camera -> CameraStorageRow(camera, index) }
                    if (storage.cameras.isEmpty()) {
                        item { InformationFocusRow("No permitted cameras are reporting recording storage") }
                    }
                }
            }

        }
    }
}

@Composable
private fun ServerFrigateConfig(snapshot: DiscoverySnapshot) {
    SettingsSection("Frigate", isFocusable = true) {
        ReadOnlyValue("Version", snapshot.frigateVersion)
        ReadOnlyValue("Compatibility", snapshot.versionCompatibility.name.replace('_', ' '))
        ReadOnlyValue("Role", snapshot.user.role)
        ReadOnlyValue("Authorized cameras", snapshot.cameras.size.toString())
    }
}

@Composable
private fun ServerBirdseyeConfig(snapshot: DiscoverySnapshot) {
    val birdseye = snapshot.birdseye
    val birdseyeMetadata = birdseye.streamName?.let(snapshot.streamMetadata::get)
    SettingsSection(
        "Birdseye",
        "Frigate's server-composed multi-camera live view",
        isFocusable = true,
    ) {
        ReadOnlyValue(
            "Availability",
            when {
                birdseye.playable -> "Ready"
                !birdseye.enabled -> "Disabled in Frigate"
                !birdseye.restreamConfigured -> "RTSP restream is not enabled"
                birdseye.streamName == null -> "RTSP source was not discovered"
                else -> "RTSP source is unavailable"
            },
        )
        ReadOnlyValue("Birdseye enabled", if (birdseye.enabled) "Yes" else "No")
        ReadOnlyValue("RTSP restream", if (birdseye.restreamConfigured) "Configured" else "Not configured")
        ReadOnlyValue("Stream", birdseye.streamName ?: "Not discovered")
        birdseyeMetadata?.let { metadata ->
            val video = buildString {
                append(metadata.videoCodec.displayName)
                if (metadata.width != null && metadata.height != null) {
                    append(" ${metadata.width}×${metadata.height}")
                }
            }
            ReadOnlyValue("Server-reported video", video)
            ReadOnlyValue("Server-reported audio", metadata.audioCodec.displayName)
        }
        Text(
            if (birdseye.playable) {
                "Home and Cameras use one composite decoder instead of opening every camera feed"
            } else {
                "Normal camera viewing remains available while Birdseye is not ready"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun SavedRecordingsScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onLoad: () -> Unit,
    onSelect: (RecordingExport, String) -> Unit,
    onClose: () -> Unit,
    onPlay: (RecordingExport) -> Unit,
    onDelete: (RecordingExport) -> Unit,
    cachedBitmap: (RecordingExport) -> Bitmap?,
    refreshBitmap: suspend (RecordingExport, Int) -> Bitmap?,
) {
    val snapshot = state.snapshot ?: return
    LaunchedEffect(state.activeProfile, state.exports.loadedOnce) {
        if (!state.exports.loadedOnce) onLoad()
    }
    val selected = state.exports.selectedItemId?.let { selectedId ->
        state.exports.items.firstOrNull { it.id == selectedId }
    }
    if (selected != null) {
        SavedRecordingDetail(
            export = selected,
            cameraLabel = snapshot.authorizedCameraNames[selected.camera]
                ?: snapshot.cameras.firstOrNull { it.name == selected.camera }?.displayName
                ?: selected.camera.replace('_', ' '),
            canDelete = canDeleteSavedRecordings(snapshot.user.role),
            deleting = state.exports.deletingItemId == selected.id,
            errorMessage = state.exports.errorMessage,
            onBack = onClose,
            onPlay = { onPlay(selected) },
            onDelete = { onDelete(selected) },
            cachedBitmap = { cachedBitmap(selected) },
            refreshBitmap = { refreshBitmap(selected, 720) },
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Saved", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Recordings you chose to keep", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.exports.errorMessage?.let { message ->
            item { ScreenMessage(message, isError = true) }
        }
        when {
            state.exports.loading && state.exports.items.isEmpty() ->
                item { ScreenMessage("Loading saved recordings…", isError = false) }
            state.exports.loadedOnce && state.exports.items.isEmpty() ->
                item { ScreenMessage("There are no saved recordings", isError = false) }
            else -> items(state.exports.items, key = RecordingExport::id) { export ->
                val focusKey = "saved:recording:${export.id}"
                SavedRecordingRow(
                    export = export,
                    cameraLabel = snapshot.authorizedCameraNames[export.camera]
                        ?: snapshot.cameras.firstOrNull { it.name == export.camera }?.displayName
                        ?: export.camera.replace('_', ' '),
                    focusKey = focusKey,
                    restoreFocusKey = restoreFocusKey,
                    onFocusRestored = onFocusRestored,
                    onOpen = { onSelect(export, focusKey) },
                    cachedBitmap = { cachedBitmap(export) },
                    refreshBitmap = { refreshBitmap(export, 240) },
                )
            }
        }
    }
}

internal fun canDeleteSavedRecordings(role: String): Boolean = role.equals("admin", ignoreCase = true)

@Composable
private fun SavedRecordingRow(
    export: RecordingExport,
    cameraLabel: String,
    focusKey: String,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onOpen: () -> Unit,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
) {
    val title = export.name.replace('_', ' ')
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date((export.createdAt * 1_000).toLong()))
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onOpen,
        accessibilityLabel = "$title, recording details",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SavedRecordingThumbnail(
                export = export,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
                modifier = Modifier.size(width = 160.dp, height = 90.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$cameraLabel • $date",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                if (export.inProgress) "Saving…" else "Details",
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SavedRecordingDetail(
    export: RecordingExport,
    cameraLabel: String,
    canDelete: Boolean,
    deleting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
) {
    var showDeleteConfirmation by rememberSaveable(export.id) { mutableStateOf(false) }
    val initialFocusRequester = remember(export.id) { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(export.id) {
        withFrameNanos { }
        initialFocusRequester.requestFocus()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Recording details", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(export.name.replace('_', ' '), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            SavedRecordingThumbnail(
                export = export,
                cachedBitmap = cachedBitmap,
                refreshBitmap = refreshBitmap,
                modifier = Modifier.weight(1.65f).aspectRatio(16f / 9f),
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SavedRecordingDetailLine("Camera", cameraLabel)
                SavedRecordingDetailLine(
                    "Saved",
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date((export.createdAt * 1_000).toLong())),
                )
                SavedRecordingDetailLine("Video", if (export.inProgress) "Still saving" else "Ready to play")
                errorMessage?.let { ScreenMessage(it, isError = true) }
                Spacer(Modifier.weight(1f))
                SafeBackButton(
                    onClick = onBack,
                    initialFocusRequester = initialFocusRequester,
                    label = "Back to Saved",
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onPlay,
                    enabled = !export.inProgress && !deleting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Play recording") }
                if (canDelete && !export.inProgress) {
                    Button(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (deleting) "Deleting…" else "Delete recording") }
                }
            }
        }
    }
    if (showDeleteConfirmation) {
        DeleteSavedRecordingDialog(
            export = export,
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = {
                showDeleteConfirmation = false
                onDelete()
            },
        )
    }
}

@Composable
internal fun SavedRecordingThumbnail(
    export: RecordingExport,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
    modifier: Modifier,
) {
    var bitmap by remember(export.id, export.thumbnailPath) { mutableStateOf(cachedBitmap()) }
    var unavailable by remember(export.id, export.thumbnailPath) {
        mutableStateOf(export.thumbnailPath == null)
    }
    LaunchedEffect(export.id, export.thumbnailPath) {
        if (export.thumbnailPath != null) {
            val loaded = refreshBitmap()
            if (loaded == null) unavailable = true else bitmap = loaded
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { current ->
            Image(
                bitmap = remember(current) { current.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            when {
                export.inProgress -> "Preview preparing…"
                unavailable -> "Preview unavailable"
                else -> "Loading preview…"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SavedRecordingDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DeleteSavedRecordingDialog(
    export: RecordingExport,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val keepRecordingFocusRequester = remember(export.id) { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 440.dp, max = 620.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                    RoundedCornerShape(14.dp),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Delete saved recording?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${export.name.replace('_', ' ')} will be permanently deleted from Frigate and cannot be recovered",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(keepRecordingFocusRequester),
                ) { Text("Keep recording") }
                Button(onClick = onConfirm) { Text("Delete recording") }
            }
        }
    }
    LaunchedEffect(export.id) {
        withFrameNanos { }
        keepRecordingFocusRequester.requestFocus()
    }
}

@Composable
private fun PerformanceOverview(
    summary: FrigatePerformanceSummary?,
    loading: Boolean,
    errorMessage: String?,
) {
    SettingsSection(
        title = "Performance",
        subtitle = summary?.let { "Frigate ${it.version} • uptime ${formatUptime(it.uptimeSeconds)}" },
        isFocusable = true,
    ) {
        if (summary == null) {
            if (loading) {
                Text("Loading performance information…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ScreenMessage(
                    errorMessage ?: "Performance information is not available",
                    isError = errorMessage != null,
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                InformationMetric("Camera FPS", formatNumber(summary.cameraFps), Modifier.weight(1f))
                InformationMetric("Process FPS", formatNumber(summary.processFps), Modifier.weight(1f))
                InformationMetric("Detection FPS", formatNumber(summary.detectionFps), Modifier.weight(1f))
                InformationMetric("Skipped FPS", formatNumber(summary.skippedFps), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                InformationMetric("System CPU", formatPercentOptional(summary.systemCpuPercent), Modifier.weight(1f))
                InformationMetric("Frigate workload", formatCpuWorkload(summary.frigateCpuPercent), Modifier.weight(1f))
                InformationMetric("Frigate memory", formatPercentOptional(summary.frigateMemoryPercent), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                InformationMetric("Detectors", summary.detectors.size.toString(), Modifier.weight(1f))
                InformationMetric("Accelerators", summary.accelerators.size.toString(), Modifier.weight(1f))
            }
            if (summary.temperatures.isNotEmpty()) {
                Text(
                    summary.temperatures.joinToString("  •  ") {
                        "${it.name}: ${String.format(Locale.getDefault(), "%.1f °C", it.celsius)}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PerformanceAccelerators(summary: FrigatePerformanceSummary) {
    SettingsSection("Hardware acceleration", "GPU and NPU values are shown when Frigate reports them", isFocusable = true) {
        summary.accelerators.forEach { accelerator ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${accelerator.kind} • ${accelerator.name}", modifier = Modifier.weight(1.6f), fontWeight = FontWeight.Bold)
                Text("Load ${formatPercentOptional(accelerator.usagePercent)}", modifier = Modifier.weight(1f))
                Text("Memory ${formatPercentOptional(accelerator.memoryPercent)}", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PerformanceDetectors(summary: FrigatePerformanceSummary) {
    SettingsSection("Detectors", "Lower inference time is faster", isFocusable = true) {
        summary.detectors.forEach { detector ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(detector.name, fontWeight = FontWeight.Bold)
                Text(
                    detector.inferenceSpeedMs?.let { String.format(Locale.getDefault(), "%.2f ms", it) }
                        ?: "Not reported",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PerformanceCameraHeader() {
    SettingsSection("Cameras", "Performance for permitted cameras only") {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Camera", modifier = Modifier.weight(1.8f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Camera FPS", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Process FPS", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Detection FPS", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Skipped FPS", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PerformanceCameraRow(camera: app.opah.tv.data.model.CameraPerformance, index: Int) {
    var focused by remember(camera.cameraName) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (index % 2 == 0) 0.28f else 0.12f), shape)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(camera.displayName, modifier = Modifier.weight(1.8f), fontWeight = FontWeight.Bold)
        Text(formatNumber(camera.cameraFps), modifier = Modifier.weight(1f))
        Text(formatNumber(camera.processFps), modifier = Modifier.weight(1f))
        Text(formatNumber(camera.detectionFps), modifier = Modifier.weight(1f))
        Text(formatNumber(camera.skippedFps), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InformationMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StorageOverview(
    summary: RecordingStorageSummary?,
    loading: Boolean,
    errorMessage: String?,
) {
    val volume = summary?.volume
    val visibleUsage = summary?.cameras?.sumOf { it.usageMiB } ?: 0.0
    val remainingUsed = (volume?.usedMiB?.minus(visibleUsage) ?: 0.0).coerceAtLeast(0.0)
    SettingsSection(
        title = if (summary == null) "Storage" else "Recording volume",
        subtitle = volume?.let {
            "${formatStorage(it.usedMiB)} used of ${formatStorage(it.totalMiB)}"
        },
        isFocusable = true,
    ) {
        if (summary == null) {
            if (loading) {
                Text("Loading recording storage…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ScreenMessage(
                    errorMessage ?: "Storage information is not available",
                    isError = errorMessage != null,
                )
            }
        } else {
            StorageUsageBar(summary, remainingUsed)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StorageMetric("Camera recordings", formatStorage(visibleUsage), Modifier.weight(1f))
                StorageMetric("Other used", formatStorage(remainingUsed), Modifier.weight(1f))
                StorageMetric("Available", formatStorage(summary.unusedMiB), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StorageUsageBar(summary: RecordingStorageSummary, remainingUsed: Double) {
    val total = summary.volume.totalMiB.coerceAtLeast(1.0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        summary.cameras.forEachIndexed { index, camera ->
            if (camera.usageMiB > 0.0) {
                Box(
                    Modifier
                        .weight((camera.usageMiB / total).toFloat().coerceAtLeast(0.0001f))
                        .fillMaxSize()
                        .background(storageColor(index)),
                )
            }
        }
        if (remainingUsed > 0.0) {
            Box(
                Modifier
                    .weight((remainingUsed / total).toFloat().coerceAtLeast(0.0001f))
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)),
            )
        }
        if (summary.unusedMiB > 0.0) {
            Box(
                Modifier
                    .weight((summary.unusedMiB / total).toFloat().coerceAtLeast(0.0001f))
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

@Composable
private fun StorageMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CameraStorageHeader() {
    SettingsSection("Cameras", "Storage and estimated recording bandwidth for permitted cameras") {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Camera", modifier = Modifier.weight(1.8f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Storage", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Total", modifier = Modifier.weight(0.8f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Bandwidth", modifier = Modifier.weight(1.2f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CameraStorageRow(camera: CameraStorageUsage, index: Int) {
    var focused by remember(camera.cameraName) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clip(shape)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (index % 2 == 0) 0.28f else 0.12f),
                shape,
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                },
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1.8f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(11.dp).background(storageColor(index), CircleShape))
            Text(camera.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatStorage(camera.usageMiB), modifier = Modifier.weight(1f))
        Text(formatPercent(camera.percentageOfTotal), modifier = Modifier.weight(0.8f))
        Text("${formatStorage(camera.bandwidthMiBPerHour)} / hour", modifier = Modifier.weight(1.2f))
    }
}

@Composable
private fun InformationFocusRow(message: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                shape,
            )
            .padding(14.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatStorage(valueMiB: Double): String = if (valueMiB >= 1024.0) {
    String.format(Locale.getDefault(), "%.2f GiB", valueMiB / 1024.0)
} else {
    String.format(Locale.getDefault(), "%.0f MiB", valueMiB)
}

private fun formatPercent(value: Double): String =
    String.format(Locale.getDefault(), "%.2f%%", value)

private fun formatPercentOptional(value: Double?): String = value?.let {
    String.format(Locale.getDefault(), "%.1f%%", it)
} ?: "Not reported"

private fun formatCpuWorkload(value: Double?): String = value?.let {
    val coreEquivalents = it / 100.0
    val coreLabel = if (coreEquivalents in 0.995..1.005) "core" else "cores"
    String.format(Locale.getDefault(), "%.1f%% • %.2f %s", it, coreEquivalents, coreLabel)
} ?: "Not reported"

private fun formatNumber(value: Double?): String = value?.let {
    String.format(Locale.getDefault(), "%.1f", it)
} ?: "—"

private fun formatUptime(seconds: Double?): String {
    if (seconds == null || seconds < 0) return "not reported"
    val totalMinutes = (seconds / 60).toLong()
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return buildList {
        if (days > 0) add("${days}d")
        if (hours > 0 || days > 0) add("${hours}h")
        add("${minutes}m")
    }.joinToString(" ")
}

private fun storageColor(index: Int): Color = STORAGE_COLORS[index % STORAGE_COLORS.size]

private val STORAGE_COLORS = listOf(
    Color(0xFFFF6A45),
    Color(0xFFFFB12E),
    Color(0xFF20D6A4),
    Color(0xFF35A7FF),
    Color(0xFF8B6FE8),
    Color(0xFFFF4F78),
)

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    subtitle: String? = null,
    isFocusable: Boolean = false,
    initialFocusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isFocusable) {
                    Modifier
                        .then(
                            if (initialFocusRequester == null) {
                                Modifier
                            } else {
                                Modifier.focusRequester(initialFocusRequester)
                            },
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                } else {
                    Modifier
                },
            )
            .background(
                if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.11f)
                else Color.Transparent,
                RoundedCornerShape(8.dp),
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FlatSettingsSectionHeader(title = title, subtitle = subtitle)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
internal fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    keyPrefix: String,
    onSelect: (T) -> Unit,
    externalFocusRequester: FocusRequester? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEachIndexed { index, value ->
            app.opah.tv.ui.ChoiceRow(
                focusKey = "$keyPrefix:$value",
                title = label(value),
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.fillMaxWidth(),
                externalFocusRequester = externalFocusRequester.takeIf { index == 0 },
            )
        }
    }
}

@Composable
internal fun ThemeColorEditor(
    colors: CustomThemeColors,
    onChange: (CustomThemeColors) -> Unit,
) {
    val safeColors = ThemeColorPolicy.sanitize(colors)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Quick styles", fontWeight = FontWeight.Bold)
        CUSTOM_THEME_PRESETS.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { preset ->
                    val presetColors = ThemeColorPolicy.sanitize(preset.colors)
                    ThemePresetCard(
                        preset = preset.copy(colors = presetColors),
                        selected = safeColors == presetColors,
                        onClick = { onChange(presetColors) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("Make it yours", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        ThemeAdjustmentRow(
            focusKey = "settings:theme-adjust:accent-hue",
            label = "Accent color",
            value = themeHueName(ThemeColorPolicy.toHsl(safeColors.accentArgb).hue),
            swatchArgb = safeColors.accentArgb,
            onDecrease = {
                onChange(safeColors.copy(accentArgb = ThemeColorPolicy.adjustHue(safeColors.accentArgb, -10)))
            },
            onIncrease = {
                onChange(safeColors.copy(accentArgb = ThemeColorPolicy.adjustHue(safeColors.accentArgb, 10)))
            },
        )
        ThemeAdjustmentRow(
            focusKey = "settings:theme-adjust:accent-strength",
            label = "Accent intensity",
            value = themeIntensityLabel(ThemeColorPolicy.toHsl(safeColors.accentArgb).saturation),
            swatchArgb = safeColors.accentArgb,
            onDecrease = {
                onChange(safeColors.copy(accentArgb = ThemeColorPolicy.adjustSaturation(safeColors.accentArgb, -5)))
            },
            onIncrease = {
                onChange(safeColors.copy(accentArgb = ThemeColorPolicy.adjustSaturation(safeColors.accentArgb, 5)))
            },
        )
        ThemeAdjustmentRow(
            focusKey = "settings:theme-adjust:accent-brightness",
            label = "Accent brightness",
            value = themeBrightnessLabel(ThemeColorPolicy.toHsl(safeColors.accentArgb).lightness),
            swatchArgb = safeColors.accentArgb,
            onDecrease = {
                onChange(safeColors.copy(accentArgb = ThemeColorPolicy.adjustLightness(safeColors.accentArgb, -5)))
            },
            onIncrease = {
                onChange(safeColors.copy(accentArgb = ThemeColorPolicy.adjustLightness(safeColors.accentArgb, 5)))
            },
        )
        ThemeAdjustmentRow(
            focusKey = "settings:theme-adjust:background-hue",
            label = "Background tint",
            value = themeHueName(ThemeColorPolicy.toHsl(safeColors.backgroundArgb).hue),
            swatchArgb = safeColors.backgroundArgb,
            onDecrease = {
                onChange(safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustHue(safeColors.backgroundArgb, -10)))
            },
            onIncrease = {
                onChange(safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustHue(safeColors.backgroundArgb, 10)))
            },
        )
        ThemeAdjustmentRow(
            focusKey = "settings:theme-adjust:background-strength",
            label = "Background intensity",
            value = themeIntensityLabel(ThemeColorPolicy.toHsl(safeColors.backgroundArgb).saturation),
            swatchArgb = safeColors.backgroundArgb,
            onDecrease = {
                onChange(safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustSaturation(safeColors.backgroundArgb, -5)))
            },
            onIncrease = {
                onChange(safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustSaturation(safeColors.backgroundArgb, 5)))
            },
        )
        ThemeAdjustmentRow(
            focusKey = "settings:theme-adjust:background-brightness",
            label = "Background brightness",
            value = themeBrightnessLabel(ThemeColorPolicy.toHsl(safeColors.backgroundArgb).lightness),
            swatchArgb = safeColors.backgroundArgb,
            onDecrease = {
                onChange(safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustLightness(safeColors.backgroundArgb, -3)))
            },
            onIncrease = {
                onChange(safeColors.copy(backgroundArgb = ThemeColorPolicy.adjustLightness(safeColors.backgroundArgb, 3)))
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(safeColors.backgroundArgb), RoundedCornerShape(10.dp))
                .border(
                    1.dp,
                    Color(ThemeColorPolicy.readableForeground(safeColors.backgroundArgb)).copy(alpha = 0.28f),
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Preview",
                color = Color(ThemeColorPolicy.readableForeground(safeColors.backgroundArgb)),
                fontWeight = FontWeight.Bold,
            )
            Box(
                modifier = Modifier
                    .background(Color(safeColors.accentArgb), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "Selected color",
                    color = Color(ThemeColorPolicy.readableForeground(safeColors.accentArgb)),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ThemeAdjustmentRow(
    focusKey: String,
    label: String,
    value: String,
    swatchArgb: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onIncrease,
        accessibilityLabel = "$label, $value. Use left and right to change",
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onDecrease()
                        true
                    }
                    Key.DirectionRight -> {
                        onIncrease()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(swatchArgb), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), CircleShape),
            )
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("◀  Change  ▶", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun themeHueName(hue: Int): String = when (hue.coerceIn(0, 359)) {
    in 0..14, in 345..359 -> "Red"
    in 15..44 -> "Orange"
    in 45..74 -> "Yellow"
    in 75..104 -> "Lime"
    in 105..149 -> "Green"
    in 150..189 -> "Teal"
    in 190..214 -> "Cyan"
    in 215..254 -> "Blue"
    in 255..284 -> "Indigo"
    in 285..319 -> "Purple"
    else -> "Pink"
}

internal fun themeIntensityLabel(saturation: Int): String = when (saturation.coerceIn(0, 100)) {
    in 0..24 -> "Muted"
    in 25..69 -> "Balanced"
    else -> "Vivid"
}

internal fun themeBrightnessLabel(lightness: Int): String = when (lightness.coerceIn(0, 100)) {
    in 0..19 -> "Very dark"
    in 20..39 -> "Dark"
    in 40..59 -> "Medium"
    in 60..79 -> "Bright"
    else -> "Very bright"
}

private data class ThemePreset(
    val label: String,
    val colors: CustomThemeColors,
)

private val CUSTOM_THEME_PRESETS = listOf(
    ThemePreset("Coral", CustomThemeColors(0xFFFF7048.toInt(), 0xFF07111F.toInt())),
    ThemePreset("Ocean", CustomThemeColors(0xFF53B7FF.toInt(), 0xFF071522.toInt())),
    ThemePreset("Forest", CustomThemeColors(0xFF65D68A.toInt(), 0xFF08170F.toInt())),
    ThemePreset("Plum", CustomThemeColors(0xFFD29BFF.toInt(), 0xFF180D20.toInt())),
    ThemePreset("Sunset", CustomThemeColors(0xFFFFB454.toInt(), 0xFF211108.toInt())),
    ThemePreset("Slate", CustomThemeColors(0xFF7DD8D2.toInt(), 0xFF11171A.toInt())),
)

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    FocusCard(
        focusKey = "settings:theme-preset:${preset.label.lowercase()}",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = onClick,
        selected = selected,
        accessibilityLabel = "${preset.label} colors",
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(preset.colors.backgroundArgb), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(Color(preset.colors.accentArgb), CircleShape),
                )
            }
            Text(
                preset.label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun SettingToggle(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    externalFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        focusKey = "settings:toggle:$label",
        title = label,
        value = if (value) "On" else "Off",
        onClick = { onChange(!value) },
        enabled = enabled,
        selected = value,
        externalFocusRequester = externalFocusRequester,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ReadOnlyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatReviewTime(epochSeconds: Double): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date((epochSeconds * 1_000.0).toLong()))
