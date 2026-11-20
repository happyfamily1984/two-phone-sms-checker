package com.happyfamily.twophone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {
    private enum class Screen { CHOOSER, CODE, SENDER, RECEIVER, APPS, COMPOSE }

    private lateinit var prefs: Prefs
    private var screen = Screen.CHOOSER
    private var pendingRole = ROLE_NONE

    /** 문자 보내기 화면에서 고른, 실제로 문자를 발신할 폰 */
    private var composeDevId = ""

    // 받은 알림 목록
    private var msgs: List<Msg> = emptyList()
    private val selected = mutableSetOf<String>()
    private var selecting = false
    private val msgAdapter = MsgAdapter()

    // 전달할 앱 목록
    private class AppItem(val pkg: String, val label: String, val icon: Drawable?, var checked: Boolean)
    private var appItems: List<AppItem> = emptyList()
    private val appAdapter = AppAdapter()

    private fun <T : View> v(id: Int): T = findViewById(id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        ReceiverService.createChannels(this)

        // 역할 고르기
        v<Button>(R.id.btnRoleSender).setOnClickListener { pendingRole = ROLE_SENDER; show(Screen.CODE) }
        v<Button>(R.id.btnRoleReceiver).setOnClickListener { pendingRole = ROLE_RECEIVER; show(Screen.CODE) }

        // 연결 코드
        v<Button>(R.id.btnNewCode).setOnClickListener { finishPairing(Crypto.newCode()) }
        v<Button>(R.id.btnJoin).setOnClickListener {
            val code = Crypto.normalizeCode(v<EditText>(R.id.etCode).text.toString())
            if (Crypto.isValidCode(code)) finishPairing(code)
            else toast("연결 코드 16자리를 정확히 입력해 주세요. (지금 ${code.length}자리)")
        }

        // 보내는 폰
        v<Switch>(R.id.swForward).setOnCheckedChangeListener { _, on ->
            if (prefs.forwardEnabled != on) { prefs.forwardEnabled = on; refresh() }
        }
        v<Button>(R.id.btnCopyS).setOnClickListener { copyCode() }
        v<Button>(R.id.btnAccess).setOnClickListener {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        v<Button>(R.id.btnAppInfo).setOnClickListener { openAppInfo() }
        v<Button>(R.id.btnBatteryS).setOnClickListener { askBattery() }
        v<Switch>(R.id.swSms).setOnCheckedChangeListener { _, on ->
            if (prefs.smsSendEnabled != on) setSmsSend(on)
        }
        v<Button>(R.id.btnApps).setOnClickListener { show(Screen.APPS) }
        v<Button>(R.id.btnName).setOnClickListener { editName() }
        v<Button>(R.id.btnTest).setOnClickListener { sendTest() }
        v<Button>(R.id.btnServerS).setOnClickListener { editServer() }
        v<Button>(R.id.btnResetS).setOnClickListener { confirmReset() }

        // 받는 폰
        v<Button>(R.id.btnPermR).setOnClickListener { fixReceiverPermission() }
        v<Button>(R.id.btnMenuR).setOnClickListener { receiverMenu() }
        v<Button>(R.id.btnCompose).setOnClickListener { openCompose("", "") }
        v<Button>(R.id.btnPickDevice).setOnClickListener { pickDevice() }
        v<Button>(R.id.btnSendSms).setOnClickListener { sendSms() }
        v<EditText>(R.id.etBody).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                v<TextView>(R.id.tvCount).text = "${s?.length ?: 0}자"
            }
        })
        v<TextView>(R.id.tvCount).text = "0자"
        v<Button>(R.id.btnSelCancel).setOnClickListener { endSelection() }
        v<Button>(R.id.btnSelDelete).setOnClickListener {
            val ids = selected.toSet()
            AlertDialog.Builder(this)
                .setMessage("선택한 ${ids.size}개의 알림 기록을 삭제할까요?")
                .setPositiveButton("삭제") { _, _ -> History.remove(this, ids); endSelection() }
                .setNegativeButton("취소", null)
                .show()
        }
        val lv = v<ListView>(R.id.lvMsgs)
        lv.adapter = msgAdapter
        lv.emptyView = v(R.id.tvEmpty)
        lv.setOnItemClickListener { _, _, pos, _ ->
            val m = msgs[pos]
            if (selecting) toggle(m.id) else showMessage(m)
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            selecting = true
            toggle(msgs[pos].id)
            true
        }

        // 앱 고르기
        val la = v<ListView>(R.id.lvApps)
        la.adapter = appAdapter
        la.setOnItemClickListener { _, _, pos, _ ->
            appItems[pos].checked = !appItems[pos].checked
            appAdapter.notifyDataSetChanged()
        }
        v<Switch>(R.id.swAll).setOnCheckedChangeListener { _, on ->
            la.alpha = if (on) 0.4f else 1f
            la.isEnabled = !on
        }

        show(home())
        if (prefs.role == ROLE_RECEIVER) enterReceiver()
        if (prefs.role == ROLE_SENDER) ReceiverService.startIfNeeded(this)
    }

    private fun home() = when (prefs.role) {
        ROLE_SENDER -> Screen.SENDER
        ROLE_RECEIVER -> Screen.RECEIVER
        else -> Screen.CHOOSER
    }

    private fun show(s: Screen) {
        if (screen == Screen.APPS && s != Screen.APPS) saveApps()
        screen = s
        val ids = mapOf(
            Screen.CHOOSER to R.id.screenChooser, Screen.CODE to R.id.screenCode,
            Screen.SENDER to R.id.screenSender, Screen.RECEIVER to R.id.screenReceiver,
            Screen.APPS to R.id.screenApps, Screen.COMPOSE to R.id.screenCompose,
        )
        for ((k, id) in ids) v<View>(id).visibility = if (k == s) View.VISIBLE else View.GONE
        v<TextView>(R.id.tvBar).text = when (s) {
            Screen.CODE -> if (pendingRole == ROLE_SENDER) "보내는 폰 연결" else "받는 폰 연결"
            Screen.APPS -> "전달할 앱 선택"
            Screen.COMPOSE -> "문자 보내기"
            else -> getString(R.string.app_name)
        }
        if (s == Screen.APPS) loadApps()
        refresh()
    }

    private fun finishPairing(code: String) {
        prefs.pair(pendingRole, code)
        v<EditText>(R.id.etCode).setText("")
        if (pendingRole == ROLE_SENDER) {
            if (prefs.apps.isEmpty()) prefs.apps = ForwardListenerService.defaultApps(this)
        } else {
            enterReceiver()
        }
        show(home())
    }

    private fun enterReceiver() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ReceiverService.startIfNeeded(this)
    }

    // ───────── 화면 갱신 ─────────

    private fun refresh() {
        when (screen) {
            Screen.SENDER -> refreshSender()
            Screen.RECEIVER -> refreshReceiver()
            Screen.COMPOSE -> refreshCompose()
            else -> {}
        }
    }

    private fun batteryOk() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun notifyOk() = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refreshSender() {
        val access = ForwardListenerService.isAccessGranted(this)
        val battery = batteryOk()
        val on = prefs.forwardEnabled

        val state = v<TextView>(R.id.tvSenderState)
        when {
            !access -> { state.text = "⚠ 알림 접근 권한을 켜야 동작합니다"; state.setTextColor(getColor(R.color.warn)) }
            !on -> { state.text = "전달이 꺼져 있습니다"; state.setTextColor(getColor(R.color.sub)) }
            else -> { state.text = "✓ 동작 중 — 알림이 오면 다른 폰으로 보냅니다"; state.setTextColor(getColor(R.color.ok)) }
        }
        v<Switch>(R.id.swForward).isChecked = on

        val stats = StringBuilder("지금까지 보낸 알림: ${prefs.sentCount}건")
        if (prefs.lastSentAt > 0) stats.append("\n마지막 전송: ${relTime(prefs.lastSentAt)}")
        if (prefs.lastError.isNotEmpty()) stats.append("\n최근 오류: ${prefs.lastError}")
        v<TextView>(R.id.tvStats).text = stats

        v<TextView>(R.id.tvCodeS).text = Crypto.formatCode(prefs.code)

        v<TextView>(R.id.tvAccess).text =
            if (access) "✓ 알림 접근 권한이 켜져 있습니다"
            else "이 폰에 도착한 알림을 읽으려면 ‘알림 접근 권한’이 필요합니다. 목록에서 ‘투폰문자체커’를 켜 주세요."
        for (id in intArrayOf(R.id.btnAccess, R.id.tvAccessHelp, R.id.btnAppInfo)) {
            v<View>(id).visibility = if (access) View.GONE else View.VISIBLE
        }
        v<TextView>(R.id.tvBatteryS).text =
            if (battery) "✓ 배터리 사용 제한이 풀려 있습니다"
            else "화면이 꺼져 있을 때도 바로 보내려면 배터리 사용 제한을 풀어 주세요."
        v<View>(R.id.btnBatteryS).visibility = if (battery) View.GONE else View.VISIBLE

        val smsOn = prefs.smsSendEnabled
        v<Switch>(R.id.swSms).isChecked = smsOn
        v<TextView>(R.id.tvSmsState).apply {
            visibility = if (smsOn) View.VISIBLE else View.GONE
            val granted = smsGranted()
            text = when {
                !granted -> "⚠ 문자 발신 권한이 없습니다. 스위치를 껐다 켜서 권한을 허용해 주세요."
                ReceiverService.connected -> "✓ ${ReceiverService.statusText}"
                else -> "⚠ ${ReceiverService.statusText}"
            }
            setTextColor(getColor(if (granted && ReceiverService.connected) R.color.ok else R.color.warn))
        }

        v<Button>(R.id.btnApps).text =
            if (prefs.forwardAll) "전달할 앱 선택 (모든 앱)" else "전달할 앱 선택 (${prefs.apps.size}개)"
        v<Button>(R.id.btnName).text = "이 폰 이름: ${prefs.deviceName}"
    }

    private fun refreshReceiver() {
        val conn = v<TextView>(R.id.tvConn)
        if (ReceiverService.connected) {
            conn.text = "✓ 연결됨 — ${ReceiverService.statusText}"
            conn.setTextColor(getColor(R.color.ok))
        } else {
            conn.text = "⚠ ${ReceiverService.statusText}"
            conn.setTextColor(getColor(R.color.warn))
        }
        v<TextView>(R.id.tvCodeR).text = "연결 코드  ${Crypto.formatCode(prefs.code)}"

        val problem = when {
            !notifyOk() -> "알림 권한이 꺼져 있어 알림을 띄울 수 없습니다."
            !batteryOk() -> "화면이 꺼져 있을 때도 바로 받으려면 배터리 사용 제한을 풀어 주세요."
            else -> ""
        }
        v<TextView>(R.id.tvPermR).apply { text = problem; visibility = if (problem.isEmpty()) View.GONE else View.VISIBLE }
        v<View>(R.id.btnPermR).visibility = if (problem.isEmpty()) View.GONE else View.VISIBLE

        msgs = History.all(this)
        selected.retainAll(msgs.map { it.id }.toSet())
        if (selecting && selected.isEmpty()) selecting = false
        v<View>(R.id.selBar).visibility = if (selecting) View.VISIBLE else View.GONE
        v<TextView>(R.id.tvSel).text = "${selected.size}개 선택"
        msgAdapter.notifyDataSetChanged()
    }

    // ───────── 보내는 폰 동작 ─────────

    private fun sendTest() {
        toast("테스트 알림을 보내는 중…")
        val msg = Msg(
            UUID.randomUUID().toString(), "테스트", packageName, "연결 확인",
            "이 알림이 보이면 정상적으로 연결된 것입니다.", System.currentTimeMillis(), prefs.deviceName.take(30), prefs.deviceId,
        )
        ForwardListenerService.send(this, msg) { error ->
            runOnUiThread {
                toast(if (error == null) "보냈습니다. 받는 폰을 확인하세요." else "전송 실패: $error")
            }
        }
    }

    private fun smsGranted() =
        checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun setSmsSend(on: Boolean) {
        if (!on) {
            prefs.smsSendEnabled = false
            ReceiverService.stop(this)
            refresh()
            return
        }
        if (!smsGranted()) {
            // 결과는 onRequestPermissionsResult에서 이어서 처리한다
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        prefs.smsSendEnabled = true
        ReceiverService.startIfNeeded(this)
        refresh()
    }

    private fun editName() {
        val et = EditText(this).apply { setText(prefs.deviceName); setSingleLine(); hint = "예: 개인폰" }
        AlertDialog.Builder(this)
            .setTitle("이 폰 이름")
            .setMessage("받는 폰에서 어느 폰의 알림인지 구분하는 데 쓰입니다.")
            .setView(et)
            .setPositiveButton("저장") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotEmpty()) prefs.deviceName = name
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadApps() {
        v<Switch>(R.id.swAll).isChecked = prefs.forwardAll
        val chosen = prefs.apps
        Thread {
            val pm = packageManager
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.toMutableSet()
            found.addAll(chosen)
            found.remove(packageName)
            val items = found.mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    AppItem(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), pkg in chosen)
                } catch (e: Exception) {
                    null
                }
            }.sortedWith(compareBy<AppItem> { !it.checked }.thenBy { it.label.lowercase() })
            runOnUiThread { appItems = items; appAdapter.notifyDataSetChanged() }
        }.start()
    }

    private fun saveApps() {
        prefs.forwardAll = v<Switch>(R.id.swAll).isChecked
        if (appItems.isNotEmpty()) prefs.apps = appItems.filter { it.checked }.map { it.pkg }.toSet()
        appItems = emptyList()
        appAdapter.notifyDataSetChanged()
    }

    // ───────── 받는 폰 동작 ─────────

    private fun fixReceiverPermission() {
        if (!notifyOk()) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            askBattery()
        }
    }

    private fun receiverMenu() {
        val items = arrayOf("연결 코드 복사", "받은 기록 모두 지우기", "중계 서버 변경 (고급)", "연결 해제 · 처음부터 다시")
        AlertDialog.Builder(this).setTitle("설정 메뉴").setItems(items) { _, which ->
            when (which) {
                0 -> copyCode()
                1 -> AlertDialog.Builder(this)
                    .setMessage("받은 알림 기록을 모두 지울까요?")
                    .setPositiveButton("지우기") { _, _ -> History.clear(this); endSelection() }
                    .setNegativeButton("취소", null).show()
                2 -> editServer()
                3 -> confirmReset()
            }
        }.show()
    }

    // ───────── 문자 보내기 (받는 폰) ─────────

    private fun openCompose(devId: String, number: String) {
        val devices = prefs.devices
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("아직 연결된 보내는 폰을 찾지 못했습니다.\n\n보내는 폰에서 ‘테스트 알림 보내기’를 한 번 눌러 주세요. 알림이 도착하면 그 폰으로 문자를 보낼 수 있습니다.")
                .setPositiveButton("확인", null).show()
            return
        }
        if (devId in devices) composeDevId = devId
        if (composeDevId !in devices) composeDevId = devices.keys.first()
        if (number.isNotEmpty()) v<EditText>(R.id.etTo).setText(number)
        endSelection()
        show(Screen.COMPOSE)
    }

    private fun refreshCompose() {
        val devices = prefs.devices
        val name = devices[composeDevId] ?: "폰 선택"
        v<Button>(R.id.btnPickDevice).text = if (devices.size > 1) "$name  (눌러서 변경)" else name
    }

    private fun pickDevice() {
        val devices = prefs.devices.toList()
        if (devices.size <= 1) return
        AlertDialog.Builder(this).setTitle("문자를 발신할 폰")
            .setItems(devices.map { it.second }.toTypedArray()) { _, i ->
                composeDevId = devices[i].first
                refresh()
            }.show()
    }

    private fun sendSms() {
        val to = SmsSender.cleanNumber(v<EditText>(R.id.etTo).text.toString())
        val body = v<EditText>(R.id.etBody).text.toString().trim()
        val devName = prefs.devices[composeDevId]
        when {
            devName == null -> { toast("문자를 발신할 폰을 골라 주세요"); return }
            to == null -> { toast("받는 사람 번호를 정확히 입력해 주세요"); return }
            body.isEmpty() -> { toast("내용을 입력해 주세요"); return }
        }
        AlertDialog.Builder(this)
            .setTitle("문자를 보낼까요?")
            .setMessage("발신: $devName\n받는 사람: $to\n\n$body")
            .setPositiveButton("보내기") { _, _ ->
                val btn = v<Button>(R.id.btnSendSms)
                btn.isEnabled = false
                ForwardListenerService.sendJson(this, true, SmsSender.buildCommand(composeDevId, to!!, body)) { error ->
                    runOnUiThread {
                        btn.isEnabled = true
                        if (error == null) {
                            v<EditText>(R.id.etBody).setText("")
                            toast("$devName(으)로 요청을 보냈습니다. 결과는 알림으로 옵니다.")
                            if (screen == Screen.COMPOSE) show(Screen.RECEIVER)
                        } else toast("요청 전송 실패: $error")
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showMessage(m: Msg) {
        val head = listOf(m.dev, m.app).filter { it.isNotEmpty() }.joinToString(" · ")
        AlertDialog.Builder(this)
            .setTitle(m.title.ifEmpty { m.app })
            .setMessage("$head\n${DateUtils.formatDateTime(this, m.ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)}\n\n${m.text}")
            .setPositiveButton("닫기", null)
            .setNeutralButton("내용 복사") { _, _ -> copy(m.text); toast("복사했습니다") }
            .setNegativeButton("문자 보내기") { _, _ ->
                // 알림 제목이 전화번호라면 받는 사람 칸에 미리 넣어 준다
                openCompose(m.devId, SmsSender.cleanNumber(m.title).orEmpty())
            }
            .show()
    }

    private fun toggle(id: String) {
        if (!selected.add(id)) selected.remove(id)
        refresh()
    }

    private fun endSelection() {
        selecting = false
        selected.clear()
        refresh()
    }

    // ───────── 공통 ─────────

    private fun copyCode() {
        copy(Crypto.formatCode(prefs.code))
        toast("연결 코드를 복사했습니다")
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("투폰문자체커", text))
    }

    private fun editServer() {
        val et = EditText(this).apply { setText(prefs.server); setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("중계 서버 주소")
            .setMessage("보통은 바꿀 필요가 없습니다. 바꾼다면 모든 폰에 같은 주소를 넣어야 합니다. (ntfy 호환 서버)")
            .setView(et)
            .setPositiveButton("저장") { _, _ ->
                val url = et.text.toString().trim()
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    prefs.server = url
                    restartReceiver()
                } else toast("https:// 로 시작하는 주소를 입력해 주세요")
            }
            .setNeutralButton("기본값") { _, _ -> prefs.server = DEFAULT_SERVER; restartReceiver() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun restartReceiver() {
        ReceiverService.stop(this)
        v<View>(R.id.tvBar).postDelayed({ ReceiverService.startIfNeeded(this) }, 500)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("연결 해제")
            .setMessage("이 폰의 연결을 끊고 처음 화면으로 돌아갑니다. 다시 쓰려면 연결 코드를 새로 입력해야 합니다.")
            .setPositiveButton("연결 해제") { _, _ ->
                ReceiverService.stop(this)
                prefs.reset()
                endSelection()
                show(home())
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppInfo() =
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) { toast("설정 화면을 열 수 없습니다") }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun relTime(ts: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

    // ───────── 뒤로가기 ─────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selecting -> endSelection()                    // 선택 모드 취소
            screen == Screen.APPS -> show(Screen.SENDER)   // 이전 화면 (저장됨)
            screen == Screen.CODE -> show(Screen.CHOOSER)
            screen == Screen.COMPOSE -> show(Screen.RECEIVER) // 쓰던 내용은 그대로 남는다
            else -> AlertDialog.Builder(this)              // 홈
                .setMessage("앱을 종료하겠습니까?")
                .setPositiveButton("예") { _, _ -> finish() }
                .setNegativeButton("아니오", null)
                .show()
        }
    }

    // ───────── 생명주기 ─────────

    override fun onResume() {
        super.onResume()
        val update = { runOnUiThread { refresh() } }
        History.onChange = update
        ReceiverService.onStateChange = update
        ForwardListenerService.onStatusChange = update
        ReceiverService.startIfNeeded(this)
        refresh()
    }

    override fun onPause() {
        History.onChange = null
        ReceiverService.onStateChange = null
        ForwardListenerService.onStatusChange = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        if (code == 2) {
            if (smsGranted()) {
                prefs.smsSendEnabled = true
                ReceiverService.startIfNeeded(this)
            } else {
                AlertDialog.Builder(this)
                    .setMessage("문자 발신 권한이 허용되지 않았습니다.\n\n권한 창이 뜨지 않았다면 ‘앱 정보 열기’ → 오른쪽 위 ⋮ → ‘제한된 설정 허용’을 누른 뒤, 권한 → SMS를 허용해 주세요.")
                    .setPositiveButton("앱 정보 열기") { _, _ -> openAppInfo() }
                    .setNegativeButton("닫기", null).show()
            }
        }
        refresh()
    }

    // ───────── 목록 어댑터 ─────────

    private inner class MsgAdapter : BaseAdapter() {
        override fun getCount() = msgs.size
        override fun getItem(position: Int) = msgs[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_msg, parent, false)
            val m = msgs[position]
            row.findViewById<TextView>(R.id.tvTitle).text = m.title.ifEmpty { m.app }
            row.findViewById<TextView>(R.id.tvTime).text = relTime(m.ts)
            row.findViewById<TextView>(R.id.tvFrom).text =
                listOf(m.dev, m.app).filter { it.isNotEmpty() }.joinToString(" · ")
            row.findViewById<TextView>(R.id.tvText).text = m.text
            val sel = m.id in selected
            row.setBackgroundColor(getColor(if (sel) R.color.selected else R.color.card))
            row.isSelected = sel
            return row
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = appItems.size
        override fun getItem(position: Int) = appItems[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val a = appItems[position]
            row.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(a.icon)
            row.findViewById<TextView>(R.id.tvName).text = a.label
            row.findViewById<CheckBox>(R.id.cb).isChecked = a.checked
            row.contentDescription = "${a.label}, ${if (a.checked) "선택됨" else "선택 안 됨"}"
            return row
        }
    }
}
