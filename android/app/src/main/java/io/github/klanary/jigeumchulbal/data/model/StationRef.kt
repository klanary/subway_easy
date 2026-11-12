package io.github.klanary.jigeumchulbal.data.model

import io.github.klanary.jigeumchulbal.data.remote.StationDto
import kotlinx.serialization.Serializable

/** 경로의 출발·경유·도착 역(재설계 스펙 D6: 역만 고른다). [id]는 서버의 역 id다. */
@Serializable
data class StationRef(val id: String, val name: String)

fun StationDto.toRef() = StationRef(id, name)
