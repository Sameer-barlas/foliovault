package com.example.views.admin

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.controllers.AdminController
import com.example.controllers.AdminUiState
import com.example.models.Book
import com.example.models.BookPage
import com.example.models.StorageStats
import com.example.services.OrderIndexMath
import com.example.ui.theme.MonoCounterTextStyle
import java.util.Locale

@Composable
fun AdminBooksTab(
    state: AdminUiState,
    controller: AdminController,
) {
    val selectedBook = state.selectedBookForPages
    if (selectedBook != null) {
        BookPagesFilmstripView(
            book = selectedBook,
            state = state,
            onBack = controller::closePageFilmstrip,
            onLoadMore = { controller.loadNextFilmstripBatch(reset = false) },
            onInsertPage = controller::insertPageFromPhotoPicker,
            onDeletePage = controller::deletePage,
        )
        return
    }

    var editingBook by remember { mutableStateOf<Book?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var bookPendingDelete by remember { mutableStateOf<Book?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "quota_card") {
                SparkQuotaCard(stats = state.storageStats)
            }

            // Debug-only Seed Loader (Locked Decision #9: unreachable in release builds)
            if (BuildConfig.DEBUG) {
                item(key = "debug_seed_card") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Debug Seed Loader",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Creates 3 books × 15 Canvas parchment WebP pages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = controller::runDebugSeedLoader,
                                enabled = state.busyMessage == null,
                                modifier = Modifier.testTag("admin_debug_seed_button"),
                            ) {
                                Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Seed")
                            }
                        }
                    }
                }
            }

            if (state.loading && state.books.isEmpty()) {
                item(key = "books_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (state.books.isEmpty()) {
                item(key = "books_empty") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "No Books Created Yet",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "Tap New Book to create your first private edition and upload WebP pages.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            } else {
                items(items = state.books, key = { it.id }) { book ->
                    AdminBookRowCard(
                        book = book,
                        onManagePages = { controller.openPageFilmstrip(book) },
                        onEdit = { editingBook = book },
                        onDelete = { bookPendingDelete = book },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }

        FloatingActionButton(
            onClick = { showCreateDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .testTag("admin_add_book_fab"),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.admin_add_book))
        }
    }

    if (showCreateDialog) {
        BookEditorDialog(
            existing = null,
            onDismiss = { showCreateDialog = false },
            onSave = { title, author, desc, cat, coverUri ->
                showCreateDialog = false
                controller.createBook(title, author, desc, cat, coverUri)
            },
        )
    }

    editingBook?.let { book ->
        BookEditorDialog(
            existing = book,
            onDismiss = { editingBook = null },
            onSave = { title, author, desc, cat, coverUri ->
                editingBook = null
                controller.updateBook(book, title, author, desc, cat, coverUri)
            },
        )
    }

    bookPendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookPendingDelete = null },
            title = { Text("Delete \"${book.title}\"?") },
            text = {
                Text("This permanently deletes the book, all ${book.pageCount} pages, and all reader access grants for it.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        bookPendingDelete = null
                        controller.deleteBook(book)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete Book")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookPendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SparkQuotaCard(stats: StorageStats) {
    val usedMb = stats.totalImageBytes / (1024.0 * 1024.0)
    val totalMb = StorageStats.SPARK_STORAGE_LIMIT_BYTES / (1024.0 * 1024.0)
    val pct = (stats.usageFraction() * 100f).toInt()
    val isWarning = stats.warningActive()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = BorderStroke(
            1.dp,
            if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_quota_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.admin_quota_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = String.format(Locale.US, "%.1f / %.0f MiB (%d%%)", usedMb, totalMb, pct),
                    style = MonoCounterTextStyle,
                    color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = { stats.usageFraction() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            if (isWarning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.admin_quota_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminBookRowCard(
    book: Book,
    onManagePages: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = "${book.pageCount} pages",
                        style = MonoCounterTextStyle,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onManagePages,
                    modifier = Modifier.testTag("manage_pages_${book.id}"),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pages")
                }
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete ${book.title}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookPagesFilmstripView(
    book: Book,
    state: AdminUiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onInsertPage: (Uri, OrderIndexMath.InsertPosition, String) -> Unit,
    onDeletePage: (BookPage) -> Unit,
) {
    BackHandler { onBack() }

    var pendingPosition by remember { mutableStateOf<OrderIndexMath.InsertPosition>(OrderIndexMath.InsertPosition.End) }
    var pagePendingDelete by remember { mutableStateOf<BookPage?>(null) }

    // Zero-permission Android Photo Picker (Play Policy compliant)
    val pagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onInsertPage(uri, pendingPosition, "")
        }
    }

    fun pickImageFor(position: OrderIndexMath.InsertPosition) {
        pendingPosition = position
        pagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("filmstrip_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to books")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${book.pageCount} pages • WebP ≤ 150 KB target",
                        style = MonoCounterTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Quick insert Start / End bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = { pickImageFor(OrderIndexMath.InsertPosition.Start) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("insert_page_start_button"),
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Insert at Start")
            }
            Button(
                onClick = { pickImageFor(OrderIndexMath.InsertPosition.End) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("insert_page_end_button"),
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Append at End")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = state.filmstripPages,
                key = { _, page -> page.id },
            ) { idx, page ->
                val bmp = state.filmstripBitmaps[page.id]
                val kb = (page.imageBytes?.toBytes()?.size ?: 0) / 1024
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .aspectRatio(0.75f)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Page ${idx + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text("#${idx + 1}", style = MonoCounterTextStyle)
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Page ${idx + 1}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "${kb} KB • ${page.width}×${page.height} • idx ${"%.2f".format(page.orderIndex)}",
                                style = MonoCounterTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = { pickImageFor(OrderIndexMath.InsertPosition.Before(idx)) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("+ Before")
                                }
                                TextButton(
                                    onClick = { pickImageFor(OrderIndexMath.InsertPosition.After(idx)) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("+ After")
                                }
                            }
                        }

                        IconButton(onClick = { pagePendingDelete = page }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "Delete page ${idx + 1}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (state.filmstripHasMore) {
                item(key = "load_more_pages") {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.filmstripLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.filmstripLoading) "Loading next 12 pages..." else "Load Next 12 Pages")
                    }
                }
            }
        }
    }

    pagePendingDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { pagePendingDelete = null },
            title = { Text("Delete this page?") },
            text = { Text("The page WebP image will be removed and the book page count updated.") },
            confirmButton = {
                Button(
                    onClick = {
                        pagePendingDelete = null
                        onDeletePage(page)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pagePendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun BookEditorDialog(
    existing: Book?,
    onDismiss: () -> Unit,
    onSave: (title: String, author: String, description: String, category: String, coverUri: Uri?) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var author by remember { mutableStateOf(existing?.author.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var pickedCoverUri by remember { mutableStateOf<Uri?>(null) }

    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) pickedCoverUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Book" else "Edit Book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        coverPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (pickedCoverUri != null) "Cover Selected (≤ 60 KB WebP)" else "Pick Cover Image (Optional)")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, author, description, category, pickedCoverUri) },
                enabled = title.trim().isNotEmpty(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
