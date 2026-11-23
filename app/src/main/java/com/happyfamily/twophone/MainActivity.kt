package com.happyfamily.twophone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
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
        setupEdgeToEdge()
        setupBack()

        v<View>(R.id.btnBack).setOnClickListener { handleBack() }

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
        v<Button>(R.id.btnAccess).setOnClickListener { askNotificationAccess() }
        v<Button>(R.id.btnAppInfo).setOnClickListener { openAppInfo() }
        v<Button>(R.id.btnBatteryS).setOnClickListener { askBattery() }
        v<Switch>(R.id.swSms).setOnCheckedChangeListener { _, on ->
            if (prefs.smsSendEnabled != on) setSmsSend(on)
        }
        v<Button>(R.id.btnTest).setOnClickListener { sendTest() }
        row(R.id.rowApps) { show(Screen.APPS) }
        row(R.id.rowName) { editName() }
        row(R.id.rowServerS) { editServer() }
        row(R.id.rowPrivacyS) { showPrivacy() }
        row(R.id.rowResetS) { confirmReset() }

        // 받는 폰
        v<Button>(R.id.btnPermR).setOnClickListener { fixReceiverPermission() }
        row(R.id.btnMenuR) { receiverMenu() }
        row(R.id.btnCompose) { openCompose("", "") }
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

        // 문자 보내기
        row(R.id.btnPickDevice) { pickDevice() }
        row(R.id.btnSendSms) { sendSms() }
        val etTo = v<EditText>(R.id.etTo)
        v<View>(R.id.btnClearTo).setOnClickListener { etTo.setText(""); etTo.requestFocus() }
        etTo.addTextChangedListener(afterChange {
            v<View>(R.id.btnClearTo).visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
        })
        v<EditText>(R.id.etBody).addTextChangedListener(afterChange { v<TextView>(R.id.tvCount).text = "${it.length}자" })
        v<TextView>(R.id.tvCount).text = "0자"
        v<TextView>(R.id.tvComposeInfo).text = composeInfoText()

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
    }

    // ───────── 화면 틀 ─────────

    /** 화면을 상태 표시줄·내비게이션 바 아래까지 그리고, 그만큼 안쪽 여백을 준다. */
    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val header = v<View>(R.id.headerText)
        val content = v<View>(R.id.content)
        val headerTop = header.paddingTop
        v<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                top = bars.top
                bottom = maxOf(bars.bottom, ime.bottom)
                content.setPadding(bars.left, 0, bars.right, bottom)
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
                content.setPadding(0, 0, 0, bottom)
            }
            header.setPadding(header.paddingLeft, headerTop + top, header.paddingRight, header.paddingBottom)
            insets
        }
    }

    private fun setupBack() {
        // Android 13 이상: 시스템 뒤로가기(제스처 포함)를 항상 이 화면이 처리한다
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                handleBack()
            }
        }
    }

    /** 클릭할 수 있는 줄·묶음 단추에 동작을 걸고, 화면 낭독기가 ‘버튼’으로 읽게 한다. */
    private fun row(id: Int, action: () -> Unit) {
        val view = v<View>(id)
        view.setOnClickListener { action() }
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }

    private fun afterChange(block: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
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

        val sub = Screen.SENDER == s || Screen.RECEIVER == s || Screen.CHOOSER == s
        v<View>(R.id.btnBack).visibility = if (sub) View.GONE else View.VISIBLE
        v<View>(R.id.titleBadge).visibility = if (sub) View.VISIBLE else View.GONE
        v<ImageView>(R.id.ivHeaderArt).setImageResource(
            if (s == Screen.COMPOSE) R.drawable.header_plane else R.drawable.header_phones
        )
        v<TextView>(R.id.tvBar).text = when (s) {
            Screen.CODE -> if (pendingRole == ROLE_SENDER) "보내는 폰 연결" else "받는 폰 연결"
            Screen.APPS -> "전달할 앱"
            Screen.COMPOSE -> "문자 보내기"
            else -> getString(R.string.app_name)
        }
        v<TextView>(R.id.tvSub).text = when (s) {
            Screen.CHOOSER -> "여러 폰의 알림을\n한 곳에서 확인하세요!"
            Screen.CODE -> "연결 코드 하나로\n폰들을 안전하게 묶어요"
            Screen.SENDER -> "이 폰의 알림을\n다른 폰으로 보내고 있어요"
            Screen.RECEIVER -> "연결된 폰들의 알림을\n한 번에 확인하세요!"
            Screen.APPS -> "어떤 앱의 알림을\n보낼지 고르세요"
            Screen.COMPOSE -> "어느 폰의 번호로 보낼까요?"
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

    private fun badge(circle: Int, icon: Int, ok: Boolean) {
        v<View>(circle).setBackgroundResource(if (ok) R.drawable.circle_ok else R.drawable.circle_warn)
        v<ImageView>(icon).setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning)
    }

    private fun refreshSender() {
        val access = ForwardListenerService.isAccessGranted(this)
        val battery = batteryOk()
        val on = prefs.forwardEnabled

        val state = v<TextView>(R.id.tvSenderState)
        when {
            !access -> { state.text = "알림 접근 권한을 켜야 동작합니다"; state.setTextColor(getColor(R.color.warn)) }
            !on -> { state.text = "전달이 꺼져 있습니다"; state.setTextColor(getColor(R.color.sub)) }
            else -> { state.text = "동작 중 — 알림을 보내고 있어요"; state.setTextColor(getColor(R.color.ok)) }
        }
        badge(R.id.senderBadge, R.id.ivSenderBadge, access && on)
        v<Switch>(R.id.swForward).isChecked = on

        val stats = StringBuilder("보낸 알림 ${prefs.sentCount}건")
        if (prefs.lastSentAt > 0) stats.append(" · 마지막 ${relTime(prefs.lastSentAt)}")
        if (prefs.lastError.isNotEmpty()) stats.append("\n최근 오류: ${prefs.lastError}")
        v<TextView>(R.id.tvStats).text = stats

        v<TextView>(R.id.tvCodeS).text = Crypto.formatCode(prefs.code)

        // 권한이 모두 갖춰지면 권한 카드를 숨긴다
        v<View>(R.id.cardPerms).visibility = if (access && battery) View.GONE else View.VISIBLE
        v<TextView>(R.id.tvAccess).text =
            if (access) "✓ 알림 접근 권한이 켜져 있습니다"
            else "이 폰에 도착한 알림을 읽으려면 ‘알림 접근 권한’이 필요합니다."
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

        v<TextView>(R.id.tvRowApps).text =
            if (prefs.forwardAll) "전달할 앱 선택 (모든 앱)" else "전달할 앱 선택 (${prefs.apps.size}개)"
        v<TextView>(R.id.tvRowName).text = "이 폰 이름: ${prefs.deviceName}"
    }

    private fun refreshReceiver() {
        val ok = ReceiverService.connected
        val conn = v<TextView>(R.id.tvConn)
        val head = if (ok) "연결됨" else "연결 안 됨"
        val sb = SpannableStringBuilder(head).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColor(if (ok) R.color.ok else R.color.warn)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" – ").append(ReceiverService.statusText)
        }
        conn.text = sb
        badge(R.id.connBadge, R.id.ivConnBadge, ok)
        v<ImageView>(R.id.ivWifi).imageTintList =
            android.content.res.ColorStateList.valueOf(if (ok) 0xFFB3ACF5.toInt() else 0xFFD0CDE0.toInt())
        v<TextView>(R.id.tvCodeR).text = "연결 코드  ${Crypto.formatCode(prefs.code)}"

        val problem = when {
            !notifyOk() -> "알림 권한이 꺼져 있어 알림을 띄울 수 없습니다."
            !batteryOk() -> "화면이 꺼져도 바로 받으려면 배터리 사용 제한을 풀어 주세요."
            else -> ""
        }
        v<TextView>(R.id.tvPermR).text = problem
        v<View>(R.id.cardPermR).visibility = if (problem.isEmpty() || selecting) View.GONE else View.VISIBLE

        msgs = History.all(this)
        selected.retainAll(msgs.map { it.id }.toSet())
        if (selecting && selected.isEmpty()) selecting = false
        v<View>(R.id.selBar).visibility = if (selecting) View.VISIBLE else View.GONE
        v<TextView>(R.id.tvSel).text = "${selected.size}개 선택됨"
        msgAdapter.notifyDataSetChanged()
    }

    // ───────── 보내는 폰 동작 ─────────

    /** Play 정책: 알림 내용을 읽기 전에 무엇을 왜 하는지 알리고 동의를 받는다. */
    private fun askNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("알림 내용 전달에 대한 안내")
            .setMessage(
                "투폰문자체커는 이 폰에 도착하는 알림(보낸 사람·내용)을 읽어, " +
                    "같은 연결 코드로 묶은 내 다른 폰으로 보냅니다.\n\n" +
                    "• ‘전달할 앱’으로 고른 앱의 알림만 보냅니다.\n" +
                    "• 내용은 이 폰에서 암호화된 뒤 전송되며, 개발자와 중계 서버를 포함해 " +
                    "연결 코드를 모르는 누구도 볼 수 없습니다.\n" +
                    "• 다른 용도로 쓰거나 저장하지 않습니다.\n\n" +
                    "동의하시면 다음 화면에서 ‘투폰문자체커’를 켜 주세요."
            )
            .setPositiveButton("동의하고 계속") { _, _ -> openListenerSettings() }
            .setNegativeButton("동의 안 함", null)
            .show()
    }

    private fun openListenerSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(this, ForwardListenerService::class.java).flattenToString(),
                    )
                )
                return
            } catch (_: Exception) {
            }
        }
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun sendTest() {
        toast("테스트 알림을 보내는 중…")
        val msg = Msg(
            UUID.randomUUID().toString(), "테스트", packageName, "연결 확인",
            "이 알림이 보이면 정상적으로 연결된 것입니다.", System.currentTimeMillis(),
            prefs.deviceName.take(30), prefs.deviceId,
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
        if (smsGranted()) {
            prefs.smsSendEnabled = true
            ReceiverService.startIfNeeded(this)
            refresh()
            return
        }
        // Play 정책: 권한을 묻기 전에 용도를 알린다
        AlertDialog.Builder(this)
            .setTitle("문자 발신 권한 안내")
            .setMessage(
                "받는 폰에서 작성한 문자를 이 폰의 번호로 보내기 위해 ‘문자 보내기’ 권한을 사용합니다.\n\n" +
                    "• 내가 연결한 받는 폰에서 요청하고, 보내기 전 확인한 문자만 발신합니다.\n" +
                    "• 이 폰의 문자를 읽거나 저장하지 않습니다.\n" +
                    "• 언제든 스위치를 꺼서 중단할 수 있습니다."
            )
            .setPositiveButton("동의하고 계속") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            .setNegativeButton("동의 안 함") { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun editName() {
        val et = EditText(this).apply { setText(prefs.deviceName); setSingleLine(); hint = "예: 개인폰" }
        AlertDialog.Builder(this)
            .setTitle("이 폰 이름")
            .setMessage("받는 폰에서 어느 폰의 알림인지 구분하는 데 쓰입니다.")
            .setView(padded(et))
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
        val items = arrayOf(
            "연결 코드 복사", "받은 기록 모두 지우기", "중계 서버 변경 (고급)", "개인정보처리방침", "연결 해제 · 처음부터 다시",
        )
        AlertDialog.Builder(this).setTitle("설정 메뉴").setItems(items) { _, which ->
            when (which) {
                0 -> copyCode()
                1 -> AlertDialog.Builder(this)
                    .setMessage("받은 알림 기록을 모두 지울까요?")
                    .setPositiveButton("지우기") { _, _ -> History.clear(this); endSelection() }
                    .setNegativeButton("취소", null).show()
                2 -> editServer()
                3 -> showPrivacy()
                4 -> confirmReset()
            }
        }.show()
    }

    // ───────── 문자 보내기 (받는 폰) ─────────

    private fun composeInfoText(): CharSequence {
        val sb = SpannableStringBuilder()
        fun plain(s: String) = sb.append(s)
        fun strong(s: String) {
            val start = sb.length
            sb.append(s)
            sb.setSpan(ForegroundColorSpan(getColor(R.color.primary)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        plain("문자는 선택한 폰에서 그 폰의 번호로 발신되고, 결과는 알림으로 돌아옵니다. 그 폰에서 ")
        strong("‘받는 폰에서 문자 발신 허용’")
        plain("이 켜져 있어야 합니다. 긴 문자(")
        strong("한글 약 70자 초과")
        plain(")는 통신사에 따라 전송되지 않을 수 있습니다.")
        return sb
    }

    private fun openCompose(devId: String, number: String) {
        val devices = prefs.devices
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("보내는 폰을 찾지 못했습니다")
                .setMessage("보내는 폰에서 ‘테스트 알림 보내기’를 한 번 눌러 주세요. 알림이 도착하면 그 폰의 번호로 문자를 보낼 수 있습니다.")
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
        v<TextView>(R.id.tvPickDevice).text = name
        val many = devices.size > 1
        v<View>(R.id.ivPickChevron).visibility = if (many) View.VISIBLE else View.INVISIBLE
        v<View>(R.id.btnPickDevice).contentDescription =
            "발신할 폰: $name" + if (many) ", 눌러서 변경" else ""
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
            to == null -> { toast("받는 사람 번호를 정확히 입력해 주세요"); v<View>(R.id.etTo).requestFocus(); return }
            body.isEmpty() -> { toast("내용을 입력해 주세요"); v<View>(R.id.etBody).requestFocus(); return }
        }
        AlertDialog.Builder(this)
            .setTitle("문자를 보낼까요?")
            .setMessage("발신: $devName\n받는 사람: $to\n\n$body")
            .setPositiveButton("보내기") { _, _ ->
                val btn = v<View>(R.id.btnSendSms)
                btn.isEnabled = false
                btn.alpha = 0.6f
                ForwardListenerService.sendJson(this, true, SmsSender.buildCommand(composeDevId, to!!, body)) { error ->
                    runOnUiThread {
                        btn.isEnabled = true
                        btn.alpha = 1f
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

    private fun showPrivacy() {
        AlertDialog.Builder(this)
            .setTitle("개인정보처리방침")
            .setMessage(getString(R.string.privacy_policy))
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun padded(view: View): View = android.widget.FrameLayout(this).apply {
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        addView(view)
    }

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
            .setView(padded(et))
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

    private fun handleBack() {
        when {
            selecting -> endSelection()                       // 선택 모드 취소
            screen == Screen.APPS -> show(Screen.SENDER)      // 이전 화면 (저장됨)
            screen == Screen.CODE -> show(Screen.CHOOSER)
            screen == Screen.COMPOSE -> show(Screen.RECEIVER) // 쓰던 내용은 그대로 남는다
            else -> AlertDialog.Builder(this)                 // 홈
                .setMessage("앱을 종료하겠습니까?")
                .setPositiveButton("예") { _, _ -> finish() }
                .setNegativeButton("아니오", null)
                .show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack() // Android 12 이하

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
                    .setMessage("문자 발신 권한이 허용되지 않았습니다.\n\n권한 창이 뜨지 않았다면 ‘앱 정보 열기’ → 권한 → SMS를 허용해 주세요.")
                    .setPositiveButton("앱 정보 열기") { _, _ -> openAppInfo() }
                    .setNegativeButton("닫기", null).show()
            }
        }
        refresh()
    }

    // ───────── 목록 어댑터 ─────────

    /** 알림 종류에 맞는 동그라미 아이콘 */
    private fun kindIcon(m: Msg): Int = when {
        m.pkg == packageName -> if (m.app == "문자 발신") R.drawable.ic_send else R.drawable.ic_check
        m.pkg.contains("messaging") || m.pkg.contains("mms") || m.pkg.contains("sms") -> R.drawable.ic_call
        else -> R.drawable.ic_chat
    }

    private inner class MsgAdapter : BaseAdapter() {
        override fun getCount() = msgs.size
        override fun getItem(position: Int) = msgs[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_msg, parent, false)
            val m = msgs[position]
            val sel = m.id in selected
            val title = m.title.ifEmpty { m.app }
            val from = listOf(m.dev, m.app).filter { it.isNotEmpty() }.joinToString(" · ")
            val time = relTime(m.ts)
            row.findViewById<TextView>(R.id.tvTitle).text = title
            row.findViewById<TextView>(R.id.tvTime).text = time
            row.findViewById<TextView>(R.id.tvFrom).text = from
            row.findViewById<TextView>(R.id.tvText).text = m.text
            row.findViewById<View>(R.id.card).setBackgroundResource(if (sel) R.drawable.card_selected else R.drawable.card_ripple)
            row.findViewById<View>(R.id.kindCircle).setBackgroundResource(if (sel) R.drawable.circle_primary else R.drawable.icon_circle)
            row.findViewById<ImageView>(R.id.ivKind).apply {
                setImageResource(if (sel) R.drawable.ic_check else kindIcon(m))
                imageTintList = android.content.res.ColorStateList.valueOf(getColor(if (sel) R.color.white else R.color.primary))
            }
            row.findViewById<View>(R.id.ivChevron).visibility = if (selecting) View.INVISIBLE else View.VISIBLE
            row.contentDescription = (if (selecting) (if (sel) "선택됨, " else "선택 안 됨, ") else "") +
                "$title, $from, $time, ${m.text}"
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
