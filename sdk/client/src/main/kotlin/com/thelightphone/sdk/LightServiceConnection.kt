package com.thelightphone.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.CompletableDeferred

internal object LightServiceConnection : ServiceConnection {

    private const val TAG = "LightServiceConnection"

    private var serviceBinder: IBinder? = null
    private var bound = false
    private var binderReady = CompletableDeferred<IBinder>()

    fun bind(context: Context) {
        if (bound) return
        val intent = Intent(LightConstants.ACTION_BIND_SDK_SERVICE).apply {
            setPackage(BuildConfig.LIGHT_SERVER_PACKAGE)
        }
        bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        if (!bound) {
            Log.w(TAG, "bindService returned false — is the server installed?")
        }
    }

    fun unbind(context: Context) {
        if (!bound) return
        try {
            context.unbindService(this)
        } catch (_: IllegalArgumentException) {}
        bound = false
        serviceBinder = null
    }

    fun request(method: String, data: String): String? {
        val binder = serviceBinder ?: return null
        val parcel = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            parcel.writeInterfaceToken(LightConstants.ACTION_BIND_SDK_SERVICE)
            parcel.writeString(method)
            parcel.writeString(data)
            binder.transact(LightConstants.TRANSACTION_REQUEST, parcel, reply, 0)
            reply.readException()
            reply.readString()
        } catch (e: Exception) {
            Log.e(TAG, "request failed: method=$method", e)
            null
        } finally {
            parcel.recycle()
            reply.recycle()
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        Log.i(TAG, "Connected to LightSdkService")
        serviceBinder = binder
        if (binder != null) {
            binderReady.complete(binder)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Log.w(TAG, "Disconnected from LightSdkService")
        serviceBinder = null
        binderReady = CompletableDeferred()
    }

    suspend fun awaitBinder(): IBinder = binderReady.await()
}

suspend fun <TRequest, TResponse> callRemoteServiceMethod(
    method: LightServiceMethod<TRequest, TResponse>,
    body: TRequest,
): TResponse? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    LightServiceConnection.awaitBinder()
    val responseJson = LightServiceConnection.request(method.id, method.encodeRequest(body)) ?: return@withContext null
    method.decodeResponse(responseJson)
}