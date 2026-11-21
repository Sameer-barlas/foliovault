package com.example.views.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.controllers.AdminController
import com.example.controllers.AdminUiState
import com.example.models.AuthorizedUser
import com.example.models.Book
import com.example.models.UserRole
import com.example.ui.theme.MonoCounterTextStyle
import java.text.DateFormat

@Composable
fun AdminUsersTab(
    state: AdminUiState,
    controller: AdminController,
) {
    var showAddUserDialog by remember { mutableStateOf(false) }
    var userForGrants by remember { mutableStateOf<AuthorizedUser?>(null) }
    var userForReset by remember { mutableStateOf<AuthorizedUser?>(null) }
    var userForDelete by remember { mutableStateOf<AuthorizedUser?>(null) }

    val dateFormatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val superAdminEmails = remember {
        val set = mutableSetOf("muhamad517uet@gmail.com", "sayazsultan@gmail.com")
        BuildConfig.SUPER_ADMIN_EMAIL.lowercase().takeIf { it.isNotBlank() }?.let { set.add(it) }
        try {
            val field = BuildConfig::class.java.getField("SUPER_ADMIN_EMAILS")
            val raw = field.get(null) as? String
            raw?.split(",")?.forEach { email ->
                val clean = email.trim().lowercase()
                if (clean.isNotEmpty()) set.add(clean)
            }
        } catch (_: Exception) {}
        set
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = state.users, key = { it.email }) { user ->
                val isSuperAdmin = superAdminEmails.contains(user.email.lowercase())
                val grantedBookIds = state.accessGrants
                    .filter { it.email.equals(user.email, ignoreCase = true) }
                    .map { it.bookId }
                    .toSet()

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
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user.displayName.ifBlank { user.email },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = user.email,
                                    style = MonoCounterTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                color = if (user.parsedRole() == UserRole.ADMIN) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                shape = MaterialTheme.shapes.extraSmall,
                            ) {
                                Text(
                                    text = if (isSuperAdmin) "SUPER ADMIN" else user.role.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (user.parsedRole() == UserRole.ADMIN) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }

                        val boundText = if (!user.boundDeviceId.isNullOrBlank()) {
                            val label = user.boundDeviceLabel ?: "Android Device"
                            val timeStr = user.boundAt?.toDate()?.let { dateFormatter.format(it) } ?: ""
                            "Bound: $label" + if (timeStr.isNotEmpty()) " ($timeStr)" else ""
                        } else {
                            "Unbound (awaiting first sign-in)"
                        }

                        Text(
                            text = "$boundText • ${grantedBookIds.size} books",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = { userForGrants = user },
                                modifier = Modifier.testTag("manage_access_${user.email}"),
                            ) {
                                Icon(Icons.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Books")
                            }

                            OutlinedButton(
                                onClick = { userForReset = user },
                                enabled = !user.boundDeviceId.isNullOrBlank(),
                                modifier = Modifier.testTag("reset_device_${user.email}"),
                            ) {
                                Icon(Icons.Filled.PhonelinkErase, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.admin_reset_device))
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            if (!isSuperAdmin) {
                                IconButton(
                                    onClick = { userForDelete = user },
                                    modifier = Modifier.testTag("delete_user_${user.email}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Remove ${user.email}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }

        FloatingActionButton(
            onClick = { showAddUserDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .testTag("admin_add_user_fab"),
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = stringResource(R.string.admin_add_user))
        }
    }

    if (showAddUserDialog) {
        AddReaderDialog(
            onDismiss = { showAddUserDialog = false },
            onAdd = { email, name ->
                showAddUserDialog = false
                controller.addReader(email, name)
            },
        )
    }

    userForGrants?.let { user ->
        ManageBookAccessDialog(
            user = user,
            books = state.books,
            grantedBookIds = state.accessGrants
                .filter { it.email.equals(user.email, ignoreCase = true) }
                .map { it.bookId }
                .toSet(),
            onToggle = { bookId, granted ->
                controller.toggleBookAccess(user.email, bookId, granted)
            },
            onDismiss = { userForGrants = null },
        )
    }

    userForReset?.let { user ->
        ResetDeviceDialog(
            user = user,
            onDismiss = { userForReset = null },
            onConfirm = { reason ->
                userForReset = null
                controller.resetUserDevice(user.email, reason)
            },
        )
    }

    userForDelete?.let { user ->
        AlertDialog(
            onDismissRequest = { userForDelete = null },
            title = { Text("Remove ${user.email}?") },
            text = {
                Text("This revokes access immediately and deletes all book grants assigned to this Gmail.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        userForDelete = null
                        controller.removeUser(user.email)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Remove User")
                }
            },
            dismissButton = {
                TextButton(onClick = { userForDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AddReaderDialog(
    onDismiss: () -> Unit,
    onAdd: (email: String, displayName: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.admin_add_user)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Reader Gmail *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(email, displayName) },
                enabled = email.trim().contains("@"),
            ) {
                Text("Authorize")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ManageBookAccessDialog(
    user: AuthorizedUser,
    books: List<Book>,
    grantedBookIds: Set<String>,
    onToggle: (bookId: String, granted: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Book Access • ${user.displayName.ifBlank { user.email }}") },
        text = {
            if (books.isEmpty()) {
                Text("Create a book first to assign reader access.")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(items = books, key = { it.id }) { book ->
                        val checked = book.id in grantedBookIds
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked -> onToggle(book.id, isChecked) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = book.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = "${book.pageCount} pages",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun ResetDeviceDialog(
    user: AuthorizedUser,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset Bound Device?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Unbinds \"${user.boundDeviceLabel ?: "current device"}\" for ${user.email}. Their old phone will be logged out immediately and this action is logged in the audit trail.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason note (optional)") },
                    placeholder = { Text("e.g., Replaced phone or factory reset") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.admin_reset_device))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
