package app.opah.tv.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.BuildConfig
import app.opah.tv.R
import app.opah.tv.data.model.AppearanceMode
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.CameraStorageUsage
import app.opah.tv.data.model.CustomThemeColors
import app.opah.tv.data.model.FrigatePerformanceSummary
import app.opah.tv.data.model.HslColor
import app.opah.tv.data.model.LiveStreamOption
import app.opah.tv.data.model.ReviewItem
import app.opah.tv.data.model.ReviewSeverity
import app.opah.tv.data.model.RecordingStorageSummary
import app.opah.tv.data.model.StreamPreference
import app.opah.tv.data.model.ThemeColorPolicy
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class InformationTab { PERFORMANCE, STORAGE }

private const val SETUP_ADDRESS_INPUT = "setup:address"
private const val SETUP_USERNAME_INPUT = "setup:username"
private const val SETUP_PASSWORD_INPUT = "setup:password"
private const val SETUP_RTSP_HOST_INPUT = "setup:rtsp-host"
private const val SETUP_RTSP_PORT_INPUT = "setup:rtsp-port"
private const val SETTINGS_RTSP_HOST_INPUT = "settings:rtsp-host"
private const val SETTINGS_RTSP_PORT_INPUT = "settings:rtsp-port"

internal const val OPAH_REPOSITORY_URL = "https://github.com/VibeCodingAntagonist/opah-frigate-tv-app"
internal const val OPAH_INDEPENDENCE_NOTICE =
    "Opah is an independent community project. It is not made, approved, or supported by " +
        "the Frigate team."
internal const val OPAH_TRADEMARK_NOTICE =
    "Frigate and Frigate NVR are trademarks of Frigate, Inc. Opah uses those names only to " +
        "explain what the app works with."
internal const val OPAH_PRIVACY_NOTICE =
    "Opah has no ads and does not track how you use the app. Your saved sign-in is encrypted " +
        "on this device."

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
    onTestConnection: (String, String, String, String, String) -> Unit,
    onConnect: (String, String, String, String, String) -> Unit,
    onForget: () -> Unit,
    onExitRequested: () -> Unit,
) {
    val saved = state.savedProfile
    var serverUrl by rememberSaveable { mutableStateOf(saved?.apiBaseUrl.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(saved?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var advanced by rememberSaveable {
        mutableStateOf(saved?.let { it.rtspHostOverride != null || it.rtspPort != 8554 } ?: false)
    }
    var rtspHost by rememberSaveable { mutableStateOf(saved?.rtspHostOverride.orEmpty()) }
    var rtspPort by rememberSaveable { mutableStateOf((saved?.rtspPort ?: 8554).toString()) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val keepEditingFocusRequester = remember { FocusRequester() }
    val inputFocusCoordinator = remember { TvInputFocusCoordinator() }
    val testConnectionFocusRequester = remember { FocusRequester() }
    val hasDraft = password.isNotEmpty() ||
        serverUrl != saved?.apiBaseUrl.orEmpty() ||
        username != saved?.username.orEmpty() ||
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
        username = saved.username
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
                    onValueChange = { serverUrl = it },
                    placeholder = "https://frigate.example:8971",
                    enabled = !state.loading,
                    keyboardType = KeyboardType.Uri,
                    requestInitialFocus = true,
                    inputKey = SETUP_ADDRESS_INPUT,
                    focusCoordinator = inputFocusCoordinator,
                    nextInputKey = SETUP_USERNAME_INPUT,
                )
            }
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
            if (serverUrl.trim().startsWith("http://", ignoreCase = true)) {
                item {
                    ScreenMessage(
                        message = "HTTP exposes the Frigate session on the local network. HTTPS is recommended.",
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
                        previousInputKey = SETUP_PASSWORD_INPUT,
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
                        onClick = { onTestConnection(serverUrl, username, password, rtspHost, rtspPort) },
                        enabled = !state.loading,
                        modifier = Modifier.focusRequester(testConnectionFocusRequester),
                    ) { Text("Test connection") }
                    Button(
                        onClick = { onConnect(serverUrl, username, password, rtspHost, rtspPort) },
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
internal fun HomeScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    initialCameraFocusRequester: FocusRequester,
    onOpenCameras: () -> Unit,
    onOpenReview: () -> Unit,
    onPlayCamera: (Camera, String) -> Unit,
    onPlayReview: (ReviewItem, String) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
    cachedReviewBitmap: (ReviewItem) -> Bitmap?,
    refreshReviewBitmap: suspend (ReviewItem, Int) -> Bitmap?,
) {
    val snapshot = state.snapshot ?: return
    val alerts = snapshot.recentReviewItems.filter { it.severity == ReviewSeverity.ALERT }.take(8)
    val detections = snapshot.recentReviewItems.filter { it.severity == ReviewSeverity.DETECTION }.take(8)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Home", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Live cameras and recent activity",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Connected", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
        }
        item {
            SectionHeader("Cameras", "Current images — select a camera to watch") {
                Button(onClick = onOpenCameras) { Text("View all") }
            }
        }
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardWidth = (maxWidth - 36.dp) / 3
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(snapshot.cameras, key = { _, camera -> camera.name }) { index, camera ->
                        val focusKey = "home:camera:${camera.name}"
                        CameraCard(
                            camera = camera,
                            focusKey = focusKey,
                            restoreFocusKey = restoreFocusKey,
                            onFocusRestored = onFocusRestored,
                            onClick = { onPlayCamera(camera, focusKey) },
                            cachedBitmap = cachedBitmap,
                            refreshBitmap = refreshBitmap,
                            initialRefreshDelayMillis = index * CAMERA_REFRESH_STAGGER_MS,
                            externalFocusRequester = initialCameraFocusRequester.takeIf {
                                camera == snapshot.cameras.firstOrNull()
                            },
                            modifier = Modifier.width(cardWidth),
                        )
                    }
                }
            }
        }
        if (alerts.isNotEmpty()) {
            item {
                SectionHeader("Recent alerts", "Recording-backed activity from Frigate") {
                    Button(onClick = onOpenReview) { Text("Open Review") }
                }
            }
            item {
                ReviewRow(
                    prefix = "home:alert",
                    items = alerts,
                    restoreFocusKey = restoreFocusKey,
                    onFocusRestored = onFocusRestored,
                    onPlay = onPlayReview,
                    cachedBitmap = cachedReviewBitmap,
                    refreshBitmap = refreshReviewBitmap,
                )
            }
        }
        if (detections.isNotEmpty()) {
            item {
                SectionHeader("Recent detections", "Other detected activity") {
                    Button(onClick = onOpenReview) { Text("Open Review") }
                }
            }
            item {
                ReviewRow(
                    prefix = "home:detection",
                    items = detections,
                    restoreFocusKey = restoreFocusKey,
                    onFocusRestored = onFocusRestored,
                    onPlay = onPlayReview,
                    cachedBitmap = cachedReviewBitmap,
                    refreshBitmap = refreshReviewBitmap,
                )
            }
        }
        if (alerts.isEmpty() && detections.isEmpty()) {
            item { ScreenMessage("No recent alerts or detections were returned by Frigate.", isError = false) }
        }
    }
}

@Composable
internal fun CamerasScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onPlayCamera: (Camera, String) -> Unit,
    onPlayStream: (Camera, LiveStreamOption, String) -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    val snapshot = state.snapshot ?: return
    val cameras = snapshot.cameras
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 24.dp, top = 18.dp, end = 24.dp, bottom = 12.dp)) {
            Text("Cameras", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "Select a camera for your default stream, or choose a stream override below it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItemsIndexed(cameras, key = { _, camera -> camera.name }) { index, camera ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        camera.streams.forEachIndexed { index, option ->
                            val streamKey = "cameras:stream:${camera.name}:${option.streamName}"
                            FocusCard(
                                focusKey = streamKey,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = onFocusRestored,
                                onClick = { onPlayStream(camera, option, streamKey) },
                                accessibilityLabel = "${camera.displayName}, ${option.label}",
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = option.label.ifBlank { "Stream ${index + 1}" },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
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
internal fun BirdseyeScreen(
    state: Phase0UiState,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    initialFocusRequester: FocusRequester,
    onPlay: () -> Unit,
    cachedBitmap: (String) -> Bitmap?,
    refreshBitmap: suspend (String, Int) -> Bitmap?,
) {
    val snapshot = state.snapshot ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Birdseye", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Frigate's live composite view of your permitted cameras.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    FocusCard(
        focusKey = "birdseye:watch",
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onPlay,
        accessibilityLabel = "Watch Birdseye composite",
        externalFocusRequester = initialFocusRequester,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraSnapshot(
                cameraName = BIRDSEYE_CAMERA_NAME,
                cachedBitmap = { cachedBitmap(BIRDSEYE_CAMERA_NAME) },
                refreshBitmap = { refreshBitmap(BIRDSEYE_CAMERA_NAME, 300) },
                modifier = Modifier
                    .width(534.dp)
                    .aspectRatio(16f / 9f),
            )
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("BIRDSEYE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
                Text("Live camera overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "One efficient composite for up to ${snapshot.authorizedCameraNames.size} permitted cameras.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Watch", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
        }
    }
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
    modifier: Modifier,
) {
    FocusCard(
        focusKey = focusKey,
        restoreFocusKey = restoreFocusKey,
        onFocusRestored = onFocusRestored,
        onClick = onClick,
        accessibilityLabel = "Watch ${camera.displayName}",
        externalFocusRequester = externalFocusRequester,
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

@Composable
private fun ReviewRow(
    prefix: String,
    items: List<ReviewItem>,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
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
                    onClick = { onPlay(item, focusKey) },
                    enabled = item.recordingAvailable != false,
                    accessibilityLabel = "${item.severity.name.lowercase()} at ${item.camera.replace('_', ' ')}",
                    modifier = Modifier.width(cardWidth),
                ) {
                    Column {
                        ReviewThumbnail(
                            item = item,
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
            SettingsSection("Appearance", "Follow the TV, use a fixed theme, or create your own.") {
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
            SettingsSection("Default live stream", "Explicit stream buttons in Cameras remain session-only overrides.") {
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
            SettingsSection("Live playback", "Defaults apply the next time a stream opens.") {
                SettingToggle("Prefer RTP over TCP", state.settings.preferRtpTcp, onPreferRtpTcp)
                SettingToggle("Start live video muted", state.settings.startLiveMuted, onStartMuted)
                SettingToggle("Show playback Info control", state.settings.diagnosticsEnabled, onDiagnostics)
            }
        }
        item {
            SettingsSection("Support", "Inspect the current connection, camera streams, and TV decoders.") {
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
            SettingsSection("Frigate account", "API authentication and RTSP routing are managed separately.") {
                ReadOnlyValue("Server", profile.apiBaseUrl)
                ReadOnlyValue("Username", profile.username)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSignOut) { Text("Sign out") }
                    Button(onClick = onForgetServer) { Text("Forget server and password") }
                }
            }
        }
        item {
            SettingsSection("RTSP routing", "Change the live-stream route without signing in again.") {
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
internal fun AboutScreen() {
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 34.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.opah_brand_mark),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Opah", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Version ${BuildConfig.VERSION_NAME}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsSection("Independent project", isFocusable = true) {
                    Text(OPAH_INDEPENDENCE_NOTICE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SettingsSection(
                    "Privacy",
                    "Opah connects this device directly to the Frigate server you choose.",
                ) {
                    Text(
                        OPAH_PRIVACY_NOTICE,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsSection("Project and support", "Opah uses the Apache License 2.0.") {
                    Text(
                        OPAH_REPOSITORY_URL,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            if (repositoryButtonArmed) {
                                runCatching { uriHandler.openUri(OPAH_REPOSITORY_URL) }
                            }
                        },
                        modifier = Modifier.onFocusChanged {
                            repositoryButtonFocused = it.isFocused
                        },
                    ) {
                        Text("Open project on GitHub")
                    }
                }
                SettingsSection("Trademarks and warranty") {
                    Text(OPAH_TRADEMARK_NOTICE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Copyright © 2026 Opah contributors. Opah is provided as-is, without warranty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
    val birdseye = snapshot.birdseye
    val birdseyeMetadata = birdseye.streamName?.let(snapshot.streamMetadata::get)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Diagnostics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Connection, authorization, and decoder evidence", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    onBack?.let {
                        Button(
                            onClick = it,
                        ) { Text("Back to Settings") }
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = !state.loading,
                        // The Settings card activates on key-down. Landing on Back
                        // here lets a held Select repeat immediately close this page.
                        // Refresh is a safe target for any carried-over repeat.
                        modifier = if (initialFocusRequester == null) {
                            Modifier
                        } else {
                            Modifier.focusRequester(initialFocusRequester)
                        },
                    ) {
                        Text(if (state.loading) "Refreshing…" else "Refresh")
                    }
                }
            }
        }
        item {
            SettingsSection("Frigate", isFocusable = true) {
                ReadOnlyValue("Version", snapshot.frigateVersion)
                ReadOnlyValue("Compatibility", snapshot.versionCompatibility.name.replace('_', ' '))
                ReadOnlyValue("Role", snapshot.user.role)
                ReadOnlyValue("Authorized cameras", snapshot.cameras.size.toString())
                ReadOnlyValue("Visible streams", snapshot.streamMetadata.size.toString())
            }
        }
        item {
            SettingsSection(
                "Birdseye",
                "Frigate's server-composed multi-camera live view.",
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
                        "Home and Cameras use one composite decoder instead of opening every camera feed."
                    } else {
                        "Normal camera viewing remains available while Birdseye is not ready."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SettingsSection("Device", isFocusable = true) {
                if (device == null) {
                    Text("Device inspection is still loading.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        item(key = "diagnostics-streams-header") {
            SettingsSection(
                "Discovered live streams",
                "Move down to inspect each permitted camera.",
                isFocusable = true,
            ) {}
        }
        items(
            items = snapshot.cameras,
            key = { camera -> "diagnostics-stream:${camera.name}" },
        ) { camera ->
            SettingsSection(camera.displayName, isFocusable = true) {
                camera.streams.forEach { option ->
                    val metadata = option.metadata ?: snapshot.streamMetadata[option.streamName]
                    val description = buildString {
                        append(option.label)
                        append(" — ")
                        append(metadata?.videoCodec?.displayName ?: "codec unknown")
                        metadata?.let { details ->
                            if (details.width != null && details.height != null) append(" ${details.width}×${details.height}")
                            append(if (details.available) " • available" else " • unavailable")
                        }
                    }
                    Text(
                        description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
    initialTabName: String? = null,
) {
    LaunchedEffect(state.activeProfile) { onLoad() }
    val information = state.information
    val summary = information.summary
    var tabName by rememberSaveable(initialTabName) {
        mutableStateOf(initialTabName ?: InformationTab.PERFORMANCE.name)
    }
    val tab = runCatching { InformationTab.valueOf(tabName) }.getOrDefault(InformationTab.PERFORMANCE)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Information", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "System performance and recording storage",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onRefresh, enabled = !information.loading) {
                    Text(if (information.loading) "Refreshing…" else "Refresh")
                }
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
                        accessibilityLabel = "${item.name.lowercase().replaceFirstChar(Char::uppercase)} information",
                        modifier = Modifier.width(170.dp),
                    ) {
                        Text(
                            item.name.lowercase().replaceFirstChar(Char::uppercase),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                            fontWeight = if (item == tab) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        when (tab) {
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
                        item { InformationFocusRow("No permitted cameras are reporting recording storage.") }
                    }
                }
            }
        }
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
                    errorMessage ?: "Performance information is not available.",
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
    SettingsSection("Hardware acceleration", "GPU and NPU values are shown when Frigate reports them.", isFocusable = true) {
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
    SettingsSection("Detectors", "Lower inference time is faster.", isFocusable = true) {
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
    SettingsSection("Cameras", "Performance for permitted cameras only.") {
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
                    errorMessage ?: "Storage information is not available.",
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
    SettingsSection("Cameras", "Storage and estimated recording bandwidth for permitted cameras.") {
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

private const val BIRDSEYE_CAMERA_NAME = "birdseye"
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
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    isFocusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isFocusable) {
                    Modifier.onFocusChanged { focused = it.isFocused }.focusable()
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                shape,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    keyPrefix: String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEach { value ->
            FocusCard(
                focusKey = "$keyPrefix:$value",
                restoreFocusKey = null,
                onFocusRestored = {},
                onClick = { onSelect(value) },
                selected = value == selected,
                accessibilityLabel = label(value),
            ) {
                Text(label(value), modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
    }
}

@Composable
private fun ThemeColorEditor(
    colors: CustomThemeColors,
    onChange: (CustomThemeColors) -> Unit,
) {
    val safeColors = ThemeColorPolicy.sanitize(colors)
    val contrast = ThemeColorPolicy.contrastRatio(
        safeColors.accentArgb,
        safeColors.backgroundArgb,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ThemeColorControls(
            label = "Accent",
            argb = safeColors.accentArgb,
            onColorChange = { accent ->
                onChange(ThemeColorPolicy.sanitize(safeColors.copy(accentArgb = accent)))
            },
            modifier = Modifier.weight(1f),
        )
        ThemeColorControls(
            label = "Background",
            argb = safeColors.backgroundArgb,
            onColorChange = { background ->
                onChange(ThemeColorPolicy.sanitize(safeColors.copy(backgroundArgb = background)))
            },
            modifier = Modifier.weight(1f),
        )
    }
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
            "Live preview",
            color = Color(ThemeColorPolicy.readableForeground(safeColors.backgroundArgb)),
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .background(Color(safeColors.accentArgb), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "Accent",
                color = Color(ThemeColorPolicy.readableForeground(safeColors.accentArgb)),
                fontWeight = FontWeight.Bold,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Accent contrast ${String.format(Locale.ROOT, "%.1f:1", contrast)} • protected minimum 3.0:1",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { onChange(CustomThemeColors()) }) { Text("Reset colors") }
    }
}

@Composable
private fun ThemeColorControls(
    label: String,
    argb: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsl = ThemeColorPolicy.toHsl(argb)
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(argb), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), CircleShape),
            )
        }
        ThemeColorAdjustment(
            label = "Hue",
            value = "${hsl.hue}°",
            onDecrease = { onColorChange(ThemeColorPolicy.adjustHue(argb, -15)) },
            onIncrease = { onColorChange(ThemeColorPolicy.adjustHue(argb, 15)) },
        )
        ThemeColorAdjustment(
            label = "Color",
            value = "${hsl.saturation}%",
            onDecrease = { onColorChange(ThemeColorPolicy.adjustSaturation(argb, -5)) },
            onIncrease = { onColorChange(ThemeColorPolicy.adjustSaturation(argb, 5)) },
        )
        ThemeColorAdjustment(
            label = "Brightness",
            value = "${hsl.lightness}%",
            onDecrease = { onColorChange(ThemeColorPolicy.adjustLightness(argb, -5)) },
            onIncrease = { onColorChange(ThemeColorPolicy.adjustLightness(argb, 5)) },
        )
    }
}

@Composable
private fun ThemeColorAdjustment(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onDecrease) { Text("−") }
        Text(value, modifier = Modifier.width(46.dp), maxLines = 1)
        Button(onClick = onIncrease) { Text("+") }
    }
}

@Composable
private fun SettingToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    FocusCard(
        focusKey = "settings:toggle:$label",
        restoreFocusKey = null,
        onFocusRestored = {},
        onClick = { onChange(!value) },
        selected = value,
        accessibilityLabel = "$label, ${if (value) "on" else "off"}",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label)
            Text(if (value) "On" else "Off", color = if (value) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadOnlyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatReviewTime(epochSeconds: Double): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date((epochSeconds * 1_000.0).toLong()))
