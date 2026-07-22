package dev.tyler.chess

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    // v1 is fully offline: no server data, no push. Push wiring arrives with correspondence (v2).
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}
    override suspend fun onPushNotification(data: ByteArray) {}
}
