package com.happyfamily.twophone

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 두 폰 사이에서 암호화된 메시지를 날라 주는 중계 서버(ntfy 방식) 통신 */
object Relay {
    class RelayException(message: String) : IOException(message)

    private fun base(server: String) = server.trim().trimEnd('/')

    fun publish(server: String, topic: String, body: String) {
        val conn = URL("${base(server)}/$topic").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 429) throw RelayException("중계 서버 하루 사용량을 넘었습니다 (429)")
            if (code !in 200..299) throw RelayException("중계 서버 오류 ($code)")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 채널을 구독하고 메시지가 올 때마다 [onMessage](id, 본문)를 부른다.
     * 연결이 끊기면 예외를 던지며 돌아오므로, 부르는 쪽에서 다시 연결한다.
     */
    fun subscribe(
        server: String,
        topic: String,
        since: String,
        onOpen: (HttpURLConnection) -> Unit,
        onMessage: (id: String, body: String) -> Unit,
    ) {
        val q = URLEncoder.encode(since, "UTF-8")
        val conn = URL("${base(server)}/$topic/json?since=$q").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            // 서버가 45초마다 keepalive를 보내므로, 이보다 오래 조용하면 끊긴 것으로 본다
            conn.readTimeout = 100000
            val code = conn.responseCode
            if (code !in 200..299) throw RelayException("중계 서버 오류 ($code)")
            onOpen(conn)
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: throw RelayException("연결이 끊겼습니다")
                    if (line.isBlank()) continue
                    val o = try { JSONObject(line) } catch (e: Exception) { continue }
                    if (o.optString("event") == "message") {
                        onMessage(o.optString("id"), o.optString("message"))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
