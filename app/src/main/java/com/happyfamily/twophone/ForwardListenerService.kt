package com.happyfamily.twophone

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 본래폰에서 동작. 문자·메신저 등의 알림이 뜨면 내용을 읽어
 * 암호화한 뒤 업무폰으로 보낸다.
 */
class ForwardListenerService : NotificationListenerService() {
    private lateinit var prefs: Prefs
    private val recent = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 100
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (prefs.role != ROLE_SENDER || !prefs.paired || !prefs.forwardEnabled) return
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (!prefs.forwardAll && pkg !in prefs.apps) return

        val n = sbn.notification
        if (sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // 같은 알림이 내용 변화 없이 다시 올라오는 경우(앱이 알림을 갱신)는 한 번만 보낸다
        val signature = "$title\n$text"
        synchronized(recent) {
            if (recent[sbn.key] == signature) return
            recent[sbn.key] = signature
        }

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        send(this, Msg(UUID.randomUUID().toString(), label, pkg, title.take(100), text.take(700), sbn.postTime, prefs.deviceName.take(30)))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(recent) { recent.remove(sbn.key) }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        var onStatusChange: (() -> Unit)? = null

        fun isAccessGranted(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, ForwardListenerService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }

        /** 처음 설정할 때 기본으로 체크해 둘 앱: 문자 앱과 카카오톡 */
        fun defaultApps(ctx: Context): Set<String> {
            val candidates = mutableSetOf(
                "com.samsung.android.messaging",
                "com.google.android.apps.messaging",
                "com.kakao.talk",
            )
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { candidates.add(it) }
            return candidates.filter {
                try { ctx.packageManager.getApplicationInfo(it, 0); true } catch (e: Exception) { false }
            }.toSet()
        }

        /** 암호화해서 보낸다. 네트워크가 잠깐 안 될 때를 대비해 몇 번 다시 시도한다. */
        fun send(ctx: Context, msg: Msg, onDone: ((error: String?) -> Unit)? = null) {
            val app = ctx.applicationContext
            executor.execute {
                val prefs = Prefs(app)
                val key = prefs.key
                var error: String? = "연결 코드가 없습니다"
                if (key != null) {
                    val body = Crypto.encrypt(key, msg.toJson().toString())
                    val waits = longArrayOf(0, 3000, 10000, 30000)
                    for (wait in waits) {
                        if (wait > 0) Thread.sleep(wait)
                        try {
                            Relay.publish(prefs.server, prefs.topic, body)
                            error = null
                            break
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                            if (onDone != null) break // 테스트 버튼은 바로 결과를 알려 준다
                        }
                    }
                }
                if (error == null) {
                    prefs.sentCount += 1
                    prefs.lastSentAt = System.currentTimeMillis()
                    prefs.lastError = ""
                } else {
                    prefs.lastError = error
                }
                onDone?.invoke(error)
                onStatusChange?.invoke()
            }
        }
    }
}
