package app.opah.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield

// Lets a ProductionTvInput that just advanced focus via Tab/keyboard-Next tell the field it
// landed on to resume editing immediately, instead of requiring a second remote-OK/click.
internal val LocalAdvanceIntoEditing = compositionLocalOf { mutableStateOf(false) }

@Composable
internal fun FocusCard(
    focusKey: String,
    restoreFocusKey: String?,
    onFocusRestored: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    accessibilityLabel: String = focusKey,
    externalFocusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val focused = remember { mutableStateOf(false) }
    val rememberedRequester = remember(focusKey) { FocusRequester() }
    val requester = externalFocusRequester ?: rememberedRequester
    LaunchedEffect(restoreFocusKey, enabled) {
        if (restoreFocusKey != focusKey || !enabled) return@LaunchedEffect
        for (attempt in 0 until 8) {
            withFrameNanos { }
            if (requester.requestFocus()) {
                onFocusRestored()
                break
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val focusedBorderColor = MaterialTheme.colorScheme.primary
    val selectedBorderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
    val restingBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    Box(
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged { focused.value = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (
                    enabled &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            // Expose each card as one useful accessibility node. Allowing all of
            // its image/text descendants into the tree makes every D-pad focus
            // move expensive whenever an accessibility service is enabled.
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = accessibilityLabel
                this.selected = selected
                if (!enabled) disabled()
                onClick {
                    if (enabled) onClick()
                    enabled
                }
            }
            .focusable(enabled)
            .clip(shape)
            .background(
                color = when {
                    !enabled -> MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
                    selected -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                    else -> MaterialTheme.colorScheme.surface
                },
                shape = shape,
            )
            // Read focus in the draw phase so ordinary D-pad movement redraws
            // the border without recomposing the card's image and text subtree.
            .drawWithContent {
                drawContent()
                val strokeWidth = if (focused.value) 3.dp.toPx() else 1.dp.toPx()
                val inset = strokeWidth / 2f
                drawRoundRect(
                    color = when {
                        focused.value -> focusedBorderColor
                        selected -> selectedBorderColor
                        else -> restingBorderColor
                    },
                    topLeft = Offset(inset, inset),
                    size = Size(
                        width = (size.width - strokeWidth).coerceAtLeast(0f),
                        height = (size.height - strokeWidth).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius((12.dp.toPx() - inset).coerceAtLeast(0f)),
                    style = Stroke(strokeWidth),
                )
            },
    ) {
        content()
    }
}

@Composable
internal fun CameraSnapshot(
    cameraName: String,
    cachedBitmap: () -> Bitmap?,
    refreshBitmap: suspend () -> Bitmap?,
    modifier: Modifier = Modifier,
    refreshMillis: Long = 10_000L,
    initialRefreshDelayMillis: Long = 0L,
) {
    var bitmap by remember(cameraName) { mutableStateOf(cachedBitmap()) }
    LaunchedEffect(cameraName) {
        if (initialRefreshDelayMillis > 0L) delay(initialRefreshDelayMillis)
        while (currentCoroutineContext().isActive) {
            refreshBitmap()?.let { bitmap = it }
            delay(refreshMillis)
        }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { current ->
            val imageBitmap = remember(current) { current.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            text = "Loading camera…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun ProductionTvInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    requestInitialFocus: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(requestInitialFocus) }
    var pendingMove by remember { mutableStateOf<FocusDirection?>(null) }
    var pendingAdvanceEditing by remember { mutableStateOf(false) }
    var returnToNavigation by remember { mutableStateOf(false) }
    val navigationFocusRequester = remember { FocusRequester() }
    val editorFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val advanceIntoEditing = LocalAdvanceIntoEditing.current

    // Level-triggered rather than event-triggered: reacts whenever focus and a pending advance
    // signal are simultaneously true, regardless of which one became true first. A one-shot
    // check inside onFocusChanged can miss the signal depending on exact event ordering.
    LaunchedEffect(focused, advanceIntoEditing.value) {
        if (focused && enabled && advanceIntoEditing.value) {
            advanceIntoEditing.value = false
            returnToNavigation = false
            editing = true
        }
    }

    LaunchedEffect(requestInitialFocus, enabled, editing, pendingMove) {
        if (!enabled) return@LaunchedEffect
        if (editing) {
            editorFocusRequester.requestFocus()
            keyboardController?.show()
        } else {
            // requestInitialFocus is intentionally excluded here: it's permanently true for
            // whichever field asked for it, so including it would make this field re-claim its
            // own navigation focus every time this effect reruns for any reason — including the
            // rerun right after it successfully moves focus away — snapping focus straight back.
            // The initial claim is instead handled by editing's initial value below.
            if (returnToNavigation || pendingMove != null) {
                navigationFocusRequester.requestFocus()
            }
            keyboardController?.hide()
            pendingMove?.let { direction ->
                yield()
                if (pendingAdvanceEditing) {
                    advanceIntoEditing.value = true
                    pendingAdvanceEditing = false
                }
                focusManager.moveFocus(direction)
                pendingMove = null
            }
            returnToNavigation = false
        }
    }

    fun finishEditing(direction: FocusDirection? = null, advanceEditing: Boolean = false) {
        editing = false
        pendingMove = direction
        returnToNavigation = true
        keyboardController?.hide()
        pendingAdvanceEditing = direction != null && advanceEditing
    }

    val transformedValue = remember(value, visualTransformation) {
        visualTransformation.filter(AnnotatedString(value)).text.text
    }
    Column(modifier = modifier.widthIn(max = 760.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusRequester(navigationFocusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                returnToNavigation = false
                                editing = true
                                true
                            }
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.Tab -> {
                                finishEditing(
                                    if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next,
                                    advanceEditing = true,
                                )
                                true
                            }
                            else -> false
                        }
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .pointerInput(enabled, editing) {
                    detectTapGestures {
                        if (enabled && !editing) {
                            returnToNavigation = false
                            editing = true
                        }
                    }
                }
                .focusable(enabled && !editing)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(9.dp))
                .border(
                    width = if (focused || editing) 3.dp else 1.dp,
                    color = if (focused || editing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    shape = RoundedCornerShape(9.dp),
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            if (editing) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = keyboardType,
                        imeAction = imeAction,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { finishEditing(FocusDirection.Down, advanceEditing = true) },
                        onDone = { finishEditing() },
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(editorFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        finishEditing(FocusDirection.Up, advanceEditing = true)
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        finishEditing(FocusDirection.Down, advanceEditing = true)
                                        true
                                    }
                                    Key.Tab -> {
                                        finishEditing(
                                            if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next,
                                            advanceEditing = true,
                                        )
                                        true
                                    }
                                    else -> false
                                }
                            }
                        },
                )
            } else {
                Text(
                    text = transformedValue.ifEmpty { placeholder },
                    color = if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 20.sp,
                )
            }
        }
    }
}

@Composable
internal fun ScreenMessage(
    message: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(9.dp))
            .padding(14.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal val PlaceholderScrim = Color.Black.copy(alpha = 0.58f)
