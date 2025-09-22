package com.example.tiny2.repository

import androidx.room.Transaction
import com.example.tiny2.data.dao.CntDefDao
import com.example.tiny2.data.entities.CntDefEntity
import com.example.tiny2.monitor.ResourceTree
import kotlinx.coroutines.flow.Flow
import com.example.tiny2.network.OneM2M

class CntRepository(private val dao: CntDefDao) {

    suspend fun stateTag(cnt: String) = OneM2M.getStateTag(cnt)
    suspend fun latest(cnt: String)   = OneM2M.getLatest(cnt)

    fun observeAllByAe(ae: String): Flow<List<CntDefEntity>> = dao.observeByAe(ae)
    fun observeSensors(ae: String): Flow<List<CntDefEntity>> = dao.observeSensors(ae)
    fun observeActuators(ae: String): Flow<List<CntDefEntity>> = dao.observeActuators(ae)

    /** 서버에서 받은 ResourceTree로 해당 AE 정의를 싹 치환 */
    @Transaction
    suspend fun replaceByTree(ae: String, tree: ResourceTree) {
        val defs = buildList {
            tree.sensors.forEach { s ->
                add(
                    CntDefEntity(
                        ae = ae,
                        remote = s.remote,
                        canonical = s.canonical,
                        type = "sensor",
                        intervalMs = s.intervalMs
                    )
                )
            }
            tree.actuators.forEach { a ->
                add(
                    CntDefEntity(
                        ae = ae,
                        remote = a.remote,
                        canonical = a.canonical,
                        type = "actuator",
                        intervalMs = null
                    )
                )
            }
        }
        dao.deleteByAe(ae)
        dao.upsertAll(defs)
    }

    /** 개별 추가(센서/액추) */
    suspend fun addDefs(ae: String, defs: List<CntDefEntity>) {
        dao.upsertAll(defs)
    }
}