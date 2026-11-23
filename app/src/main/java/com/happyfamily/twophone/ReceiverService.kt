package com.happyfamily.twophone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * 중계 서버에 계속 연결해 두는 서비스.
 * - 받는 폰: 다른 폰이 보낸 알림이 오면 복호화해서 이 폰에 알림으로 띄운다.
 * - 보내는 폰(문자 발신 허용 시): 받는 폰의 문자 발신 요청을 기다렸다가 문자를 보낸다.
 */
class ReceiverService : Service() {
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        val status = statusNotification("연결하는 중…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, status)
        }
        if (!shouldRun(prefs)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker == null) {
            running = true
            // 화면이 꺼져 있어도 도착한 알림을 바로 처리하기 위해 필요
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "twophone:receiver").apply { acquire() }
            worker = Thread({ loop(prefs) }, "twophone-receiver").apply { start() }
        }
        return START_STICKY
    }

    private fun loop(prefs: Prefs) {
        var backoff = 2000L
        while (running) {
            try {
                val key = prefs.key ?: break
                val since = prefs.lastMsgId.ifEmpty { prefs.pairedAtSec.toString() }
                val sender = prefs.role == ROLE_SENDER
                Relay.subscribe(prefs.server, if (sender) prefs.cmdTopic else prefs.topic, since,
                    onOpen = {
                        conn = it
                        backoff = 2000L
                        setState(true, if (sender) "문자 발신 요청을 기다리는 중" else "알림을 기다리는 중")
                    },
                    onMessage = { id, body ->
                        if (sender) SmsSender.handle(this, key, body) else handle(key, id, body)
                        if (id.isNotEmpty()) prefs.lastMsgId = id
                    })
            } catch (e: Exception) {
                if (!running) break
                setState(false, "다시 연결하는 중… (${e.message ?: "네트워크 없음"})")
                try { Thread.sleep(backoff) } catch (ie: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(30000L)
            }
        }
    }

    private fun handle(key: ByteArray, id: String, body: String) {
        val plain = Crypto.decrypt(key, body) ?: return // 우리 코드로 보낸 것이 아니면 무시
        val msg = try { Msg.fromJson(JSONObject(plain), id) } catch (e: Exception) { return }
        if (!History.add(this, msg)) return
        if (msg.devId.isNotEmpty()) {
            val prefs = Prefs(this)
            if (prefs.devices[msg.devId] != msg.dev) prefs.devices = prefs.devices + (msg.devId to msg.dev)
        }
        val title = listOf(msg.dev.let { if (it.isEmpty()) "" else "[$it]" }, listOf(msg.app, msg.title)
            .filter { it.isNotEmpty() }.joinToString(" · ")).filter { it.isNotEmpty() }.joinToString(" ")
        val n = Notification.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(getColor(R.color.primary))
            .setContentTitle(title.ifEmpty { "본래폰 알림" })
            .setContentText(msg.text)
            .setStyle(Notification.BigTextStyle().bigText(msg.text))
            .setWhen(msg.ts)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(openApp(this))
            .build()
        nm().notify(msg.id.hashCode(), n)
    }

    private fun setState(ok: Boolean, text: String) {
        connected = ok
        statusText = text
        nm().notify(STATUS_ID, statusNotification(text))
        onStateChange?.invoke()
    }

    private fun statusNotification(text: String): Notification =
        Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(getColor(R.color.primary))
            .setContentTitle("투폰문자체커")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .build()

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        // 읽기 대기 중인 연결은 다른 스레드에서 끊어야 바로 풀린다
        conn?.let { c -> Thread { try { c.disconnect() } catch (_: Exception) {} }.start() }
        wakeLock?.let { if (it.isHeld) it.release() }
        connected = false
        statusText = "꺼짐"
        onStateChange?.invoke()
        super.onDestroy()
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_MESSAGES = "messages"
        private const val STATUS_ID = 1

        @Volatile var connected = false
        @Volatile var statusText = "꺼짐"
        var onStateChange: (() -> Unit)? = null

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "본래폰에서 온 알림", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "연결 상태", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun shouldRun(prefs: Prefs) = prefs.paired &&
            (prefs.role == ROLE_RECEIVER || (prefs.role == ROLE_SENDER && prefs.smsSendEnabled))

        fun startIfNeeded(ctx: Context) {
            val prefs = Prefs(ctx)
            if (shouldRun(prefs)) {
                try {
                    ctx.startForegroundService(Intent(ctx, ReceiverService::class.java))
                } catch (_: Exception) {
                    // 백그라운드에서 시작이 막힌 경우: 앱을 열면 다시 시작된다
                }
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ReceiverService::class.java))
        }
    }
}
