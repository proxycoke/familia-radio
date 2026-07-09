package com.familiaradio.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Servidor de tokens y familias: desplegado en Render, accesible desde cualquier red.
private const val TOKEN_SERVER_URL = "https://familia-radio-server.onrender.com"

/**
 * Encapsula toda la conexión de voz a Agora (fetch de token, join, reconexión automática).
 * No depende de una Activity: puede vivir dentro de un Service para seguir conectada
 * en segundo plano.
 */
class AgoraConnectionManager(private val context: Context) {
    private var rtcEngine: RtcEngine? = null
    private var activeFamilyId: Int? = null
    private var activeDeviceId: String? = null
    private var rejoinInProgress = false
    private val mainHandler = Handler(Looper.getMainLooper())
    val reconnecting = mutableStateOf(false)

    fun connect(familyId: Int, deviceId: String, onJoined: () -> Unit, onError: (String) -> Unit) {
        activeFamilyId = familyId
        activeDeviceId = deviceId
        fetchTokenAndJoin(familyId, deviceId, onJoined, onError)
    }

    fun setMicMuted(muted: Boolean) {
        rtcEngine?.muteLocalAudioStream(muted)
    }

    fun forceMaxVolume() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
    }

    fun leaveChannel() {
        activeFamilyId = null
        activeDeviceId = null
        rejoinInProgress = false
        reconnecting.value = false
        mainHandler.removeCallbacksAndMessages(null)
        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        rtcEngine = null
    }

    private fun httpGet(path: String, onResult: (Int, String) -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val url = URL("$TOKEN_SERVER_URL$path")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.readText() ?: ""
                mainHandler.post { onResult(code, body) }
            } catch (e: Exception) {
                mainHandler.post { onError(context.getString(R.string.error_contacting_server, e.message)) }
            }
        }.start()
    }

    private fun fetchToken(
        familyId: Int,
        deviceId: String,
        onResult: (appId: String, channelName: String, uid: Int, token: String) -> Unit,
        onError: (String) -> Unit
    ) {
        httpGet("/token?familyId=$familyId&deviceId=$deviceId", onResult = { code, responseBody ->
            if (code != 200) {
                onError(context.getString(R.string.error_token_server_status, code))
                return@httpGet
            }
            val json = JSONObject(responseBody)
            onResult(
                json.getString("appId"),
                json.getString("channelName"),
                json.getInt("uid"),
                json.getString("token")
            )
        }, onError = onError)
    }

    private fun fetchTokenAndJoin(
        familyId: Int,
        deviceId: String,
        onJoined: () -> Unit,
        onError: (String) -> Unit
    ) {
        fetchToken(familyId, deviceId, onResult = { appId, channelName, uid, token ->
            joinAgoraChannel(appId, channelName, token, uid, onJoined, onError)
        }, onError = onError)
    }

    private fun joinAgoraChannel(
        appId: String,
        channelName: String,
        token: String,
        uid: Int,
        onJoined: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            if (rtcEngine != null) {
                rtcEngine?.leaveChannel()
                RtcEngine.destroy()
                rtcEngine = null
            }
            val eventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
                    mainHandler.post {
                        rejoinInProgress = false
                        reconnecting.value = false
                        onJoined()
                    }
                }
                override fun onError(err: Int) {
                    mainHandler.post { onError(context.getString(R.string.error_agora_connection, err)) }
                }
                override fun onConnectionStateChanged(state: Int, reason: Int) {
                    val lostConnection = state == Constants.CONNECTION_STATE_FAILED ||
                        (state == Constants.CONNECTION_STATE_DISCONNECTED &&
                            reason != Constants.CONNECTION_CHANGED_LEAVE_CHANNEL)
                    if (lostConnection) {
                        mainHandler.post { attemptReconnect() }
                    }
                }
                override fun onTokenPrivilegeWillExpire(token: String?) {
                    val familyIdForRenewal = activeFamilyId ?: return
                    val deviceIdForRenewal = activeDeviceId ?: return
                    fetchToken(familyIdForRenewal, deviceIdForRenewal, onResult = { _, _, _, freshToken ->
                        rtcEngine?.renewToken(freshToken)
                    }, onError = {
                        // Se reintentará solo cuando el token realmente expire y se corte la conexión.
                    })
                }
            }
            val appContext = context.applicationContext
            val config = RtcEngineConfig().apply {
                mContext = appContext
                mAppId = appId
                mEventHandler = eventHandler
                mChannelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            }
            val engine = RtcEngine.create(config)
            engine.enableAudio()
            engine.setDefaultAudioRoutetoSpeakerphone(true)
            engine.muteLocalAudioStream(true)

            val options = ChannelMediaOptions().apply {
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                publishMicrophoneTrack = true
                autoSubscribeAudio = true
            }
            engine.joinChannel(token, channelName, uid, options)
            rtcEngine = engine
        } catch (e: Exception) {
            onError(context.getString(R.string.error_agora_start, e.message))
        }
    }

    private fun attemptReconnect() {
        val familyId = activeFamilyId ?: return
        val deviceId = activeDeviceId ?: return
        if (rejoinInProgress) return
        rejoinInProgress = true
        reconnecting.value = true
        fetchTokenAndJoin(familyId, deviceId, onJoined = {
            // onJoinChannelSuccess ya limpia rejoinInProgress y reconnecting
        }, onError = {
            rejoinInProgress = false
            mainHandler.postDelayed({ attemptReconnect() }, 5000)
        })
    }
}
