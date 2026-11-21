package com.example.views.admin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.controllers.AdminController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminHubScreen(
    adminEmail: String,
    onEnterReaderPreview: () -> Unit,
    onSignOut: () -> Unit,
    controller: AdminController = viewModel(key = "admin_$adminEmail"),
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(adminEmail) {
        controller.initialize(email = adminEmail, isAdmin = true)
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            controller.consumeSnackbar()
        }
    }

    val tabs = listOf(
        Triple(stringResource(R.string.admin_tab_books), Icons.Filled.MenuBook, "admin_tab_books"),
        Triple(stringResource(R.string.admin_tab_users), Icons.Filled.People, "admin_tab_users"),
        Triple(stringResource(R.string.admin_tab_audit), Icons.Filled.History, "admin_tab_audit"),
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.admin_hub_title),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = adminEmail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onEnterReaderPreview,
                            modifier = Modifier.testTag("admin_reader_preview_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoStories,
                                contentDescription = stringResource(R.string.admin_reader_preview),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = controller::refreshAll,
                            modifier = Modifier.testTag("admin_refresh_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh admin data",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onSignOut,
                            modifier = Modifier.testTag("admin_sign_out_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = stringResource(R.string.action_sign_out),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                if (!useRail && state.selectedBookForPages == null) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        tabs.forEachIndexed { index, (label, icon, tag) ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                modifier = Modifier.testTag(tag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (!state.busyMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = state.busyMessage.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    if (useRail && state.selectedBookForPages == null) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            tabs.forEachIndexed { index, (label, icon, tag) ->
                                NavigationRailItem(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag(tag),
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            0 -> AdminBooksTab(
                                state = state,
                                controller = controller,
                            )

                            1 -> AdminUsersTab(
                                state = state,
                                controller = controller,
                            )

                            else -> AdminAuditTab(
                                logs = state.auditLogs,
                                loading = state.loading,
                            )
                        }
                    }
                }
            }
        }
    }
}
