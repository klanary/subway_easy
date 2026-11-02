package io.github.klanary.jigeumchulbal.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var store: SettingsStore

    @Before fun setUp() {
        store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") },
        )
    }

    @After fun tearDown() = scope.cancel()

    @Test fun defaultsMatchSpec() = runTest {
        assertEquals(AppSettings(60, 120, 180, 30, false), store.settings.first())
    }

    @Test fun updatePersists() = runTest {
        store.update { it.copy(alightLeadSec = 180) }
        assertEquals(180, store.settings.first().alightLeadSec)
    }
}
