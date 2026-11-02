package io.github.klanary.jigeumchulbal.location

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * 위치 권한 요청 함수를 돌려준다. "현재 위치"를 처음 쓸 때 부른다(스펙 4.4).
 * 대략적인 위치(COARSE)만 허용해도 출발지로 충분하므로 둘 중 하나라도 허용이면 true다.
 */
@Composable
fun rememberLocationPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        currentOnResult(it.values.any { granted -> granted })
    }
    return remember(launcher) {
        {
            launcher.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            )
        }
    }
}
