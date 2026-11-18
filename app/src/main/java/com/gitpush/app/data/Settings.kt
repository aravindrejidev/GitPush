package com.gitpush.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gitpush.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val clientId: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val defaultMessage: String = "Update via GitPush",
    val respectGitignore: Boolean = true,
    val stripZipRoot: Boolean = true,
    val maxFileMb: Int = 50,
    val threads: Int = 0, // 0 = automatic
    val excludedDirs: String = DEFAULT_EXCLUDED_DIRS,
    val extraIgnores: String = "",
    val keepStaged: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val updateRepo: String = BuildConfig.UPDATE_REPO,
    val lastUpdateCheck: Long = 0L,
) {
    val excludedDirSet: Set<String>
        get() = excludedDirs.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    val extraIgnoreLines: List<String>
        get() = extraIgnores.lines().map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_EXCLUDED_DIRS = ".git\nnode_modules\n.gradle\n.idea\n__pycache__\n.DS_Store"
    }
}

object SettingKeys {
    val clientId = stringPreferencesKey("client_id")
    val authorName = stringPreferencesKey("author_name")
    val authorEmail = stringPreferencesKey("author_email")
    val defaultMessage = stringPreferencesKey("default_message")
    val respectGitignore = booleanPreferencesKey("respect_gitignore")
    val stripZipRoot = booleanPreferencesKey("strip_zip_root")
    val maxFileMb = intPreferencesKey("max_file_mb")
    val threads = intPreferencesKey("threads")
    val excludedDirs = stringPreferencesKey("excluded_dirs")
    val extraIgnores = stringPreferencesKey("extra_ignores")
    val keepStaged = booleanPreferencesKey("keep_staged")
    val themeMode = stringPreferencesKey("theme_mode")
    val dynamicColor = booleanPreferencesKey("dynamic_color")
    val updateRepo = stringPreferencesKey("update_repo")
    val lastUpdateCheck = longPreferencesKey("last_update_check")
}

class SettingsRepository(private val context: Context) {
    val flow: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            clientId = p[SettingKeys.clientId] ?: d.clientId,
            authorName = p[SettingKeys.authorName] ?: d.authorName,
            authorEmail = p[SettingKeys.authorEmail] ?: d.authorEmail,
            defaultMessage = p[SettingKeys.defaultMessage] ?: d.defaultMessage,
            respectGitignore = p[SettingKeys.respectGitignore] ?: d.respectGitignore,
            stripZipRoot = p[SettingKeys.stripZipRoot] ?: d.stripZipRoot,
            maxFileMb = p[SettingKeys.maxFileMb] ?: d.maxFileMb,
            threads = p[SettingKeys.threads] ?: d.threads,
            excludedDirs = p[SettingKeys.excludedDirs] ?: d.excludedDirs,
            extraIgnores = p[SettingKeys.extraIgnores] ?: d.extraIgnores,
            keepStaged = p[SettingKeys.keepStaged] ?: d.keepStaged,
            themeMode = runCatching { ThemeMode.valueOf(p[SettingKeys.themeMode] ?: "") }.getOrDefault(d.themeMode),
            dynamicColor = p[SettingKeys.dynamicColor] ?: d.dynamicColor,
            updateRepo = p[SettingKeys.updateRepo] ?: d.updateRepo,
            lastUpdateCheck = p[SettingKeys.lastUpdateCheck] ?: d.lastUpdateCheck,
        )
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }
}
