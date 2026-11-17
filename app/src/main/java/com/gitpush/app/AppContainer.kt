package com.gitpush.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.Room
import com.gitpush.app.core.GitEngine
import com.gitpush.app.data.AppDatabase
import com.gitpush.app.data.AuthRepository
import com.gitpush.app.data.GitHubApi
import com.gitpush.app.data.SecureStore
import com.gitpush.app.data.SettingsRepository
import com.gitpush.app.data.SourceStager
import com.gitpush.app.push.PushCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependency graph: small, explicit, no code generation. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val secure = SecureStore(app)
    val settings = SettingsRepository(app)
    val api = GitHubApi(http, json) { secure.get("github_token") }
    val auth = AuthRepository(secure, http, json, api)
    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "gitpush.db").build()
    val engine = GitEngine(json)
    val stager = SourceStager(app, engine)
    val coordinator = PushCoordinator(app, appScope, auth, settings, stager, engine, db)

    /** A ZIP shared into the app from another app; consumed by the push screen. */
    val sharedZip = MutableStateFlow<Uri?>(null)
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
