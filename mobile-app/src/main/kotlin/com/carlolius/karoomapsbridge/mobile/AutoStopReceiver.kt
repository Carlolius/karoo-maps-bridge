package com.carlolius.karoomapsbridge.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Target of the auto-stop alarm. stopService is never blocked by background limits. */
class AutoStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("PointServer", "Auto-stop alarm fired")
        context.stopService(Intent(context, PointServerService::class.java))
    }
}
