package com.kevinushey.quell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevinushey.quell.ui.AppNav
import com.kevinushey.quell.ui.MainViewModel
import com.kevinushey.quell.ui.theme.QuellTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val pendingImports = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null)
            handleIntent(intent)

        setContent {
            val app by vm.appSettings.collectAsStateWithLifecycle()
            QuellTheme(themeMode = app.theme, dynamicColor = app.dynamicColor) {
                AppNav(vm, pendingImports, onImportsConsumed = { pendingImports.value = emptyList() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Files opened with or shared to this app arrive here. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(uris::add)
            Intent.ACTION_SEND -> intentUri(intent)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> intentUris(intent)?.let(uris::addAll)
        }
        if (uris.isNotEmpty())
            pendingImports.value = pendingImports.value + uris
    }

    @Suppress("DEPRECATION")
    private fun intentUri(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun intentUris(intent: Intent): List<Uri>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
}
