package io.github.klanary.jigeumchulbal.ui.mappick

import io.github.klanary.jigeumchulbal.MainDispatcherRule
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import io.github.klanary.jigeumchulbal.data.model.EndpointKind
import io.github.klanary.jigeumchulbal.data.model.GeoPoint
import io.github.klanary.jigeumchulbal.data.remote.ApiResult
import io.github.klanary.jigeumchulbal.data.remote.NearestStationDto
import io.github.klanary.jigeumchulbal.data.remote.ReverseDto
import io.github.klanary.jigeumchulbal.fakes.FakePlaceRepository
import io.github.klanary.jigeumchulbal.fakes.FakeRecentSearchRepository
import io.github.klanary.jigeumchulbal.ui.Slot
import io.github.klanary.jigeumchulbal.ui.common.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapPickViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val places = FakePlaceRepository()
    private val p1 = GeoPoint(37.55, 126.92)
    private val p2 = GeoPoint(37.5571, 126.9245)

    @Test fun onlyLastCameraPositionIsReversed() = runTest {
        val vm = MapPickViewModel(places, FakeRecentSearchRepository())
        vm.onCameraIdle(p1)
        advanceTimeBy(300)
        vm.onCameraIdle(p2)
        advanceUntilIdle()
        assertEquals(listOf(p2), places.reverseCalls)
    }

    @Test fun seaShowsNoAddressAndNoStation() = runTest {
        places.reverseResult = ApiResult.Ok(ReverseDto(null, null))
        val vm = MapPickViewModel(places, FakeRecentSearchRepository())
        vm.onCameraIdle(p1)
        advanceUntilIdle()
        val card = (vm.card.value as UiState.Content).data
        assertEquals("주소 정보 없음" to "근처 2km 안에 역이 없어요", card.addressText to card.stationText)
    }

    @Test fun nearestStationText() = runTest {
        places.reverseResult =
            ApiResult.Ok(ReverseDto("서울 마포구 양화로 160", NearestStationDto("홍대입구", "홍대입구", 320)))
        val vm = MapPickViewModel(places, FakeRecentSearchRepository())
        vm.onCameraIdle(p2)
        advanceUntilIdle()
        assertEquals("가장 가까운 역: 홍대입구 · 320m", (vm.card.value as UiState.Content).data.stationText)
    }

    @Test fun pickUsesAddressAsLabelAndRecordsRecent() = runTest {
        places.reverseResult = ApiResult.Ok(ReverseDto("서울 마포구 양화로 160", null))
        val recents = FakeRecentSearchRepository()
        val vm = MapPickViewModel(places, recents)
        vm.onCameraIdle(p2)
        advanceUntilIdle()
        val pick = vm.pick(Slot.ORIGIN)
        assertEquals(Endpoint("서울 마포구 양화로 160", p2, EndpointKind.PIN), pick.endpoint)
        assertEquals(listOf(pick.endpoint), recents.recorded)
    }
}
