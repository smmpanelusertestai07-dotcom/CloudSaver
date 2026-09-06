package app.novacalc

import android.app.Application
import app.novacalc.data.DataStoreHistoryRepository
import app.novacalc.data.DataStoreSettingsRepository
import app.novacalc.data.HistoryRepository
import app.novacalc.data.SettingsRepository
import app.novacalc.data.appDataStore

/** Hand-wired dependency container; the app is small enough not to need a DI framework. */
class AppContainer(application: Application) {
    private val dataStore = application.appDataStore()
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(dataStore)
    val historyRepository: HistoryRepository = DataStoreHistoryRepository(dataStore)
}

class CalcApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
