package com.example.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

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
        Log.d(TAG, "onCallAdded: $call")
        currentCall = call
        inCallServiceInstance = this

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                Log.d(TAG, "Call state changed: $state")
                onCallStateChangedListener?.invoke(call, state)
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                super.onDetailsChanged(call, details)
                Log.d(TAG, "Call details changed: $details")
            }
        })

        onCallStateChangedListener?.invoke(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
        if (currentCall == call) {
            currentCall = null
            onCallStateChangedListener?.invoke(null, Call.STATE_DISCONNECTED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inCallServiceInstance = null
        currentCall = null
    }

    fun endCurrentCall() {
        currentCall?.disconnect()
    }

    fun answerCurrentCall() {
        currentCall?.answer(0)
    }

    fun toggleMute(muted: Boolean) {
        setMuted(muted)
    }

    fun toggleSpeaker(speakerOn: Boolean) {
        if (speakerOn) {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        } else {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE)
        }
    }
}
