package io.github.klanary.jigeumchulbal.data.model

import kotlinx.serialization.Serializable

/** 위도, 경도. 서버와 주고받는 좌표는 항상 이 순서다(스펙 6.2). */
@Serializable
data class GeoPoint(val lat: Double, val lng: Double) {
    /** 서버 쿼리 형식 "위도,경도". Double.toString은 지역 설정과 관계없이 소수점이 "."이다. */
    fun toQuery(): String = "$lat,$lng"
}
