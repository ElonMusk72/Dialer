package com.example.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.example.utils.LogRecorder

class CallService : InCallService() {

    companion object {
        private const val TAG = "CallService"
        var currentCall: Call? = null
        var inCallServiceInstance: CallService? = null
        
        // Listener for Call state updates
        var onCallStateChangedListener: ((call: Call?, state: Int) -> Unit)? = null
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        LogRecorder.logInfo(TAG, "onCallAdded: $call (state=${call.state})")
        currentCall = call
        inCallServiceInstance = this

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                LogRecorder.logInfo(TAG, "Call state changed: state=$state")
                onCallStateChangedListener?.invoke(call, state)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                super.onDetailsChanged(call, details)
                LogRecorder.logDebug(TAG, "Call details changed: handle=${details.handle}")
            }
        })

        onCallStateChangedListener?.invoke(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        LogRecorder.logInfo(TAG, "onCallRemoved: $call")
        if (currentCall == call) {
            currentCall = null
            onCallStateChangedListener?.invoke(null, Call.STATE_DISCONNECTED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LogRecorder.logInfo(TAG, "CallService destroyed")
        inCallServiceInstance = null
        currentCall = null
    }

    fun endCurrentCall() {
        LogRecorder.logInfo(TAG, "endCurrentCall invoked")
        currentCall?.disconnect()
    }

    fun answerCurrentCall() {
        LogRecorder.logInfo(TAG, "answerCurrentCall invoked")
        currentCall?.answer(0)
    }

    fun toggleMute(muted: Boolean) {
        LogRecorder.logInfo(TAG, "toggleMute: $muted")
        setMuted(muted)
    }

    fun toggleSpeaker(speakerOn: Boolean) {
        LogRecorder.logInfo(TAG, "toggleSpeaker: $speakerOn")
        if (speakerOn) {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        } else {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE)
        }
    }
}
