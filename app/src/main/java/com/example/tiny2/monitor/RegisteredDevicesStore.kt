package com.example.tiny2.monitor   // ← 당신 패키지에 맞춰 유지

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.deviceDataStore by preferencesDataStore(name = "registered_devices")

class RegisteredDevicesStore(private val context: Context) {

    private object Keys {
        val AE_SET = stringSetPreferencesKey("ae_set")
    }

    /** 저장된 AE 목록 스트림 */
    val registeredAEs: Flow<Set<String>> =
        context.deviceDataStore.data.map { pref -> pref[Keys.AE_SET] ?: emptySet() }

    /** AE 추가 */
    suspend fun addAE(ae: String) {
        context.deviceDataStore.edit { pref ->
            val cur = pref[Keys.AE_SET] ?: emptySet()
            pref[Keys.AE_SET] = cur + ae
        }
    }

    /** AE 제거 */
    suspend fun removeAE(ae: String) {
        context.deviceDataStore.edit { pref ->
            val cur = pref[Keys.AE_SET] ?: emptySet()
            pref[Keys.AE_SET] = cur - ae
        }
    }

    /** 전체 초기화(필요시) */
    suspend fun clear() {
        context.deviceDataStore.edit { pref -> pref[Keys.AE_SET] = emptySet() }
    }
}