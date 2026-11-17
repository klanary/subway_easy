package io.github.klanary.jigeumchulbal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import io.github.klanary.jigeumchulbal.data.model.StationRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 역 검색이 이전 화면(경로 편집)에 돌려주는 선택 결과. */
@Serializable
data class PickResult(val slot: Slot?, val station: StationRef)

private const val PICK_RESULT_KEY = "pick_result"

/**
 * 결과를 받을 화면까지 돌아가며 그 화면의 SavedStateHandle에 결과를 JSON으로 넣는다.
 * 고르는 화면(검색)이 겹쳐 열렸으면 그 화면들을 모두 닫는다.
 *
 * 공개 API(`popBackStack`, `currentBackStackEntry`)만 쓴다. `currentBackStack`은
 * Navigation 라이브러리 내부 전용(@RestrictTo)이라 lint 오류(RestrictedApi)가 난다.
 */
fun NavController.returnPick(result: PickResult) {
    // 이미 돌아갔으면(역을 빠르게 두 번 눌러 두 번 불림) 결과를 받을 화면까지 닫지 않는다.
    if (currentDestination?.hasRoute<SearchDest>() != true) return
    if (!popBackStack()) return
    while (currentDestination?.hasRoute<SearchDest>() == true) {
        if (!popBackStack()) return
    }
    currentBackStackEntry?.savedStateHandle?.set(PICK_RESULT_KEY, Json.encodeToString(result))
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
