package com.example.tiny2.monitor

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private fun canonicalOf(remote: String): String = when {
    remote.equals("Temperature", true) -> "Temperature"
    remote.startsWith("Humid", true)   -> "Humid"
    remote.equals("CO2", true)         -> "CO2"
    remote.equals("Soil", true)        -> "Soil"
    remote.equals("LED", true)         -> "LED"
    remote.startsWith("Fan", true)     -> "Fan"
    remote.equals("Water", true)       -> "Water"
    remote.equals("Door", true)        -> "Door"
    else -> remote
}

private val Context.treeDataStore by preferencesDataStore(name = "resource_tree")

data class SensorDef(
    val canonical: String,
    val remote: String,
    val intervalMs: Long
)

data class ActDef(
    val canonical: String,
    val remote: String
)

data class ResourceTree(
    val sensors: List<SensorDef>,
    val actuators: List<ActDef>,
    val inference: List<ActDef> = emptyList()
)

class ResourceTreeStore(private val context: Context) {

    private fun key(ae: String) = stringPreferencesKey("tree:$ae")

    suspend fun load(ae: String): ResourceTree? {
        val raw = context.treeDataStore.data
            .map { it[key(ae)] }
            .first()
            ?: return null
        return parse(raw)
    }

    suspend fun addSensors(
        ae: String,
        sensors: List<String>,
        intervals: Map<String, Long>
    ) {
        val current = load(ae) ?: ResourceTree(emptyList(), emptyList())
        val merged = current.sensors.toMutableList()

        sensors.forEach { s ->
            val already = merged.any { it.remote == s }
            if (!already) {
                val ms = intervals[s] ?: SensorKind.fromKey(s)?.defaultMs ?: 60_000L
                val canon = canonicalOf(s)
                merged += SensorDef(
                    canonical = canon,
                    remote = s,
                    intervalMs = ms
                )
            }
        }

        val newTree = current.copy(sensors = merged)
        save(ae, newTree)
    }

    suspend fun addActuators(
        ae: String,
        acts: List<String>
    ) {
        val current = load(ae) ?: ResourceTree(emptyList(), emptyList())
        val merged = current.actuators.toMutableList()

        acts.forEach { a ->
            val already = merged.any { it.remote == a }
            if (!already) {
                val canon = canonicalOf(a)
                merged += ActDef(
                    canonical = canon,
                    remote    = a
                )
            }
        }

        val newTree = current.copy(actuators = merged)
        save(ae, newTree)
    }

    suspend fun save(ae: String, tree: ResourceTree) {
        context.treeDataStore.edit { prefs ->
            prefs[key(ae)] = serialize(tree)
        }
    }
}


private fun serialize(tree: ResourceTree): String {
    val root = JSONObject()

    val arrSensors = JSONArray()
    tree.sensors.forEach {
        arrSensors.put(JSONObject().apply {
            put("canonical", it.canonical)
            put("intervalMs", it.intervalMs)
        })
    }

    val arrActs = JSONArray()
    tree.actuators.forEach {
        arrActs.put(JSONObject().apply {
            put("canonical", it.canonical)
        })
    }

    root.put("sensors", arrSensors)
    root.put("actuators", arrActs)
    return root.toString()
}

private fun parse(raw: String): ResourceTree {
    return try {
        val root = JSONObject(raw)
        val sensorsJson = root.optJSONArray("sensors") ?: JSONArray()
        val actsJson = root.optJSONArray("actuators") ?: JSONArray()

        val sensors = buildList {
            for (i in 0 until sensorsJson.length()) {
                val o = sensorsJson.getJSONObject(i)
                add(
                    SensorDef(
                        canonical = o.optString("canonical"),
                        remote = o.optString("remote"),
                        intervalMs = o.optLong("intervalMs", 60_000L)
                    )
                )
            }
        }
        val acts = buildList {
            for (i in 0 until actsJson.length()) {
                val o = actsJson.getJSONObject(i)
                add(
                    ActDef(
                        canonical = o.optString("canonical"),
                        remote = o.optString("remote")
                    )
                )
            }
        }
        ResourceTree(sensors, acts)
    } catch (_: Throwable) {
        ResourceTree(emptyList(), emptyList())
    }
}