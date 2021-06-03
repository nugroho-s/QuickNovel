package com.lagradost.quicknovel.services

import android.app.IntentService
import android.content.Intent
import com.lagradost.quicknovel.ReadActivity
import com.lagradost.quicknovel.TTSActionType

class TTSPauseService : IntentService("TTSPauseService") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent != null) {
            val id = intent.getIntExtra("id", -1)
            when (id) {
                TTSActionType.Pause.ordinal -> {
                    ReadActivity.readActivity.isTTSPaused = true
                }
                TTSActionType.Resume.ordinal -> {
                    ReadActivity.readActivity.isTTSPaused = false
                }
                TTSActionType.Stop.ordinal -> {
                    ReadActivity.readActivity.stopTTS()
                }
            }
        }
    }
}