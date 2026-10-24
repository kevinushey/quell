package com.kevinushey.quell

import android.app.Application
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.SettingsStore
import com.kevinushey.quell.sync.FolderSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class QuellApp : Application() {
    val repository: DatasetRepository by lazy { DatasetRepository(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val sync: FolderSync by lazy { FolderSync(this, repository, settings) }

    /** Lives as long as the process: the mirror must not stop when a screen does. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        sync.start(appScope)
    }
}
