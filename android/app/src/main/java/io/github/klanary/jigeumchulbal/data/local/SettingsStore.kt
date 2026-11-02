package io.github.klanary.jigeumchulbal.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 알림 설정(스펙 8.6 기본값). [permissionIntroShown]은 P4의 권한 안내 화면이 쓴다. */
data class AppSettings(
    val departMarginSec: Int = 60,
    val alightLeadSec: Int = 120,
    val stationLeadSec: Int = 180,
    val maxWaitMin: Int = 30,
    val permissionIntroShown: Boolean = false,
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[DEPART_MARGIN_SEC] = next.departMarginSec
            prefs[ALIGHT_LEAD_SEC] = next.alightLeadSec
            prefs[STATION_LEAD_SEC] = next.stationLeadSec
            prefs[MAX_WAIT_MIN] = next.maxWaitMin
            prefs[PERMISSION_INTRO_SHOWN] = next.permissionIntroShown
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            departMarginSec = this[DEPART_MARGIN_SEC] ?: defaults.departMarginSec,
            alightLeadSec = this[ALIGHT_LEAD_SEC] ?: defaults.alightLeadSec,
            stationLeadSec = this[STATION_LEAD_SEC] ?: defaults.stationLeadSec,
            maxWaitMin = this[MAX_WAIT_MIN] ?: defaults.maxWaitMin,
            permissionIntroShown = this[PERMISSION_INTRO_SHOWN] ?: defaults.permissionIntroShown,
        )
    }

    private companion object {
        val DEPART_MARGIN_SEC = intPreferencesKey("depart_margin_sec")
        val ALIGHT_LEAD_SEC = intPreferencesKey("alight_lead_sec")
        val STATION_LEAD_SEC = intPreferencesKey("station_lead_sec")
        val MAX_WAIT_MIN = intPreferencesKey("max_wait_min")
        val PERMISSION_INTRO_SHOWN = booleanPreferencesKey("permission_intro_shown")
    }
}
