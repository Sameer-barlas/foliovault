package com.example.views.reader

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.controllers.ReaderController
import com.example.models.Book
import com.example.models.BookPage
import com.example.ui.theme.FolioVaultTheme
import com.example.ui.theme.MonoCounterTextStyle
import com.example.ui.theme.ReaderInkLight
import com.example.ui.theme.ReaderSurfaceDark
import com.example.ui.theme.ReaderSurroundDark

/**
 * Vertical paginated image reader on archival dark surround (#141210):
 * - 12-page cursor batching with automatic prefetch within 3 pages of end
 * - Pinch-to-zoom and double-tap zoom
 * - Shimmer placeholders during decode/fetch
 * - Floating `04 / 18` monospace page pill
 * - Translucent diagonal Gmail watermark on every page
 * - RAM bitmap cache automatically cleared on exit
 */
@Composable
fun BookReaderScreen(
    book: Book,
    watermarkEmail: String,
    onBack: () -> Unit,
    controller: ReaderController = viewModel(key = "reader_${book.id}"),
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val nextScale = (zoomScale * zoomChange).coerceIn(1f, 3f)
        zoomScale = nextScale
        panOffset = if (nextScale > 1.01f) {
            Offset(
                x = (panOffset.x + offsetChange.x).coerceIn(-600f, 600f),
                y = (panOffset.y + offsetChange.y).coerceIn(-600f, 600f),
            )
        } else {
            Offset.Zero
        }
    }

    LaunchedEffect(book.id) {
        controller.openBook(book)
    }

    DisposableEffect(book.id) {
        onDispose {
            controller.closeBook()
        }
    }

    BackHandler {
        controller.closeBook()
        onBack()
    }

    val firstVisibleIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }

    LaunchedEffect(firstVisibleIndex, state.pages.size) {
        if (state.pages.isNotEmpty()) {
            controller.onVisiblePageChanged(firstVisibleIndex)
        }
    }

    FolioVaultTheme(readerDarkSurround = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ReaderSurroundDark),
        ) {
            when {
                state.initialLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 72.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        repeat(2) {
                            PageShimmerPlaceholder(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                !state.errorMessage.isNullOrBlank() && state.pages.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ReaderInkLight,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(onClick = controller::retryLoad) {
                            Text(text = stringResource(R.string.action_try_again))
                        }
                    }
                }

                state.pages.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.reader_empty_pages),
                            style = MaterialTheme.typography.titleLarge,
                            color = ReaderInkLight,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (zoomScale > 1.05f) {
                                            zoomScale = 1f
                                            panOffset = Offset.Zero
                                        } else {
                                            zoomScale = 2.2f
                                        }
                                    }
                                )
                            }
                            .transformable(state = transformableState, enabled = zoomScale > 1.01f),
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                top = 84.dp,
                                bottom = 92.dp,
                                start = 10.dp,
                                end = 10.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoomScale,
                                    scaleY = zoomScale,
                                    translationX = panOffset.x,
                                    translationY = panOffset.y,
                                )
                                .testTag("reader_pages_list"),
                        ) {
                            itemsIndexed(
                                items = state.pages,
                                key = { _, page -> page.id },
                            ) { index, page ->
                                ReaderPageCard(
                                    page = page,
                                    pageNumber = index + 1,
                                    bitmap = state.decodedBitmaps[page.id],
                                    watermarkEmail = watermarkEmail,
                                )
                            }

                            if (state.loadingNextBatch) {
                                item(key = "loading_footer") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 20.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating Top Reader Bar
            Surface(
                color = ReaderSurroundDark.copy(alpha = 0.88f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            controller.closeBook()
                            onBack()
                        },
                        modifier = Modifier.testTag("reader_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back_cd),
                            tint = ReaderInkLight,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = ReaderInkLight,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (book.author.isNotBlank()) {
                            Text(
                                text = book.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = ReaderInkLight.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (zoomScale > 1.05f) {
                        IconButton(
                            onClick = {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            },
                            modifier = Modifier.testTag("reader_reset_zoom_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ZoomOutMap,
                                contentDescription = stringResource(R.string.reader_reset_zoom_cd),
                                tint = ReaderInkLight,
                            )
                        }
                    }
                }
            }

            // Floating Monospace Page Counter Pill (`04 / 18`)
            if (state.pages.isNotEmpty()) {
                val currentDisplay = (state.currentVisibleIndex + 1)
                    .coerceIn(1, state.totalDisplayCount.coerceAtLeast(1))
                Surface(
                    color = ReaderSurfaceDark.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                        .testTag("reader_page_pill"),
                ) {
                    Text(
                        text = stringResource(
                            R.string.reader_page_counter,
                            currentDisplay,
                            state.totalDisplayCount,
                        ),
                        style = MonoCounterTextStyle,
                        color = ReaderInkLight,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderPageCard(
    page: BookPage,
    pageNumber: Int,
    bitmap: Bitmap?,
    watermarkEmail: String,
) {
    val ratio = if (page.width > 0 && page.height > 0) {
        (page.width.toFloat() / page.height.toFloat()).coerceIn(0.45f, 1.8f)
    } else {
        0.72f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(ReaderSurfaceDark),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page $pageNumber ${page.caption}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // Translucent Diagonal Gmail Watermark Overlay (Locked Decision #6)
            GmailWatermarkOverlay(
                email = watermarkEmail,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PageShimmerPlaceholder(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GmailWatermarkOverlay(
    email: String,
    modifier: Modifier = Modifier,
) {
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(38, 124, 45, 45) // Translucent oxblood ink
            textSize = 34f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier = modifier) {
        val label = email.ifBlank { "Redoo Protected" }
        val stepY = (size.height / 3.2f).coerceAtLeast(220f)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.rotate(-26f, size.width / 2f, size.height / 2f)
        var y = stepY * 0.6f
        while (y < size.height * 1.2f) {
            canvas.drawText(label, size.width / 2f, y, paint)
            y += stepY
        }
        canvas.restore()
    }
}

@Composable
private fun PageShimmerPlaceholder(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "page_shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_shift",
    )

    Box(
        modifier = modifier
            .aspectRatio(0.72f)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1F1C19),
                        Color(0xFF2D2722),
                        Color(0xFF1F1C19),
                    ),
                    start = Offset(shift - 300f, 0f),
                    end = Offset(shift, 400f),
                )
            ),
    )
}
