package io.github.klanary.jigeumchulbal.location

import io.github.klanary.jigeumchulbal.data.model.GeoPoint

/** 위치 하나: 좌표, 정확도(m, 68% 신뢰 반경), 받은 시각(epoch ms). 탑승 감지에 쓴다(위치 결합 스펙 7.1). */
data class LocationFix(val point: GeoPoint, val accuracyM: Float, val at: Long)
