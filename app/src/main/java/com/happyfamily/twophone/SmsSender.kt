package com.happyfamily.twophone

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import org.json.JSONObject
import java.util.UUID

/**
 * 보내는 폰에서 동작. 받는 폰이 보낸 "문자 발신 요청"을 받아
 * 이 폰의 번호로 실제 문자를 보내고, 결과를 받는 폰에 알려 준다.
 */
object SmsSender {
    /** 요청이 만들어진 뒤 이 시간이 지나면 보내지 않는다 (뒤늦게 엉뚱한 문자가 나가는 것 방지) */
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    /** 받는 폰에서 요청을 만든다. */
    fun buildCommand(targetDevId: String, to: String, text: String): String = JSONObject()
        .put("c", "sms").put("i", UUID.randomUUID().toString())
        .put("target", targetDevId).put("to", to).put("m", text)
        .put("ts", System.currentTimeMillis())
        .toString()

    /** 입력한 번호에서 공백·하이픈을 걷어 낸다. 번호로 볼 수 없으면 null */
    fun cleanNumber(input: String): String? {
        val n = input.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val extra = input.filter { !(it.isDigit() || it in "+*#-() .") }
        return if (n.count { it.isDigit() } >= 3 && extra.isEmpty() && n.lastIndexOf('+') <= 0) n else null
    }

    fun handle(ctx: Context, key: ByteArray, body: String) {
        val prefs = Prefs(ctx)
        val plain = Crypto.decrypt(key, body) ?: return // 연결 코드를 모르는 쪽이 보낸 것은 무시
        val o = try { JSONObject(plain) } catch (e: Exception) { return }
        if (o.optString("c") != "sms") return
        if (!BuildConfig.SMS_SEND) return // Play 버전은 문자를 보내지 않는다
        if (o.optString("target") != prefs.deviceId) return // 다른 폰에게 온 요청
        val id = o.optString("i")
        val to = cleanNumber(o.optString("to"))
        val text = o.optString("m")
        if (id.isEmpty() || !prefs.markCommandSeen(id)) return

        val problem = when {
            !prefs.smsSendEnabled -> "이 폰에서 문자 발신 허용이 꺼져 있습니다"
            to == null || text.isEmpty() -> "번호 또는 내용이 올바르지 않습니다"
            System.currentTimeMillis() - o.optLong("ts") > MAX_AGE_MS -> "요청한 지 10분이 지나 보내지 않았습니다"
            ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ->
                "이 폰에 문자 발신 권한이 없습니다"
            else -> null
        }
        if (problem != null || to == null) {
            report(ctx, o.optString("to"), "보내지 못함: $problem")
            return
        }
        try {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            // 마지막 조각의 결과로 전체 결과를 판단한다
            val sent = PendingIntent.getBroadcast(
                ctx, id.hashCode(),
                Intent(ctx, SmsSentReceiver::class.java).putExtra("to", to),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (parts.size <= 1) {
                sms.sendTextMessage(to, null, text, sent, null)
            } else {
                val intents = ArrayList<PendingIntent?>(parts.size)
                for (i in parts.indices) intents.add(if (i == parts.size - 1) sent else null)
                sms.sendMultipartTextMessage(to, null, parts, intents, null)
            }
        } catch (e: Exception) {
            report(ctx, to, "보내지 못함: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 결과를 받는 폰에 알림으로 돌려 보낸다. */
    fun report(ctx: Context, to: String, result: String) {
        val prefs = Prefs(ctx)
        ForwardListenerService.send(
            ctx,
            Msg(UUID.randomUUID().toString(), "문자 발신", ctx.packageName, to, result,
                System.currentTimeMillis(), prefs.deviceName.take(30), prefs.deviceId),
        )
    }
}

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val to = intent.getStringExtra("to").orEmpty()
        val result = when (resultCode) {
            Activity.RESULT_OK -> "문자를 보냈습니다"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "보내지 못함: 통신 서비스 지역이 아닙니다"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "보내지 못함: 비행기 모드이거나 통신이 꺼져 있습니다"
            else -> "보내지 못함: 통신사 오류 (코드 $resultCode)"
        }
        SmsSender.report(context, to, result)
    }
}
