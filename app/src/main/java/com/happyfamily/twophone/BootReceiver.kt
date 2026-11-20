package com.happyfamily.twophone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 폰을 껐다 켜거나 앱을 업데이트한 뒤에도 업무폰이 계속 알림을 받도록 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReceiverService.startIfNeeded(context)
    }
}
