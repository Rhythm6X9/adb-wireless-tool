package com.yg.adbtool;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * 主界面 —— 以「无线调试」为核心动作。
 *
 * 页面结构：
 *   ① 权限环境       root / Shizuku / 普通
 *   ② 一键开无线调试  ← 主按钮
 *   ③ 无线调试状态    开关 / TLS 端口 / 配对端口 / 连接端口 / mDNS
 *   ④ 连接与配对      adb pair / adb connect / 复制命令
 *   ⑤ 辅助开关        开发者选项 / USB 调试 / tcpip 兜底 / 写公钥
 *   ⑥ 日志
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF0F131A;
    private static final int PANEL = 0xFF161B22;
    private static final int PANEL2 = 0xFF1E252E;
    private static final int TXT = 0xFFE6EDF3;
    private static final int DIM = 0xFF8B949E;
    private static final int ACCENT = 0xFF2F81F7;
    private static final int OKC = 0xFF3FB950;
    private static final int WARNC = 0xFFD29922;
    private static final int ERRC = 0xFFF85149;
    private static final int MONO = 0xFFC9D1D9;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvPerm;
    private TextView tvPermDetail;
    private TextView tvInfo;
    private TextView tvWifiState;
    private TextView tvPairCmd;
    private TextView tvConnCmd;
    private TextView tvLog;
    private EditText etCode;
    private EditText etIp;
    private EditText etTcpPort;
    private Button btnAuto;
    private final StringBuilder logBuf = new StringBuilder();
    private volatile boolean busy = false;
    private int pairPort = 0;
    private int connectPort = 0;
    private String deviceIp = "";

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                log(ok ? "Shizuku 已授权" : "Shizuku 授权被拒绝", ok ? OKC : ERRC);
                refreshEnv();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        try { Shizuku.addRequestPermissionResultListener(permListener); } catch (Throwable ignored) {}
        refreshEnv();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「无线调试」页面切回来时，端口可能刚分配出来
        refreshWifiState();
    }

    @Override
    protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(permListener); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    //  界面
    // ------------------------------------------------------------------
    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        sv.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(PANEL);
        top.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("无线调试工具");
        title.setTextColor(TXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);

        TextView sub = new TextView(this);
        sub.setText("目标：打开「无线调试」并拿到配对/连接端口");
        sub.setTextColor(DIM);
        sub.setTextSize(11.5f);
        top.addView(sub);

        tvPerm = new TextView(this);
        tvPerm.setTextColor(TXT);
        tvPerm.setTextSize(13);
        tvPerm.setText("当前权限：检测中…");
        tvPerm.setPadding(0, dp(8), 0, 0);
        top.addView(tvPerm);

        tvPermDetail = new TextView(this);
        tvPermDetail.setTextColor(DIM);
        tvPermDetail.setTextSize(11);
        tvPermDetail.setText("");
        top.addView(tvPermDetail);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 内容
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(24));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ① 权限
        LinearLayout g1 = group("① 权限环境");
        LinearLayout r1 = row();
        r1.addView(btn("刷新环境", true, v -> refreshEnv()));
        r1.addView(btn("请求 Shizuku 授权", false, v -> {
            if (!Shell.shizukuAlive()) {
                log("Shizuku 未运行：先装 Shizuku 并用无线调试方式启动它", ERRC);
            } else {
                Shell.requestShizuku();
                log("已发起 Shizuku 授权请求", ACCENT);
            }
        }));
        g1.addView(r1);

        tvInfo = new TextView(this);
        tvInfo.setTextColor(MONO);
        tvInfo.setTextSize(11);
        tvInfo.setTypeface(Typeface.MONOSPACE);
        tvInfo.setText("点击「刷新环境」读取设备信息");
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        g1.addView(tvInfo, ilp);
        body.addView(g1);

        // ② 一键
        LinearLayout g2 = group("② 一键开启无线调试");
        btnAuto = btn("★ 打开无线调试 → 读配对端口 → 生成连接命令", true, v -> startAuto());
        btnAuto.setTextSize(13);
        g2.addView(btnAuto);

        LinearLayout rp = row();
        rp.setPadding(0, dp(8), 0, 0);
        TextView pt = new TextView(this);
        pt.setText("兜底 TCP 端口");
        pt.setTextColor(DIM);
        pt.setTextSize(11);
        pt.setGravity(Gravity.CENTER_VERTICAL);
        rp.addView(pt);
        etTcpPort = new EditText(this);
        etTcpPort.setText("5555");
        etTcpPort.setTextColor(TXT);
        etTcpPort.setTextSize(13);
        etTcpPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etTcpPort.setSingleLine(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(84), dp(42));
        plp.leftMargin = dp(8);
        rp.addView(etTcpPort, plp);
        TextView ph = new TextView(this);
        ph.setText("（root/Shizuku 才会开）");
        ph.setTextColor(DIM);
        ph.setTextSize(10.5f);
        ph.setGravity(Gravity.CENTER_VERTICAL);
        ph.setPadding(dp(6), 0, 0, 0);
        rp.addView(ph);
        g2.addView(rp);
        body.addView(g2);

        // ③ 无线调试状态
        LinearLayout g3 = group("③ 无线调试状态");
        tvWifiState = new TextView(this);
        tvWifiState.setTextColor(MONO);
        tvWifiState.setTextSize(11.5f);
        tvWifiState.setTypeface(Typeface.MONOSPACE);
        tvWifiState.setText("点「刷新状态」读取");
        g3.addView(tvWifiState);

        LinearLayout r3 = row();
        r3.setPadding(0, dp(8), 0, 0);
        r3.addView(btn("刷新状态", false, v -> refreshWifiState()));
        r3.addView(btn("打开无线调试页", false, v -> {
            if (WirelessDebug.openWirelessPage(MainActivity.this)) log("已跳转「无线调试」页面", ACCENT);
            else log("打不开无线调试页，请手动进入 设置 → 系统 → 开发者选项 → 无线调试", ERRC);
        }));
        r3.addView(btn("开配对对话框", false, v -> {
            if (WirelessDebug.openPairingDialog(MainActivity.this))
                log("已跳转，请在页面上点『使用配对码配对设备』", ACCENT);
            else log("无法自动打开，请手动进「无线调试」页点配对按钮", ERRC);
        }));
        g3.addView(r3);
        body.addView(g3);

        // ④ 配对与连接
        LinearLayout g4 = group("④ 配对与连接");
        LinearLayout r4a = row();
        TextView ipt = new TextView(this);
        ipt.setText("设备 IP");
        ipt.setTextColor(DIM);
        ipt.setTextSize(11);
        ipt.setGravity(Gravity.CENTER_VERTICAL);
        r4a.addView(ipt);
        etIp = new EditText(this);
        etIp.setTextColor(TXT);
        etIp.setTextSize(13);
        etIp.setSingleLine(true);
        etIp.setHint("自动获取");
        etIp.setHintTextColor(DIM);
        LinearLayout.LayoutParams iplp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        iplp.leftMargin = dp(8);
        r4a.addView(etIp, iplp);
        etIp.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { deviceIp = s.toString().trim(); updateCommands(); }
        });
        g4.addView(r4a);

        LinearLayout r4b = row();
        r4b.setPadding(0, dp(8), 0, 0);
        TextView ct = new TextView(this);
        ct.setText("6 位配对码");
        ct.setTextColor(DIM);
        ct.setTextSize(11);
        ct.setGravity(Gravity.CENTER_VERTICAL);
        r4b.addView(ct);
        etCode = new EditText(this);
        etCode.setTextColor(TXT);
        etCode.setTextSize(13);
        etCode.setSingleLine(true);
        etCode.setHint("手机页面上的 6 位数字");
        etCode.setHintTextColor(DIM);
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        clp.leftMargin = dp(8);
        r4b.addView(etCode, clp);
        g4.addView(r4b);

        tvPairCmd = new TextView(this);
        tvPairCmd.setTextColor(DIM);
        tvPairCmd.setTextSize(11.5f);
        tvPairCmd.setTypeface(Typeface.MONOSPACE);
        tvPairCmd.setText("配对命令：等待读取配对端口");
        LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pclp.topMargin = dp(10);
        g4.addView(tvPairCmd, pclp);

        tvConnCmd = new TextView(this);
        tvConnCmd.setTextColor(DIM);
        tvConnCmd.setTextSize(11.5f);
        tvConnCmd.setTypeface(Typeface.MONOSPACE);
        tvConnCmd.setText("连接命令：等待读取连接端口");
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cclp.topMargin = dp(4);
        g4.addView(tvConnCmd, cclp);

        LinearLayout r4c = row();
        r4c.setPadding(0, dp(8), 0, 0);
        r4c.addView(btn("复制配对命令", false, v -> copyText(tvPairCmd.getText().toString(), "配对命令")));
        r4c.addView(btn("复制连接命令", false, v -> copyText(tvConnCmd.getText().toString(), "连接命令")));
        g4.addView(r4c);

        LinearLayout r4d = row();
        r4d.setPadding(0, dp(8), 0, 0);
        r4d.addView(btn("复制完整指引", false, v -> copyGuide()));
        r4d.addView(btn("已配对？测连接", false, v -> testConnect()));
        g4.addView(r4d);
        body.addView(g4);

        // ⑤ 辅助
        LinearLayout g5 = group("⑤ 辅助开关");
        LinearLayout r5a = row();
        r5a.addView(btn("开发者选项", false, v -> quick("settings put global development_settings_enabled 1",
                "settings get global development_settings_enabled", "开发者选项")));
        r5a.addView(btn("USB 调试", false, v -> quick("settings put global adb_enabled 1",
                "settings get global adb_enabled", "USB 调试")));
        g5.addView(r5a);

        LinearLayout r5b = row();
        r5b.setPadding(0, dp(8), 0, 0);
        r5b.addView(btn("无线调试开关", false, v -> quick("settings put global adb_wifi_enabled 1",
                "settings get global adb_wifi_enabled", "无线调试开关")));
        r5b.addView(btn("开 Wi-Fi", false, v -> quick("svc wifi enable", "", "Wi-Fi")));
        g5.addView(r5b);

        LinearLayout r5c = row();
        r5c.setPadding(0, dp(8), 0, 0);
        r5c.addView(btn("写 adb 公钥", false, v -> writeKey()));
        r5c.addView(btn("重启 adbd", false, v -> restartAdbd()));
        g5.addView(r5c);
        body.addView(g5);

        // ⑥ 日志
        LinearLayout g6 = group("⑥ 日志");
        tvLog = new TextView(this);
        tvLog.setTextColor(MONO);
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setText("暂无日志");
        g6.addView(tvLog);

        LinearLayout r6 = row();
        r6.setPadding(0, dp(8), 0, 0);
        r6.addView(btn("清空", false, v -> clearLog()));
        r6.addView(btn("复制日志", false, v -> copyText(logBuf.toString(), "日志")));
        g6.addView(r6);
        body.addView(g6);

        // 说明
        TextView foot = new TextView(this);
        foot.setTextColor(DIM);
        foot.setTextSize(11);
        foot.setLineSpacing(dp(2), 1f);
        foot.setText("Android 11+ 的「无线调试」= 系统开关 + TLS 随机端口 + 6 位配对码，和 tcpip 5555 是两套东西。\n"
                + "流程：开开关 → 手机页点『使用配对码配对设备』→ 抄 IP:端口 和 6 位码 → 电脑 adb pair → adb connect。\n"
                + "有 root 时本工具会额外把 5555 开出来，省掉配对。");
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(12);
        body.addView(foot, flp);

        setContentView(sv);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout group(String title) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackgroundColor(PANEL);
        g.setPadding(dp(14), dp(12), dp(14), dp(14));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TXT);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        g.addView(t);
        View spacer = new View(this);
        g.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        g.setLayoutParams(lp);
        return g;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button btn(String text, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11.5f);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setBackgroundColor(primary ? ACCENT : PANEL2);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.rightMargin = dp(7);
        b.setLayoutParams(lp);
        b.setPadding(dp(11), 0, dp(11), 0);
        return b;
    }

    // ------------------------------------------------------------------
    //  日志
    // ------------------------------------------------------------------
    private void log(String text, int color) {
        ui.post(() -> {
            if (logBuf.length() == 0) tvLog.setText("");
            logBuf.append(text).append('\n');
            android.text.SpannableStringBuilder sb =
                    new android.text.SpannableStringBuilder(tvLog.getText());
            int start = sb.length();
            sb.append(text).append('\n');
            sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvLog.setText(sb);
        });
    }

    private void clearLog() {
        ui.post(() -> {
            logBuf.setLength(0);
            tvLog.setText("暂无日志");
        });
    }

    private void copyText(String text, String what) {
        if (text == null || text.trim().isEmpty()) {
            log("没有可复制的" + what, WARNC);
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(what, text));
        log("已复制" + what + "：" + text.replace("\n", " "), OKC);
    }

    private void copyGuide() {
        StringBuilder sb = new StringBuilder();
        sb.append("【无线调试连接指引】\n");
        sb.append("设备 IP：").append(deviceIp.isEmpty() ? "未取到" : deviceIp).append('\n');
        if (pairPort > 0) {
            sb.append("1) 配对：adb pair ").append(deviceIp).append(':').append(pairPort).append('\n');
            sb.append("   配对码填手机上显示的 6 位数字\n");
        } else {
            sb.append("1) 先在手机「无线调试」页点『使用配对码配对设备』，抄下 IP:端口 与 6 位码\n");
        }
        if (connectPort > 0) {
            sb.append("2) 连接：adb connect ").append(deviceIp).append(':').append(connectPort).append('\n');
        } else {
            sb.append("2) 配对成功后，连接端口会出现在同一页面，再执行 adb connect\n");
        }
        int tcp = parsePort();
        if (tcp > 0 && Shell.getMode() != Shell.Mode.LOCAL) {
            sb.append("3) 兜底直连：adb connect ").append(deviceIp).append(':').append(tcp).append('\n');
        }
        copyText(sb.toString(), "完整指引");
    }

    // ------------------------------------------------------------------
    //  动作
    // ------------------------------------------------------------------
    private void bg(Runnable r) {
        new Thread(() -> {
            try { r.run(); } catch (Throwable t) { log("ERR: " + t.getMessage(), ERRC); }
        }).start();
    }

    private int parsePort() {
        try { return Integer.parseInt(etTcpPort.getText().toString().trim()); }
        catch (Throwable t) { return 5555; }
    }

    private void refreshEnv() {
        bg(() -> {
            final Shell.Mode m = Shell.detect();
            final String detail = Shell.getDetail();
            final String info = AutoEngine.deviceInfo(MainActivity.this);
            final String gotIp = AutoEngine.wlanIp(MainActivity.this);
            ui.post(() -> {
                tvPerm.setText("当前权限：" + m.label);
                tvPerm.setTextColor(m == Shell.Mode.LOCAL ? ERRC : OKC);
                tvPermDetail.setText(detail);
                tvInfo.setText(info);
                if (!gotIp.isEmpty()) {
                    deviceIp = gotIp;
                    if (etIp.getText().toString().trim().isEmpty()) etIp.setText(gotIp);
                }
            });
            refreshWifiState();
        });
    }

    private void refreshWifiState() {
        bg(() -> {
            WirelessDebug.State st = AutoEngine.status(MainActivity.this);
            ui.post(() -> {
                pairPort = st.pairPort;
                connectPort = st.connectPort;
                tvWifiState.setText(st.summary());
                tvWifiState.setTextColor("1".equals(st.wifiEnabled) ? OKC : WARNC);
                updateCommands();
            });
        });
    }

    private void updateCommands() {
        String ip = deviceIp.isEmpty() ? etIp.getText().toString().trim() : deviceIp;

        if (pairPort > 0 && !ip.isEmpty()) {
            tvPairCmd.setText("配对：adb pair " + ip + ":" + pairPort + "   （再输手机上的 6 位码）");
            tvPairCmd.setTextColor(OKC);
        } else {
            tvPairCmd.setText("配对命令：未取到配对端口\n→ 手机「无线调试」页点『使用配对码配对设备』后点「刷新状态」");
            tvPairCmd.setTextColor(DIM);
        }

        if (connectPort > 0 && !ip.isEmpty()) {
            tvConnCmd.setText("连接：adb connect " + ip + ":" + connectPort);
            tvConnCmd.setTextColor(OKC);
        } else {
            int tcp = parsePort();
            if (Shell.getMode() != Shell.Mode.LOCAL && !ip.isEmpty()) {
                tvConnCmd.setText("连接：配对完成后用 adb connect " + ip + ":<连接端口>\n兜底可用 adb connect " + ip + ":" + tcp);
            } else {
                tvConnCmd.setText("连接命令：等待配对后系统分配连接端口");
            }
            tvConnCmd.setTextColor(DIM);
        }
    }

    private void startAuto() {
        if (busy) return;
        busy = true;
        btnAuto.setEnabled(false);
        clearLog();
        final int tcp = parsePort();
        log("===== 开始：打开无线调试 =====", ACCENT);
        bg(() -> {
            final AutoEngine.Summary s = AutoEngine.runAll(MainActivity.this, tcp,
                    step -> log("[" + step.index + "] " + step.title + " → " + step.detail,
                            step.ok ? OKC : ERRC));
            ui.post(() -> {
                if (!s.ip.isEmpty()) {
                    deviceIp = s.ip;
                    etIp.setText(s.ip);
                }
                pairPort = s.pairPort;
                connectPort = s.connectPort;
                updateCommands();

                log("===== 流程结束 =====", ACCENT);
                if (s.wirelessOn) log("✔ 无线调试开关已开启", OKC);
                else log("✘ 无线调试开关没写进去（需要 root/Shizuku，或手动去页面开）", ERRC);

                if (s.pairPort > 0) {
                    log("配对：adb pair " + s.ip + ":" + s.pairPort + "  ← 再输手机 6 位码", OKC);
                } else {
                    log("配对端口未出现：请在手机「无线调试」页点『使用配对码配对设备』", WARNC);
                }
                if (s.connectPort > 0) {
                    log("连接：adb connect " + s.ip + ":" + s.connectPort, OKC);
                }
                if (s.port > 0 && (s.root || s.shizuku)) {
                    log("兜底：adb connect " + s.ip + ":" + s.port, OKC);
                }
                busy = false;
                btnAuto.setEnabled(true);
            });
        });
    }

    private void refreshAfter(String what) {
        log(what, ACCENT);
        refreshWifiState();
    }

    private void quick(String putCmd, String getCmd, String name) {
        bg(() -> {
            Shell.run(putCmd, 8000);
            if (getCmd != null && !getCmd.isEmpty()) {
                String v = Shell.run(getCmd, 6000).output.trim();
                boolean ok = "1".equals(v);
                log(name + " → " + v, ok ? OKC : ERRC);
                if (!ok) log("  写入失败：需要 root / Shizuku，或去页面手动打开", WARNC);
            } else {
                log(name + " → 已下发", OKC);
            }
            refreshAfter("");
        });
    }

    private void writeKey() {
        bg(() -> {
            String out = AutoEngine.installPublicKey(MainActivity.this);
            Shell.run("setprop ro.adb.secure 0", 6000);
            boolean ok = !out.contains("Permission denied") && !out.contains("Read-only");
            log("公钥写入：" + out.replace('\n', ' '), ok ? OKC : ERRC);
            if (ok) log("配对/连接不再弹授权框", OKC);
        });
    }

    private void restartAdbd() {
        bg(() -> {
            Shell.run("stop adbd; start adbd", 9000);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            log("adbd 已重启", ACCENT);
            refreshAfter("");
        });
    }

    private void testConnect() {
        final int tcp = parsePort();
        bg(() -> {
            String ip = deviceIp.isEmpty() ? etIp.getText().toString().trim() : deviceIp;
            if (ip.isEmpty()) { log("先取到设备 IP", ERRC); return; }
            // 用本机 loopback 探测端口是否在监听
            boolean tcpUp = probePort(ip, connectPort > 0 ? connectPort : tcp);
            ui.post(() -> {
                if (connectPort > 0) {
                    log("连接端口 " + connectPort + " → " + (tcpUp ? "可连通，直接 adb connect" : "未响应，确认已配对"),
                            tcpUp ? OKC : ERRC);
                } else {
                    log("兜底端口 " + tcp + " → " + (tcpUp ? "可连通" : "未响应"), tcpUp ? OKC : ERRC);
                }
            });
        });
    }

    private boolean probePort(String host, int port) {
        if (port <= 0) return false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2500);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
