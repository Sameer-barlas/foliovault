package com.example.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.controllers.AuthController
import com.example.controllers.AuthUiState
import com.example.models.Book
import com.example.models.UserRole
import com.example.views.admin.AdminHubScreen
import com.example.views.auth.DeviceLockedScreen
import com.example.views.auth.LoginScreen
import com.example.views.auth.NotAuthorizedScreen
import com.example.views.reader.BookReaderScreen
import com.example.views.reader.LibraryScreen

@Composable
fun FolioVaultRoot(
    authController: AuthController = viewModel(),
) {
    val authState by authController.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (val state = authState) {
        is AuthUiState.CheckingSession -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        is AuthUiState.Welcome -> {
            LoginScreen(
                loading = state.loading,
                errorMessage = state.errorMessage,
                configMissing = state.configMissing,
                onContinueWithGoogle = { authController.signInWithGoogle(context) },
            )
        }

        is AuthUiState.NotAuthorized -> {
            NotAuthorizedScreen(
                email = state.email,
                reasonMessage = state.reasonMessage,
                onSwitchAccount = { authController.returnToWelcome() },
            )
        }

        is AuthUiState.DeviceLocked -> {
            DeviceLockedScreen(
                email = state.email,
                boundDeviceLabel = state.boundDeviceLabel,
                wasRevoked = state.wasRevoked,
                onRetrySignIn = { authController.signInWithGoogle(context) },
                onSwitchAccount = { authController.returnToWelcome() },
            )
        }

        is AuthUiState.Authenticated -> {
            key(state.uid) {
                AuthenticatedRoleRouter(
                    session = state,
                    onSignOut = { authController.signOut() },
                )
            }
        }
    }
}

@Composable
private fun AuthenticatedRoleRouter(
    session: AuthUiState.Authenticated,
    onSignOut: () -> Unit,
) {
    var openBook by remember(session.uid) { mutableStateOf<Book?>(null) }
    var readerPreviewActive by remember(session.uid) { mutableStateOf(false) }

    val activeBook = openBook
    if (activeBook != null) {
        BookReaderScreen(
            book = activeBook,
            watermarkEmail = session.email,
            onBack = { openBook = null },
        )
        return
    }

    if (session.role == UserRole.ADMIN && !readerPreviewActive) {
        AdminHubScreen(
            adminEmail = session.email,
            onEnterReaderPreview = { readerPreviewActive = true },
            onSignOut = onSignOut,
        )
    } else {
        LibraryScreen(
            userEmail = session.email,
            adminPreviewMode = session.role == UserRole.ADMIN && readerPreviewActive,
            onOpenBook = { book -> openBook = book },
            onExitAdminPreview = if (session.role == UserRole.ADMIN) {
                { readerPreviewActive = false }
            } else {
                null
            },
            onSignOut = onSignOut,
        )
    }
}
