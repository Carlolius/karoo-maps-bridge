package com.carlolius.karoomapsbridge.mobile

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Tiny HTTP server on the phone. The Karoo cannot accept incoming connections
 * (verified on a real Karoo 2), so the Karoo always *asks* (see [PointApi]).
 * Runs as a foreground service; started automatically when something is shared.
 * Stops by itself [AFTER_FETCH_MS] after the Karoo fetched the point, or
 * [AFTER_SHARE_MS] after sharing if the Karoo never asks. Also stoppable from the notification.
 *
 * The automatic stop is belt and braces, because phones (Xiaomi especially) are creative
 * about delaying timers while asleep:
 *  - an exact AlarmManager alarm (a Handler counts uptime and freezes while the phone sleeps)
 *    delivered to [AutoStopReceiver], which just calls stopService (never blocked);
 *  - plus a check whenever the screen turns on, the app opens or a request arrives.
 */
class PointServerService : Service() {

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = stopIfExpired("screen on")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested from the notification")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // A refresh (list changed) only redraws the notification; anything else (a share,
        // the start button, a sticky restart) restarts the countdown.
        if (intent?.action != ACTION_REFRESH || stopAt == 0L) scheduleStop(AFTER_SHARE_MS)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (serverSocket == null) {
            startServer()
            registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Server stopped")
        getSystemService(AlarmManager::class.java).cancel(autoStopIntent())
        if (serverSocket != null) runCatching { unregisterReceiver(screenOn) }
        stopAt = 0L
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        super.onDestroy()
    }

    /** (Re)schedules the automatic stop and refreshes the notification. Main thread only. */
    private fun scheduleStop(delayMs: Long) {
        stopAt = System.currentTimeMillis() + delayMs
        val alarms = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, autoStopIntent())
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, autoStopIntent()) // may be late
        }
        Log.i(TAG, "Auto-stop scheduled at ${formatTime(this, stopAt)}")
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun autoStopIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 2, Intent(this, AutoStopReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Fallback for a late alarm. Main thread only. */
    private fun stopIfExpired(why: String) {
        if (stopAt != 0L && System.currentTimeMillis() >= stopAt) {
            Log.i(TAG, "Countdown expired, stopping ($why)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startServer() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        serverSocket = socket
        running = true
        Log.i(TAG, "Listening on port $PORT")
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
    }

    private fun handle(client: Socket): Unit = client.use { s ->
        s.soTimeout = 5000
        val reader = s.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (!reader.readLine().isNullOrEmpty()) { /* skip headers */ }
        val (method, path) = requestLine.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        Log.i(TAG, "${s.inetAddress.hostAddress} $method $path")
        if (stopAt != 0L && System.currentTimeMillis() >= stopAt) {
            handler.post { stopIfExpired("request after the stop time") }
            return
        }

        val response = PointApi.respond(method, path, PointStore.all(this))
        if (response.delivered) handler.post { scheduleStop(AFTER_FETCH_MS) } // job done, wind down
        val status = response.status
        val body = response.body
        val bytes = body.toByteArray()
        val out = s.getOutputStream()
        out.write(("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val points = PointStore.all(this)
        val title = when (points.size) {
            0 -> getString(R.string.notif_no_point)
            1 -> getString(R.string.notif_point_ready, points[0].name ?: formatCoords(points[0]))
            else -> getString(R.string.notif_points_ready, points.size)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PointServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = getString(R.string.notif_server_on, localAddresses().joinToString(", ").ifEmpty { "-" }, PORT) +
            "\n" + getString(R.string.notif_stops_at, formatTime(this, stopAt))
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8730
        private const val TAG = "PointServer"
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_REFRESH = "refresh"
        const val AFTER_SHARE_MS = 2 * 60 * 60 * 1000L
        const val AFTER_FETCH_MS = 10 * 60 * 1000L

        /** Wall-clock time of the scheduled automatic stop (0 when not running). */
        @Volatile
        var stopAt = 0L
            private set

        @Volatile
        var running = false
            private set

        /** Starts the server, or refreshes its notification if already running. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, PointServerService::class.java))
        }

        /** Redraws the notification after the list changed, without restarting the countdown. */
        fun refresh(context: Context) {
            if (running) context.startService(Intent(context, PointServerService::class.java).setAction(ACTION_REFRESH))
        }

        /** True when the countdown is over but the service is still around (late alarm). */
        fun expired() = running && stopAt != 0L && System.currentTimeMillis() >= stopAt

        fun stop(context: Context) {
            context.startService(Intent(context, PointServerService::class.java).setAction(ACTION_STOP))
        }

        fun formatTime(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(millis)

        /** Always "41.38700, 2.17009": a decimal comma would be ambiguous with the separator. */
        fun formatCoords(p: PendingPoint) = String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lng)

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
