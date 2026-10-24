package com.kevinushey.quell

import android.app.Application
import com.kevinushey.quell.data.DatasetRepository
import com.kevinushey.quell.data.SettingsStore

class QuellApp : Application() {
    val repository: DatasetRepository by lazy { DatasetRepository(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
}
