package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.services.FirestoreConfig
import com.example.ui.theme.FolioVaultTheme
import com.example.views.FolioVaultRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security Requirement (Locked Decision #6):
        // Apply FLAG_SECURE to the entire window BEFORE setContent so no screen,
        // recent-apps snapshot, or screen recording can capture protected content.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        FirestoreConfig.initializeAppCheck(applicationContext)
        enableEdgeToEdge()

        setContent {
            FolioVaultTheme {
                FolioVaultRoot()
            }
        }
    }
}
