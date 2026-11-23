package io.github.klanary.jigeumchulbal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 통합 검색·지도 핀 선택이 이전 화면(경로 편집, 설정)에 돌려주는 선택 결과. */
@Serializable
data class PickResult(val slot: Slot?, val endpoint: Endpoint)

private const val PICK_RESULT_KEY = "pick_result"

/** 이전 화면의 SavedStateHandle에 결과를 JSON으로 넣고 뒤로 간다. */
fun NavController.returnPick(result: PickResult) {
    previousBackStackEntry?.savedStateHandle?.set(PICK_RESULT_KEY, Json.encodeToString(result))
    popBackStack()
}

/** 돌아온 선택 결과를 한 번만 전달한다(꺼낸 뒤 지운다). */
@Composable
fun NavBackStackEntry.OnPickResult(onResult: (PickResult) -> Unit) {
    val encoded by savedStateHandle.getStateFlow<String?>(PICK_RESULT_KEY, null).collectAsStateWithLifecycle()
    LaunchedEffect(encoded) {
        val value = encoded ?: return@LaunchedEffect
        savedStateHandle.remove<String>(PICK_RESULT_KEY)
        onResult(Json.decodeFromString<PickResult>(value))
    }
}
