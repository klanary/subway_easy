package io.github.klanary.jigeumchulbal.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** 진행 중 세션을 DataStore에 JSON으로 저장한다(스펙 8.7). 없거나 읽지 못하면 세션이 없는 것으로 본다. */
class SessionStore(private val dataStore: DataStore<Preferences>, private val json: Json) {
    suspend fun load(): SessionState {
        val raw = try {
            dataStore.data.first()[SESSION_JSON]
        } catch (e: IOException) {
            null
        } ?: return SessionState.Idle
        return try {
            json.decodeFromString<SessionState>(raw)
        } catch (e: SerializationException) {
            SessionState.Idle
        } catch (e: IllegalArgumentException) {
            SessionState.Idle
        }
    }

    suspend fun save(state: SessionState) {
        dataStore.edit { it[SESSION_JSON] = json.encodeToString<SessionState>(state) }
    }

    private companion object {
        val SESSION_JSON = stringPreferencesKey("session_json")
    }
}
