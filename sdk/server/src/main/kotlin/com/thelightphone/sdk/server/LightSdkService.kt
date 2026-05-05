package com.thelightphone.sdk.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.allMethods

class LightSdkService : Service() {

    companion object {
        private const val TAG = "LightSdkService"
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                LightConstants.TRANSACTION_REQUEST -> {
                    data.enforceInterface(LightConstants.ACTION_BIND_SDK_SERVICE)
                    val methodId = data.readString()
                    val payload = data.readString()
                    val response = if (methodId != null) {
                        handleRequest(methodId, payload)
                    } else ""
                    reply?.writeNoException()
                    reply?.writeString(response)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun handleRequest(methodId: String, payload: String?): String? {
        return when (val method = allMethods[methodId]) {
            LightServiceMethod.GetVersion -> {
                LightServiceMethod.GetVersion.encodeResponse(
                    LightServiceMethod.GetVersion.Response(version = BuildConfig.SDK_VERSION)
                )
            }
            LightServiceMethod.SetRingtone -> {
                val request = LightServiceMethod.SetRingtone.decodeRequest(payload!!)
                setActualDefaultRingtoneUri(request.type, request.uri)
                LightServiceMethod.SetRingtone.encodeResponse(Unit)
            }
            null -> {
                Log.e(TAG, "Service method $method not found!")
                ""
            }


        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

fun Context.setActualDefaultRingtoneUri(type: Int, uri: String) {
    println("setting ringtone: $type $uri")
    RingtoneManager.setActualDefaultRingtoneUri(this, type, Uri.parse(uri))
}
