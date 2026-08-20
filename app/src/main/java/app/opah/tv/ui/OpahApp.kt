package app.opah.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.opah.tv.PictureInPictureRequest
import app.opah.tv.R
import app.opah.tv.data.model.Camera
import app.opah.tv.data.model.ReviewItem

internal enum class AppDestination(val label: String, val iconRes: Int) {
    HOME("Home", R.drawable.ic_home),
    CAMERAS("Cameras", R.drawable.ic_videocam),
    BIRDSEYE("Birdseye", R.drawable.ic_birdseye),
    REVIEW("Review", R.drawable.ic_review),
    INFORMATION("Information", R.drawable.ic_info),
    SETTINGS("Settings", R.drawable.ic_settings),
    ABOUT("About", R.drawable.ic_about),
}

private enum class SettingsPage { MAIN, DIAGNOSTICS }

internal enum class RootSurface { PLAYBACK, CONNECTED, LOADING, RECOVERY, SETUP }

internal fun rootSurface(
    hasPlayback: Boolean,
    hasConnectedContent: Boolean,
    loading: Boolean,
    connectionWorkInProgress: Boolean = false,
    savedSessionRecoveryAvailable: Boolean = false,
): RootSurface = when {
    hasPlayback -> RootSurface.PLAYBACK
    hasConnectedContent -> RootSurface.CONNECTED
    connectionWorkInProgress -> RootSurface.SETUP
    loading -> RootSurface.LOADING
    savedSessionRecoveryAvailable -> RootSurface.RECOVERY
    else -> RootSurface.SETUP
}

@Composable
fun OpahApp(
    viewModel: Phase0ViewModel,
    pictureInPictureAvailable: Boolean = false,
    pictureInPictureActive: Boolean = false,
    onEnterPictureInPicture: (PictureInPictureRequest) -> Boolean = { false },
    onFullyDrawn: () -> Unit = {},
    onExitRequested: () -> Unit = {},
    initialDestinationName: String? = null,
    initialSettingsPageName: String? = null,
    initialInformationTabName: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OpahTheme(state.settings.appearanceMode, state.settings.customThemeColors) {
        var destinationName by rememberSaveable(initialDestinationName) {
            mutableStateOf(initialDestinationName ?: AppDestination.HOME.name)
        }
        var returnDestinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
        var restoreFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var playbackReturnFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsPageName by rememberSaveable(initialSettingsPageName) {
            mutableStateOf(initialSettingsPageName ?: SettingsPage.MAIN.name)
        }
        var restoreSettingsDiagnosticsFocus by rememberSaveable { mutableStateOf(false) }
        val homeCameraFocusRequester = remember { FocusRequester() }
        val birdseyeFocusRequester = remember { FocusRequester() }
        val settingsDiagnosticsFocusRequester = remember { FocusRequester() }
        val diagnosticsBackFocusRequester = remember { FocusRequester() }
        val destination = runCatching { AppDestination.valueOf(destinationName) }
            .getOrDefault(AppDestination.HOME)
        val settingsPage = runCatching { SettingsPage.valueOf(settingsPageName) }
            .getOrDefault(SettingsPage.MAIN)
        val playback = state.playback
        val surface = rootSurface(
            hasPlayback = playback != null,
            hasConnectedContent = state.activeProfile != null && state.snapshot != null,
            loading = state.loading,
            connectionWorkInProgress = state.connectionWorkInProgress,
            savedSessionRecoveryAvailable = state.savedSessionRecoveryAvailable,
        )

        LaunchedEffect(surface) {
            if (surface == RootSurface.CONNECTED || surface == RootSurface.SETUP) {
                withFrameNanos { }
                onFullyDrawn()
            }
        }

        LaunchedEffect(surface, destination, settingsPage) {
            if (surface == RootSurface.CONNECTED) {
                playbackReturnFocusKey?.let { focusKey ->
                    restoreFocusKey = focusKey
                    playbackReturnFocusKey = null
                }
            }
            if (surface != RootSurface.CONNECTED || destination != AppDestination.SETTINGS) return@LaunchedEffect
            withFrameNanos { }
            if (settingsPage == SettingsPage.DIAGNOSTICS) {
                diagnosticsBackFocusRequester.requestFocus()
            }
        }

        when (surface) {
            RootSurface.PLAYBACK -> {
                val playbackRequest = requireNotNull(playback)
                val cameras = state.snapshot?.cameras.orEmpty()
                val currentIndex = cameras.indexOfFirst { it.name == state.activeCameraName }
                val previous = cameras.wrappedAt(currentIndex - 1).takeIf { currentIndex >= 0 }
                val next = cameras.wrappedAt(currentIndex + 1).takeIf { currentIndex >= 0 }
                PlaybackScreen(
                    request = playbackRequest,
                    onBack = {
                        destinationName = returnDestinationName
                        restoreFocusKey = playbackReturnFocusKey
                        playbackReturnFocusKey = null
                        viewModel.closePlayback()
                    },
                    onSessionExpired = viewModel::sessionExpired,
                    preferRtpTcp = state.settings.preferRtpTcp,
                    startMuted = state.settings.startLiveMuted,
                    diagnosticsAvailable = state.settings.diagnosticsEnabled,
                    pictureInPictureAvailable = pictureInPictureAvailable,
                    pictureInPictureActive = pictureInPictureActive,
                    onEnterPictureInPicture = onEnterPictureInPicture,
                    onPrevious = previous?.let { camera -> { viewModel.playAutomatic(camera) } },
                    onNext = next?.let { camera -> { viewModel.playAutomatic(camera) } },
                )
            }

            RootSurface.CONNECTED -> {
                BackHandler(
                    enabled = destination != AppDestination.HOME ||
                        state.review.selectedItemId != null ||
                        settingsPage != SettingsPage.MAIN,
                ) {
                    if (destination == AppDestination.REVIEW && state.review.selectedItemId != null) {
                        viewModel.closeReviewItem()
                    } else if (destination == AppDestination.SETTINGS && settingsPage != SettingsPage.MAIN) {
                        restoreSettingsDiagnosticsFocus = true
                        settingsPageName = SettingsPage.MAIN.name
                    } else {
                        destinationName = AppDestination.HOME.name
                        settingsPageName = SettingsPage.MAIN.name
                    }
                }
                ConnectedShell(
                    destination = destination,
                    birdseyeAvailable = state.snapshot?.birdseye?.playable == true,
                    onDestination = {
                        destinationName = it.name
                        settingsPageName = SettingsPage.MAIN.name
                        restoreSettingsDiagnosticsFocus = false
                        restoreFocusKey = null
                        playbackReturnFocusKey = null
                    },
                    contentClaimsInitialFocus = restoreFocusKey != null,
                    contentEntryFocusRequester = when {
                        destination == AppDestination.HOME && state.snapshot?.cameras?.isNotEmpty() == true ->
                            homeCameraFocusRequester
                        destination == AppDestination.BIRDSEYE -> birdseyeFocusRequester
                        else -> null
                    },
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        state.errorMessage?.let { error ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 34.dp, top = 18.dp, end = 34.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ScreenMessage(error, isError = true, modifier = Modifier.weight(1f))
                                Button(onClick = viewModel::clearError) { Text("Dismiss") }
                            }
                        }
                        when (destination) {
                            AppDestination.HOME -> HomeScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                initialCameraFocusRequester = homeCameraFocusRequester,
                                onOpenCameras = { destinationName = AppDestination.CAMERAS.name },
                                onOpenReview = { destinationName = AppDestination.REVIEW.name },
                                onPlayCamera = { camera, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playAutomatic(camera)
                                },
                                onPlayReview = { item, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.HOME.name
                                    viewModel.playReview(item)
                                },
                                cachedBitmap = { camera ->
                                    viewModel.cachedCameraImage(camera)?.bitmap
                                },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                                cachedReviewBitmap = { item -> viewModel.cachedReviewImage(item)?.bitmap },
                                refreshReviewBitmap = { item, height ->
                                    viewModel.refreshReviewImage(item, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.CAMERAS -> CamerasScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onPlayCamera = { camera, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.CAMERAS.name
                                    viewModel.playAutomatic(camera)
                                },
                                onPlayStream = { camera, option, key ->
                                    playbackReturnFocusKey = key
                                    returnDestinationName = AppDestination.CAMERAS.name
                                    viewModel.playStream(camera, option)
                                },
                                cachedBitmap = { camera ->
                                    viewModel.cachedCameraImage(camera)?.bitmap
                                },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.BIRDSEYE -> BirdseyeScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                initialFocusRequester = birdseyeFocusRequester,
                                onPlay = {
                                    playbackReturnFocusKey = "birdseye:watch"
                                    returnDestinationName = AppDestination.BIRDSEYE.name
                                    viewModel.playBirdseye()
                                },
                                cachedBitmap = { camera -> viewModel.cachedCameraImage(camera)?.bitmap },
                                refreshBitmap = { camera, height ->
                                    viewModel.refreshCameraImage(camera, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.REVIEW -> ReviewScreen(
                                state = state,
                                restoreFocusKey = restoreFocusKey,
                                onFocusRestored = { restoreFocusKey = null },
                                onLoad = viewModel::loadReview,
                                onSeverity = viewModel::updateReviewSeverity,
                                onCamera = viewModel::updateReviewCamera,
                                onLabel = viewModel::updateReviewLabel,
                                onZone = viewModel::updateReviewZone,
                                onTimeRange = viewModel::updateReviewTimeRange,
                                onResetFilters = viewModel::resetReviewFilters,
                                onSelectItem = { item, key ->
                                    restoreFocusKey = key
                                    viewModel.selectReviewItem(item)
                                },
                                onCloseItem = viewModel::closeReviewItem,
                                onPlayItem = { item ->
                                    returnDestinationName = AppDestination.REVIEW.name
                                    viewModel.playReview(item)
                                },
                                onMarkReviewed = viewModel::markReviewReviewed,
                                cachedBitmap = { item -> viewModel.cachedReviewImage(item)?.bitmap },
                                refreshBitmap = { item, height ->
                                    viewModel.refreshReviewImage(item, height).getOrNull()?.bitmap
                                },
                            )

                            AppDestination.INFORMATION -> InformationScreen(
                                state = state,
                                onLoad = { viewModel.loadInformation() },
                                onRefresh = { viewModel.loadInformation(force = true) },
                                initialTabName = initialInformationTabName,
                            )

                            AppDestination.SETTINGS -> when (settingsPage) {
                                SettingsPage.MAIN -> SettingsScreen(
                                    state = state,
                                    onAppearance = viewModel::updateAppearance,
                                    onCustomTheme = viewModel::updateCustomTheme,
                                    onStreamPreference = viewModel::updateStreamPreference,
                                    onPreferRtpTcp = viewModel::updatePreferRtpTcp,
                                    onStartMuted = viewModel::updateStartLiveMuted,
                                    onDiagnostics = viewModel::updateDiagnosticsEnabled,
                                    onUpdateRtspRoute = viewModel::updateRtspRoute,
                                    onOpenDiagnostics = {
                                        restoreSettingsDiagnosticsFocus = false
                                        settingsPageName = SettingsPage.DIAGNOSTICS.name
                                    },
                                    diagnosticsFocusRequester = settingsDiagnosticsFocusRequester,
                                    restoreDiagnosticsFocus = restoreSettingsDiagnosticsFocus,
                                    onDiagnosticsFocusRestored = {
                                        restoreSettingsDiagnosticsFocus = false
                                    },
                                    onSignOut = { viewModel.logout(false) },
                                    onForgetServer = { viewModel.logout(true) },
                                )

                                SettingsPage.DIAGNOSTICS -> DiagnosticsScreen(
                                    state = state,
                                    onRefresh = viewModel::refresh,
                                    onBack = {
                                        restoreSettingsDiagnosticsFocus = true
                                        settingsPageName = SettingsPage.MAIN.name
                                    },
                                    initialFocusRequester = diagnosticsBackFocusRequester,
                                )
                            }

                            AppDestination.ABOUT -> AboutScreen()
                        }
                    }
                }
            }

            RootSurface.LOADING -> StartupLoadingScreen(state.statusMessage)

            RootSurface.RECOVERY -> StartupRecoveryScreen(
                message = state.errorMessage,
                onRetry = viewModel::retrySavedSession,
                onConnectionSettings = viewModel::showConnectionSetup,
            )

            RootSurface.SETUP -> ConnectionSetupScreen(
                state = state,
                onDismissError = viewModel::clearError,
                onTestConnection = viewModel::testConnection,
                onConnect = viewModel::connect,
                onForget = { viewModel.logout(true) },
                onExitRequested = onExitRequested,
            )
        }
    }
}

@Composable
private fun ConnectedShell(
    destination: AppDestination,
    birdseyeAvailable: Boolean,
    onDestination: (AppDestination) -> Unit,
    contentClaimsInitialFocus: Boolean,
    contentEntryFocusRequester: FocusRequester?,
    content: @Composable () -> Unit,
) {
    var railHasFocus by rememberSaveable { mutableStateOf(false) }
    var railActivated by rememberSaveable { mutableStateOf(false) }
    var railEntryRequest by remember { mutableIntStateOf(0) }
    var contentFocusRequest by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val visibleDestinations = remember(birdseyeAvailable) {
        AppDestination.entries.filter { it != AppDestination.BIRDSEYE || birdseyeAvailable }
    }
    val navRequesters = remember { AppDestination.entries.associateWith { FocusRequester() } }
    val railClaimsInitialFocus = remember { !contentClaimsInitialFocus }
    val railExpanded = railHasFocus && railActivated
    // Width animation forces every image-heavy destination to remeasure on every
    // animation frame. An immediate rail transition is substantially faster on TV SoCs.
    val collapsedRailWidth = 54.dp
    val railWidth = if (railExpanded) 190.dp else collapsedRailWidth
    LaunchedEffect(Unit) {
        if (!railClaimsInitialFocus) return@LaunchedEffect
        withFrameNanos { }
        navRequesters.getValue(destination).requestFocus()
    }
    LaunchedEffect(railEntryRequest, destination) {
        if (railEntryRequest == 0 || !railHasFocus) return@LaunchedEffect
        withFrameNanos { }
        navRequesters.getValue(destination).requestFocus()
    }
    LaunchedEffect(contentFocusRequest, destination) {
        if (contentFocusRequest == 0) return@LaunchedEffect
        withFrameNanos { }
        focusManager.moveFocus(FocusDirection.Right)
        contentFocusRequest = 0
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The destination always receives the collapsed-rail inset. Expanding the
        // rail now overlays it instead of changing its constraints and forcing an
        // image-heavy screen to be measured again.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = collapsedRailWidth)
                .onFocusEvent { if (it.hasFocus) railActivated = true },
        ) {
            content()
        }
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(railWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .onFocusEvent { focusState ->
                    if (focusState.hasFocus && !railHasFocus) railEntryRequest += 1
                    railHasFocus = focusState.hasFocus
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp,
                            Key.DirectionDown,
                            Key.DirectionLeft,
                            Key.DirectionRight -> railActivated = true
                            else -> Unit
                        }
                    }
                    false
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.opah_brand_mark),
                        contentDescription = null,
                        modifier = Modifier.size(if (railExpanded) 34.dp else 32.dp),
                    )
                    if (railExpanded) {
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Opah",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            visibleDestinations.forEachIndexed { index, item ->
                FocusCard(
                    focusKey = "nav:${item.name.lowercase()}",
                    restoreFocusKey = null,
                    onFocusRestored = {},
                    onClick = {
                        onDestination(item)
                        contentFocusRequest += 1
                    },
                    selected = item == destination,
                    accessibilityLabel = item.label,
                    externalFocusRequester = navRequesters.getValue(item),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties {
                            up = if (index == 0) {
                                FocusRequester.Cancel
                            } else {
                                navRequesters.getValue(visibleDestinations[index - 1])
                            }
                            down = if (index == visibleDestinations.lastIndex) {
                                FocusRequester.Cancel
                            } else {
                                navRequesters.getValue(visibleDestinations[index + 1])
                            }
                            left = FocusRequester.Cancel
                            right = if (item == destination && contentEntryFocusRequester != null) {
                                contentEntryFocusRequester
                            } else {
                                FocusRequester.Default
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (railExpanded) 12.dp else 6.dp,
                                vertical = 11.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (railExpanded) {
                            Arrangement.spacedBy(12.dp)
                        } else {
                            Arrangement.Center
                        },
                    ) {
                        Image(
                            painter = painterResource(item.iconRes),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                if (item == destination) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                            modifier = Modifier.size(24.dp),
                        )
                        if (railExpanded) {
                            Text(
                                text = item.label,
                                maxLines = 1,
                                color = if (item == destination) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (item == destination) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun List<Camera>.wrappedAt(index: Int): Camera? {
    if (isEmpty()) return null
    val wrapped = ((index % size) + size) % size
    return get(wrapped)
}
