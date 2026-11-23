package io.github.klanary.jigeumchulbal

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
    @Test fun serverBaseUrlEndsWithSlash() =
        assertTrue(BuildConfig.SERVER_BASE_URL.endsWith("/")) // Retrofit baseUrl 규칙

    @Test fun kakaoKeyIsNeverBlank() =
        assertTrue(BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank())
}
