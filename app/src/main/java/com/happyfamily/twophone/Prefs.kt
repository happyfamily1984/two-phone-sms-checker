package com.happyfamily.twophone

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

const val ROLE_NONE = ""
const val ROLE_SENDER = "sender"     // 본래폰: 알림을 보내는 쪽
const val ROLE_RECEIVER = "receiver" // 업무폰: 알림을 받는 쪽
const val DEFAULT_SERVER = "https://ntfy.sh"

class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("twophone", Context.MODE_PRIVATE)

    var role: String
        get() = sp.getString("role", ROLE_NONE)!!
        set(v) = sp.edit().putString("role", v).apply()
    val code: String get() = sp.getString("code", "")!!
    val topic: String get() = sp.getString("topic", "")!!
    val key: ByteArray? get() = sp.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    val cmdTopic: String
        get() = sp.getString("cmdTopic", null)
            ?: Crypto.derive(code).cmdTopic.also { sp.edit().putString("cmdTopic", it).apply() }
    val paired: Boolean get() = topic.isNotEmpty() && sp.contains("key")

    var server: String
        get() = sp.getString("server", DEFAULT_SERVER)!!
        set(v) = sp.edit().putString("server", v).apply()

    /** 여러 폰에서 보낼 때 구분하기 위한 이 폰의 이름 */
    var deviceName: String
        get() = sp.getString("devName", null) ?: android.os.Build.MODEL.orEmpty()
        set(v) = sp.edit().putString("devName", v).apply()

    /** 이 폰을 가리키는 고유 번호 (이름이 같은 폰이 있어도 구분된다) */
    val deviceId: String
        get() = sp.getString("devId", null)
            ?: java.util.UUID.randomUUID().toString().take(8).also { sp.edit().putString("devId", it).apply() }

    /** 보내는 폰: 받는 폰의 요청으로 이 폰에서 문자를 발신하는 것을 허용 */
    var smsSendEnabled: Boolean
        get() = sp.getBoolean("smsSend", false)
        set(v) = sp.edit().putBoolean("smsSend", v).apply()

    /** 받는 폰: 지금까지 알림을 보내 온 폰들 (고유 번호 → 이름) */
    var devices: Map<String, String>
        get() = try {
            val o = JSONObject(sp.getString("devices", "{}")!!)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) { emptyMap() }
        set(v) = sp.edit().putString("devices", JSONObject(v).toString()).apply()

    /** 보내는 폰: 이미 처리한 발신 요청 (같은 요청으로 문자가 두 번 나가지 않게) */
    fun markCommandSeen(id: String): Boolean {
        val seen = sp.getString("seenCmds", "")!!.split(",").filter { it.isNotEmpty() }
        if (id in seen) return false
        sp.edit().putString("seenCmds", (seen + id).takeLast(100).joinToString(",")).apply()
        return true
    }

    var forwardEnabled: Boolean
        get() = sp.getBoolean("fwd", true)
        set(v) = sp.edit().putBoolean("fwd", v).apply()
    var forwardAll: Boolean
        get() = sp.getBoolean("fwdAll", false)
        set(v) = sp.edit().putBoolean("fwdAll", v).apply()
    var apps: Set<String>
        get() = sp.getStringSet("apps", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("apps", v).apply()

    var sentCount: Int
        get() = sp.getInt("sent", 0)
        set(v) = sp.edit().putInt("sent", v).apply()
    var lastSentAt: Long
        get() = sp.getLong("lastSent", 0)
        set(v) = sp.edit().putLong("lastSent", v).apply()
    var lastError: String
        get() = sp.getString("lastErr", "")!!
        set(v) = sp.edit().putString("lastErr", v).apply()

    /** 받는 쪽: 마지막으로 받은 메시지 ID (끊겼다 다시 연결될 때 그 뒤부터 받기 위함) */
    var lastMsgId: String
        get() = sp.getString("lastId", "")!!
        set(v) = sp.edit().putString("lastId", v).apply()
    val pairedAtSec: Long get() = sp.getLong("pairedAt", 0)

    fun pair(role: String, code: String) {
        val p = Crypto.derive(code)
        sp.edit()
            .putString("role", role)
            .putString("code", code)
            .putString("topic", p.topic)
            .putString("cmdTopic", p.cmdTopic)
            .putString("key", Base64.encodeToString(p.key, Base64.NO_WRAP))
            .putLong("pairedAt", System.currentTimeMillis() / 1000)
            .remove("lastId")
            .apply()
    }

    fun reset() {
        val server = this.server
        val name = deviceName
        val id = deviceId
        sp.edit().clear().putString("server", server).putString("devName", name).putString("devId", id).apply()
    }
}

/** 전달되는 알림 한 건 */
data class Msg(
    val id: String,
    val app: String,
    val pkg: String,
    val title: String,
    val text: String,
    val ts: Long,
    /** 어느 폰에서 온 알림인지 (보내는 폰의 이름) */
    val dev: String = "",
    val devId: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("i", id).put("a", app).put("p", pkg).put("t", title).put("m", text).put("ts", ts).put("d", dev).put("di", devId)

    companion object {
        fun fromJson(o: JSONObject, fallbackId: String = "") = Msg(
            o.optString("i", fallbackId).ifEmpty { fallbackId },
            o.optString("a"), o.optString("p"), o.optString("t"), o.optString("m"),
            o.optLong("ts", System.currentTimeMillis()),
            o.optString("d"),
            o.optString("di"),
        )
    }
}

/** 업무폰에 쌓이는 받은 알림 기록 (최근 200건) */
object History {
    private const val MAX = 200
    private var cache: MutableList<Msg>? = null
    var onChange: (() -> Unit)? = null

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    @Synchronized
    private fun load(ctx: Context): MutableList<Msg> {
        cache?.let { return it }
        val list = mutableListOf<Msg>()
        try {
            val arr = JSONArray(sp(ctx).getString("items", "[]"))
            for (i in 0 until arr.length()) list.add(Msg.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Msg>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString("items", arr.toString()).apply()
        onChange?.invoke()
    }

    /** 최신순 목록 */
    @Synchronized
    fun all(ctx: Context): List<Msg> = load(ctx).toList()

    /** 이미 있는 ID면 false (중복 수신) */
    @Synchronized
    fun add(ctx: Context, m: Msg): Boolean {
        val list = load(ctx)
        if (list.any { it.id == m.id }) return false
        list.add(0, m)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx, list)
        return true
    }

    @Synchronized
    fun remove(ctx: Context, ids: Set<String>) {
        val list = load(ctx)
        list.removeAll { it.id in ids }
        save(ctx, list)
    }

    @Synchronized
    fun clear(ctx: Context) {
        load(ctx).clear()
        save(ctx, emptyList())
    }
}
