package io.github.klanary.jigeumchulbal.data.repository

import io.github.klanary.jigeumchulbal.data.local.MyPlaceDao
import io.github.klanary.jigeumchulbal.data.local.MyPlaceEntity
import io.github.klanary.jigeumchulbal.data.local.MyPlaceType
import io.github.klanary.jigeumchulbal.data.model.Endpoint
import kotlinx.coroutines.flow.Flow

interface MyPlaceRepository {
    val places: Flow<List<MyPlaceEntity>>

    suspend fun save(type: MyPlaceType, label: String, endpoint: Endpoint, address: String?)

    suspend fun delete(id: Long)

    suspend fun get(id: Long): MyPlaceEntity?
}

class DefaultMyPlaceRepository(private val dao: MyPlaceDao) : MyPlaceRepository {
    override val places: Flow<List<MyPlaceEntity>> = dao.observeAll()

    override suspend fun save(type: MyPlaceType, label: String, endpoint: Endpoint, address: String?) =
        dao.saveTyped(
            MyPlaceEntity(
                type = type,
                label = label,
                lat = endpoint.point.lat,
                lng = endpoint.point.lng,
                address = address,
                stationId = endpoint.stationId,
            ),
        )

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun get(id: Long): MyPlaceEntity? = dao.get(id)
}
