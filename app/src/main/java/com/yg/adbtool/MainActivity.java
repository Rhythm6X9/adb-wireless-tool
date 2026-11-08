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
 * 单页原生界面：不依赖 compose / androidx，直接用 SDK 控件，APK 只有几十 KB。
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
    private TextView tvCmd;
    private TextView tvLog;
    private EditText etPort;
    private EditText etIp;
    private Button btnAuto;
    private final StringBuilder logBuf = new StringBuilder();
    private volatile boolean busy = false;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                    log(ok ? "Shizuku 已授权" : "Shizuku 授权被拒绝", ok ? OKC : ERRC);
                    refreshEnv();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        refreshEnv();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshEnv();
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
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
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(PANEL);
        top.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("USB 无线调试工具");
        title.setTextColor(TXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title);

        tvPerm = new TextView(this);
        tvPerm.setTextColor(TXT);
        tvPerm.setTextSize(13);
        tvPerm.setText("当前权限：检测中…");
        tvPerm.setPadding(0, dp(6), 0, 0);
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
        r1.addView(btn("刷新环境", true, new View.OnClickListener() {
            @Override public void onClick(View v) { refreshEnv(); }
        }));
        r1.addView(btn("请求 Shizuku 授权", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Shell.shizukuAlive()) {
                    log("Shizuku 未运行：请先安装并启动 Shizuku（可用无线调试方式启动），再回本页授权", ERRC);
                } else {
                    Shell.requestShizuku();
                    log("已发起 Shizuku 授权请求，请在弹窗中允许", ACCENT);
                }
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
        LinearLayout g2 = group("② 一键全自动");
        btnAuto = btn("★ 一键：开发者模式 → USB调试 → 无线调试", true, new View.OnClickListener() {
            @Override public void onClick(View v) { startAuto(); }
        });
        g2.addView(btnAuto);

        LinearLayout rp = row();
        rp.setPadding(0, dp(8), 0, 0);
        TextView pt = new TextView(this);
        pt.setText("TCP 端口");
        pt.setTextColor(DIM);
        pt.setTextSize(12);
        pt.setGravity(Gravity.CENTER_VERTICAL);
        rp.addView(pt);
        etPort = new EditText(this);
        etPort.setText("5555");
        etPort.setTextColor(TXT);
        etPort.setTextSize(13);
        etPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPort.setSingleLine(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(96), dp(46));
        plp.leftMargin = dp(8);
        rp.addView(etPort, plp);
        g2.addView(rp);
        body.addView(g2);

        // ③ 单项
        LinearLayout g3 = group("③ 单项操作");
        LinearLayout r3a = row();
        r3a.addView(btn("开开发者选项", false, new View.OnClickListener() {
            @Override public void onClick(View v) { setDev(); }
        }));
        r3a.addView(btn("开 USB 调试", false, new View.OnClickListener() {
            @Override public void onClick(View v) { setAdb(); }
        }));
        g3.addView(r3a);

        LinearLayout r3b = row();
        r3b.setPadding(0, dp(8), 0, 0);
        r3b.addView(btn("只开 TCP 端口", false, new View.OnClickListener() {
            @Override public void onClick(View v) { onlyTcp(); }
        }));
        r3b.addView(btn("写 adb 公钥", false, new View.OnClickListener() {
            @Override public void onClick(View v) { writeKey(); }
        }));
        g3.addView(r3b);

        LinearLayout r3c = row();
        r3c.setPadding(0, dp(8), 0, 0);
        r3c.addView(btn("打开开发者选项页", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (AutoEngine.openDevSettings(MainActivity.this)) log("已跳转开发者选项", ACCENT);
                else log("无法打开开发者选项页", ERRC);
            }
        }));
        r3c.addView(btn("打开无线调试页", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (AutoEngine.openWirelessDebugging(MainActivity.this)) log("已跳转无线调试页", ACCENT);
                else log("无法打开无线调试页", ERRC);
            }
        }));
        g3.addView(r3c);
        body.addView(g3);

        // ④ 无线信息
        LinearLayout g4 = group("④ 无线连接信息");
        LinearLayout r4 = row();
        TextView ipt = new TextView(this);
        ipt.setText("设备 IP");
        ipt.setTextColor(DIM);
        ipt.setTextSize(12);
        ipt.setGravity(Gravity.CENTER_VERTICAL);
        r4.addView(ipt);
        etIp = new EditText(this);
        etIp.setTextColor(TXT);
        etIp.setTextSize(13);
        etIp.setSingleLine(true);
        etIp.setHint("如 192.168.1.23");
        etIp.setHintTextColor(DIM);
        LinearLayout.LayoutParams iplp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        iplp.leftMargin = dp(8);
        r4.addView(etIp, iplp);
        g4.addView(r4);

        tvCmd = new TextView(this);
        tvCmd.setTextColor(DIM);
        tvCmd.setTextSize(12);
        tvCmd.setTypeface(Typeface.MONOSPACE);
        tvCmd.setText("填好 IP 后这里显示电脑端命令");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        g4.addView(tvCmd, clp);

        LinearLayout r4b = row();
        r4b.setPadding(0, dp(8), 0, 0);
        r4b.addView(btn("复制命令", false, new View.OnClickListener() {
            @Override public void onClick(View v) { copyCmd(); }
        }));
        r4b.addView(btn("读取 TLS 端口", false, new View.OnClickListener() {
            @Override public void onClick(View v) { readTls(); }
        }));
        g4.addView(r4b);
        body.addView(g4);

        // ⑤ 日志
        LinearLayout g5 = group("⑤ 日志");
        tvLog = new TextView(this);
        tvLog.setTextColor(MONO);
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setText("暂无日志");
        g5.addView(tvLog);

        LinearLayout r5 = row();
        r5.setPadding(0, dp(8), 0, 0);
        r5.addView(btn("清空日志", false, new View.OnClickListener() {
            @Override public void onClick(View v) { clearLog(); }
        }));
        r5.addView(btn("复制日志", false, new View.OnClickListener() {
            @Override public void onClick(View v) { copyLog(); }
        }));
        g5.addView(r5);
        body.addView(g5);

        // 末尾说明
        TextView foot = new TextView(this);
        foot.setTextColor(DIM);
        foot.setTextSize(11);
        foot.setLineSpacing(dp(2), 1f);
        foot.setText("说明：开发者选项与 USB 调试两个开关写入需要 root 或 Shizuku（shell 权限）。\n"
                + "Android 11+ 的「无线调试」为 TLS + 配对码模式，可用 root 直接开 5555 传统端口免配对；\n"
                + "拿不到 IP 时把手机与电脑连同一个 Wi-Fi 再刷新。");
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
        g.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
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
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(TXT);
        b.setBackgroundColor(primary ? ACCENT : PANEL2);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    // ------------------------------------------------------------------
    //  日志
    // ------------------------------------------------------------------
    private void log(String text, int color) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (logBuf.length() == 0) {
                    tvLog.setText("");
                }
                logBuf.append(text).append('\n');
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(tvLog.getText());
                int start = sb.length();
                sb.append(text).append('\n');
                sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                        start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvLog.setText(sb);
            }
        });
    }

    private void clearLog() {
        ui.post(new Runnable() {
            @Override public void run() {
                logBuf.setLength(0);
                tvLog.setText("暂无日志");
            }
        });
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("log", logBuf.toString()));
        log("日志已复制", OKC);
    }

    private void copyCmd() {
        String c = "adb connect " + ip() + ":" + port();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("adb", c));
        log("已复制：" + c, OKC);
    }

    private int port() {
        try {
            return Integer.parseInt(etPort.getText().toString().trim());
        } catch (Throwable t) {
            return 5555;
        }
    }

    private String ip() {
        return etIp.getText().toString().trim();
    }

    // ------------------------------------------------------------------
    //  动作
    // ------------------------------------------------------------------
    private void bg(final Runnable r) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { r.run(); } catch (Throwable t) { log("ERR: " + t.getMessage(), ERRC); }
            }
        }).start();
    }

    private void refreshEnv() {
        bg(new Runnable() {
            @Override public void run() {
                final Shell.Mode m = Shell.detect();
                final String detail = Shell.getDetail();
                final String info = AutoEngine.deviceInfo(MainActivity.this);
                final String gotIp = AutoEngine.wlanIp(MainActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        tvPerm.setText("当前权限：" + m.label);
                        tvPerm.setTextColor(m == Shell.Mode.LOCAL ? ERRC : OKC);
                        tvPermDetail.setText(detail);
                        tvInfo.setText(info);
                        if (!gotIp.isEmpty() && ip().isEmpty()) {
                            etIp.setText(gotIp);
                        }
                        updateCmd();
                    }
                });
            }
        });
    }

    private void updateCmd() {
        String ipv = ip();
        tvCmd.setText(ipv.isEmpty() ? "填好 IP 后这里显示电脑端命令" : "adb connect " + ipv + ":" + port());
        tvCmd.setTextColor(ipv.isEmpty() ? DIM : OKC);
    }

    private void startAuto() {
        if (busy) return;
        busy = true;
        btnAuto.setEnabled(false);
        clearLog();
        log("开始全自动流程", ACCENT);
        final int p = port();
        bg(new Runnable() {
            @Override public void run() {
                final AutoEngine.Summary s = AutoEngine.runAll(MainActivity.this, p,
                        new AutoEngine.StepListener() {
                            @Override public void onStep(AutoEngine.Step step) {
                                log("[" + step.index + "] " + step.title + " → " + step.detail,
                                        step.ok ? OKC : ERRC);
                            }
                        });
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!s.ip.isEmpty()) etIp.setText(s.ip);
                        updateCmd();
                        log("———— 流程结束 ————", ACCENT);
                        if (!s.connectCommand().isEmpty()) {
                            log("电脑端执行：" + s.connectCommand(), OKC);
                        }
                        busy = false;
                        btnAuto.setEnabled(true);
                    }
                });
            }
        });
    }

    private void setDev() {
        bg(new Runnable() {
            @Override public void run() {
                Shell.run("settings put global development_settings_enabled 1", 8000);
                String v = Shell.run("settings get global development_settings_enabled", 6000).output.trim();
                log("development_settings_enabled=" + v, "1".equals(v) ? OKC : ERRC);
                if (!"1".equals(v)) log("提示：需要 root / Shizuku，或在设置里手动打开一次", WARNC);
            }
        });
    }

    private void setAdb() {
        bg(new Runnable() {
            @Override public void run() {
                Shell.run("settings put global adb_enabled 1", 8000);
                String v = Shell.run("settings get global adb_enabled", 6000).output.trim();
                log("adb_enabled=" + v, "1".equals(v) ? OKC : ERRC);
            }
        });
    }

    private void onlyTcp() {
        final int p = port();
        bg(new Runnable() {
            @Override public void run() {
                Shell.run("setprop service.adb.tcp.port " + p, 6000);
                Shell.run("stop adbd; start adbd", 9000);
                try { Thread.sleep(1800); } catch (Throwable ignored) {}
                String v = Shell.run("getprop service.adb.tcp.port", 6000).output.trim();
                log("service.adb.tcp.port=" + v, String.valueOf(p).equals(v) ? OKC : ERRC);
                final String gotIp = AutoEngine.wlanIp(MainActivity.this);
                if (!gotIp.isEmpty()) {
                    ui.post(new Runnable() {
                        @Override public void run() { etIp.setText(gotIp); updateCmd(); }
                    });
                    log("电脑端执行：adb connect " + gotIp + ":" + p, OKC);
                }
            }
        });
    }

    private void writeKey() {
        bg(new Runnable() {
            @Override public void run() {
                String out = AutoEngine.installPublicKey(MainActivity.this);
                Shell.run("setprop ro.adb.secure 0", 6000);
                boolean ok = !out.contains("Permission denied") && !out.contains("Read-only");
                log("公钥写入结果：" + out.replace('\n', ' '), ok ? OKC : ERRC);
                if (ok) log("/data/misc/adb/adb_keys 已更新，之后 adb connect 不再弹授权框", OKC);
            }
        });
    }

    private void readTls() {
        bg(new Runnable() {
            @Override public void run() {
                int p = AutoEngine.detectWirelessPort();
                if (p > 0) {
                    log("检测到无线调试端口：" + p + "（Android 11+ 需配对码）", OKC);
                } else {
                    log("未取到端口：请打开「无线调试」页并点「使用配对码配对设备」", ERRC);
                }
            }
        });
    }
}
