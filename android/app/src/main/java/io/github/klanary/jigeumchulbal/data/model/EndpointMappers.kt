package io.github.klanary.jigeumchulbal.data.model

import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.local.RecentKind
import io.github.klanary.jigeumchulbal.data.local.RecentSearchEntity
import io.github.klanary.jigeumchulbal.data.remote.PlaceDto
import io.github.klanary.jigeumchulbal.data.remote.StationDto

// 검색·내 장소·최근 검색 항목을 경로 지점(Endpoint)으로 바꾼다.

fun StationDto.toEndpoint() = Endpoint(name, GeoPoint(lat, lng), EndpointKind.STATION, id)

fun PlaceDto.toEndpoint() = Endpoint(name, GeoPoint(lat, lng), EndpointKind.PLACE)

fun MyPlaceEntity.toEndpoint() = Endpoint(label, GeoPoint(lat, lng), EndpointKind.MY_PLACE, stationId)

fun RecentSearchEntity.toEndpoint(): Endpoint {
    val kind = when (kind) {
        RecentKind.STATION -> EndpointKind.STATION
        RecentKind.PLACE -> EndpointKind.PLACE
        RecentKind.PIN -> EndpointKind.PIN
    }
    return Endpoint(label, GeoPoint(lat, lng), kind, stationId)
}
