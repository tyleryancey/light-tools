package dev.tyler.sudoku

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    // Sudoku is fully offline: no server data, no push.
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}
    override suspend fun onPushNotification(data: ByteArray) {}
}
