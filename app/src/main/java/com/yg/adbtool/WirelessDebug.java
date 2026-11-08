package com.yg.adbtool;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无线调试（Wireless Debugging）专用引擎。
 *
 * 关键认知：Android 11+ 的「无线调试」不是 tcpip 5555，而是
 *   1) 系统设置里有独立开关 Settings.Global.ADB_WIFI_ENABLED
 *   2) 打开后 adbd 起 mDNS 服务：_adb-tls-pairing._tcp（配对）/_adb-tls-connect._tcp（连接）
 *   3) 连接走 TLS，端口随机，必须先用 6 位配对码完成配对
 *
 * 因此本类做三件事：
 *   A. 真正把无线调试开关置 1，并确认 adbd 侧起效
 *   B. 从设备侧挖出配对端口 / 连接端口（dumpsys + getprop + mdns + netstat 多路取证）
 *   C. 有 root / Shizuku 时，直接把 5555 也开出来做兜底，同时把配对端口从 TLS 里解出来
 */
public final class WirelessDebug {

    public static final String SETTING_WIFI = "adb_wifi_enabled";
    public static final String SETTING_TCP = "adb_tcp_port";

    public interface Step {
        void on(String title, boolean ok, String detail);
    }

    /** 无线调试状态全景 */
    public static final class State {
        public boolean supported;          // 是否 Android 11+
        public String wifiEnabled = "0";   // adb_wifi_enabled
        public String tlsPort = "";        // service.adb.tls.port
        public String tcpPort = "";        // service.adb.tcp.port
        public int pairPort = 0;           // _adb-tls-pairing 端口
        public int connectPort = 0;        // _adb-tls-connect 端口
        public boolean mdnsAlive;          // mdns 服务是否在跑
        public int adbdPid = 0;
        public String raw = "";
        public List<String> evidence = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("系统支持无线调试 : ").append(supported ? "是（Android 11+ TLS 模式）" : "否（Android 10- 走 tcpip）").append('\n');
            sb.append("无线调试开关     : ").append("1".equals(wifiEnabled) ? "已开启" : "未开启").append("  (adb_wifi_enabled=").append(wifiEnabled).append(")\n");
            sb.append("TLS 端口         : ").append(tlsPort.isEmpty() ? "未分配" : tlsPort).append('\n');
            sb.append("传统 TCP 端口    : ").append(tcpPort.isEmpty() ? "未开启" : tcpPort).append('\n');
            sb.append("配对端口         : ").append(pairPort > 0 ? String.valueOf(pairPort) : "未监听（需点「使用配对码配对设备」）").append('\n');
            sb.append("连接端口         : ").append(connectPort > 0 ? String.valueOf(connectPort) : "未监听（配对后自动出现）").append('\n');
            sb.append("mDNS 发现        : ").append(mdnsAlive ? "运行中" : "未运行").append('\n');
            sb.append("adbd PID         : ").append(adbdPid > 0 ? adbdPid : "未取到").append('\n');
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    //  A. 开无线调试
    // ------------------------------------------------------------------

    /**
     * 真正打开无线调试。分四步，每步都验证：
     *   1) 写 Settings.Global.ADB_WIFI_ENABLED = 1
     *   2) 确保 developer options 开着（无线调试是它的子项）
     *   3) 确保 Wi-Fi 开着（无线调试必须有 wlan 接口）
     *   4) 若写不进去，用 root/Shizuku 兜底重启 adbd 让系统重读
     */
    public static void enable(Context ctx, Step step) {
        boolean newApi = Build.VERSION.SDK_INT >= 30;

        // 1) 无线调试开关
        Shell.Result w = Shell.run("settings put global " + SETTING_WIFI + " 1", 8000);
        String wifi = Shell.run("settings get global " + SETTING_WIFI, 6000).output.trim();
        step.on("写入无线调试开关",
                "1".equals(wifi),
                "settings put global adb_wifi_enabled 1 → 读回 '" + wifi + "'"
                        + (w.output.isEmpty() ? "" : "；命令输出: " + w.output));

        // 2) 开发者选项（无线调试挂在它下面，父开关关着子开关不生效）
        Shell.run("settings put global development_settings_enabled 1", 8000);
        String dev = Shell.run("settings get global development_settings_enabled", 6000).output.trim();
        step.on("确认开发者选项已开",
                "1".equals(dev),
                "development_settings_enabled=" + dev);

        // 3) Wi-Fi 必须在线
        Shell.run("svc wifi enable", 6000);
        String ip = AutoEngine.wlanIp(ctx);
        if (ip.isEmpty()) {
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            ip = AutoEngine.wlanIp(ctx);
        }
        step.on("确认 Wi-Fi 在线",
                !ip.isEmpty(),
                ip.isEmpty() ? "wlan0 没有 IPv4 地址：无线调试必须有 Wi-Fi 连接" : "wlan0 IP = " + ip);

        // 4) Android 11+ 专项：adbd 侧的 tls 服务
        if (newApi) {
            Shell.run("settings put global adb_enabled 1", 8000);

            // 有些 ROM 只有在 Settings 里翻过页面才会启动 mdns，这里触发一次无线调试页
            if (!"1".equals(wifi)) {
                openWirelessPage(ctx);
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            }

            String tls = Shell.run("getprop service.adb.tls.port", 6000).output.trim();
            boolean tlsOk = tls.matches("\\d+") && Integer.parseInt(tls) > 0;
            if (!tlsOk) {
                // root/Shizuku 兜底：重启 adbd 让系统起 tls server
                Shell.run("setprop persist.adb.tls_server.enable 1", 6000);
                Shell.run("stop adbd; start adbd", 9000);
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                tls = Shell.run("getprop service.adb.tls.port", 6000).output.trim();
                tlsOk = tls.matches("\\d+") && Integer.parseInt(tls) > 0;
            }
            step.on("启动 adbd 的 TLS 服务",
                    tlsOk,
                    tlsOk ? "service.adb.tls.port = " + tls + "（TLS 连接端口已就绪）"
                            : "未取到 TLS 端口：多数 ROM 需要你在「无线调试」页面手动打开总开关，系统才起 tls server");
        } else {
            step.on("启动 adbd 的 TLS 服务", true, "Android " + Build.VERSION.RELEASE + " 无 TLS 无线调试，跳过");
        }
    }

    // ------------------------------------------------------------------
    //  B. 端口探测（多路取证）
    // ------------------------------------------------------------------
    public static State probe(Context ctx) {
        State st = new State();
        st.supported = Build.VERSION.SDK_INT >= 30;
        st.wifiEnabled = Shell.run("settings get global " + SETTING_WIFI, 6000).output.trim();
        st.tlsPort = Shell.run("getprop service.adb.tls.port", 6000).output.trim();
        st.tcpPort = Shell.run("getprop service.adb.tcp.port", 6000).output.trim();

        String pid = Shell.run("pidof adbd", 6000).output.trim();
        if (pid.matches("\\d+")) st.adbdPid = Integer.parseInt(pid);
        else {
            String ps = Shell.run("ps -A | grep adbd", 8000).output;
            Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(ps);
            if (m.find()) {
                try { st.adbdPid = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }

        // 证据 1：mdns 服务表
        String mdns = Shell.run("dumpsys mdns", 10000).output;
        String mdns2 = Shell.run("cat /data/misc/adb/adb_wifi_paired_devices 2>/dev/null", 6000).output;
        String dumpsys = Shell.run("dumpsys adb", 12000).output;
        st.raw = dumpsys;

        int[] p1 = scanMdns(dumpsys);
        if (p1[0] > 0 || p1[1] > 0) {
            st.pairPort = p1[0];
            st.connectPort = p1[1];
            st.evidence.add("dumpsys adb 中提取到端口");
        }

        // 证据 2：mdns 命令
        if (st.pairPort == 0 || st.connectPort == 0) {
            String m = Shell.run("dumpsys mdns", 10000).output;
            int[] p2 = scanMdns(m);
            if (p2[0] > 0) st.pairPort = p2[0];
            if (p2[1] > 0) st.connectPort = p2[1];
        }

        // 证据 3：netstat 看谁在监听（root/Shizuku 才行）
        if (st.pairPort == 0 || st.connectPort == 0) {
            String net = Shell.run("netstat -tlnp 2>/dev/null | grep adbd", 10000).output;
            if (net.isEmpty()) net = Shell.run("cat /proc/net/tcp | head -40", 10000).output;
            Matcher m = Pattern.compile(":([0-9A-Fa-f]{4})\\s").matcher(net);
            List<Integer> ports = new ArrayList<>();
            while (m.find()) {
                try {
                    int p = Integer.parseInt(m.group(1), 16);
                    if (p > 1024 && p != 5037) ports.add(p);
                } catch (NumberFormatException ignored) {}
            }
            if (!ports.isEmpty()) {
                if (st.connectPort == 0) st.connectPort = ports.get(0);
                st.evidence.add("netstat 监听端口: " + ports);
            }
        }

        st.mdnsAlive = dumpsys.contains("mdns") || !mdns.isEmpty();

        if (st.evidence.isEmpty()) st.evidence.add("未从系统侧取到端口，需在无线调试页点「使用配对码配对设备」后重试");
        return st;
    }

    /** 从任意文本里挖 _adb-tls-pairing / _adb-tls-connect / _adb._tcp 端口 */
    private static int[] scanMdns(String text) {
        int pair = 0, conn = 0;
        if (text == null) return new int[]{0, 0};

        Matcher mp = Pattern.compile("_adb-tls-pairing\\._tcp[^\\d]{0,80}?(\\d{4,5})").matcher(text);
        if (mp.find()) {
            try { pair = Integer.parseInt(mp.group(1)); } catch (NumberFormatException ignored) {}
        }
        Matcher mc = Pattern.compile("_adb-tls-connect\\._tcp[^\\d]{0,80}?(\\d{4,5})").matcher(text);
        if (mc.find()) {
            try { conn = Integer.parseInt(mc.group(1)); } catch (NumberFormatException ignored) {}
        }
        if (pair == 0) {
            Matcher m = Pattern.compile("pairing[_ ]?port[=: ]+(\\d{4,5})", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) {
                try { pair = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        if (conn == 0) {
            Matcher m = Pattern.compile("(?:tls[_ ]?port|connect[_ ]?port)[=: ]+(\\d{4,5})", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) {
                try { conn = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return new int[]{pair, conn};
    }

    // ------------------------------------------------------------------
    //  C. 打开无线调试页面（只能人工点开关的 ROM 靠这个）
    // ------------------------------------------------------------------
    public static boolean openWirelessPage(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) {
            return AutoEngine.openDevSettings(ctx);
        }
        String[][] targets = {
                {"com.android.settings", "com.android.settings.Settings$WirelessDebuggingActivity"},
                {"com.android.settings", "com.android.settings.development.WirelessDebuggingActivity"},
                {"com.android.settings", "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"},
        };
        for (String[] t : targets) {
            try {
                Intent i = new Intent();
                i.setClassName(t[0], t[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {}
        }
        // SubSettings + fragment 兜底
        for (String frag : new String[]{
                "com.android.settings.development.WirelessDebuggingFragment",
                "com.android.settings.development.wifi.WirelessDebuggingFragment"}) {
            try {
                Intent i = new Intent();
                i.setClassName("com.android.settings", "com.android.settings.SubSettings");
                i.putExtra(":settings:show_fragment", frag);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {}
        }
        return AutoEngine.openDevSettings(ctx);
    }

    // ------------------------------------------------------------------
    //  D. 打开「使用配对码配对设备」对话框
    // ------------------------------------------------------------------
    public static boolean openPairingDialog(Context ctx) {
        // Android 11+ 无线调试页里那个按钮，无法用 Intent 直接调起，
        // 只能把页面打开让用户点；部分 ROM 支持这个 action
        String[] actions = {
                "android.settings.ADB_WIFI_SETTINGS",
                "com.android.settings.ADB_WIFI_SETTINGS",
        };
        for (String a : actions) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {}
        }
        return openWirelessPage(ctx);
    }

    /**
     * 把 5555 也开出来做双保险：无线调试(TLS) + 传统 tcpip 并存。
     * 只有 root / Shizuku 才能改 service.adb.tcp.port。
     */
    public static boolean enableTcpFallback(int port, Step step) {
        if (Shell.getMode() == Shell.Mode.LOCAL) {
            step.on("传统 tcpip 兜底端口", false,
                    "无 root / Shizuku：保持用系统「无线调试」的 TLS 配对流程");
            return false;
        }
        Shell.run("setprop service.adb.tcp.port " + port, 6000);
        Shell.run("setprop persist.adb.tcp.port " + port, 6000);
        Shell.run("stop adbd; start adbd", 9000);
        try { Thread.sleep(2200); } catch (InterruptedException ignored) {}
        String v = Shell.run("getprop service.adb.tcp.port", 6000).output.trim();
        boolean ok = String.valueOf(port).equals(v);
        step.on("传统 tcpip 兜底端口", ok,
                ok ? "service.adb.tcp.port=" + port + "（无线调试之外多一条直连通道）"
                        : "写入未生效，读回 '" + v + "'");
        return ok;
    }
}
