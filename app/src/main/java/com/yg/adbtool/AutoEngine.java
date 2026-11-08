package com.yg.adbtool;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoEngine {

    public interface StepListener {
        void onStep(Step s);
    }

    public static final class Step {
        public final int index;
        public final String title;
        public final boolean ok;
        public final String detail;
        public Step(int i, String t, boolean o, String d) {
            index = i; title = t; ok = o; detail = d;
        }
    }

    public static final class Summary {
        public final List<Step> steps;
        public final String ip;
        public final int port;
        public final boolean root;
        public final boolean shizuku;
        public final int sdk;
        public Summary(List<Step> s, String ip, int port, boolean root, boolean shizuku, int sdk) {
            this.steps = s; this.ip = ip; this.port = port;
            this.root = root; this.shizuku = shizuku; this.sdk = sdk;
        }
        public String connectCommand() {
            return ip == null || ip.isEmpty() ? "" : "adb connect " + ip + ":" + port;
        }
    }

    private AutoEngine() {}

    public static String deviceInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("型号      : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("系统      : Android ").append(Build.VERSION.RELEASE)
                .append("  (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("构建类型  : ").append(Build.TYPE).append('\n');
        sb.append("CPU ABI   : ").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("执行权限  : ").append(Shell.getMode().label).append('\n');
        sb.append("权限说明  : ").append(Shell.getDetail()).append('\n');
        String ip = wlanIp(ctx);
        sb.append("WLAN IP   : ").append(ip.isEmpty() ? "未连接" : ip).append('\n');
        return sb.toString();
    }

    private static String join(String[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    public static String wlanIp(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    int a = ipInt & 0xFF;
                    int b = (ipInt >> 8) & 0xFF;
                    int c = (ipInt >> 16) & 0xFF;
                    int d = (ipInt >> 24) & 0xFF;
                    if (c != 0 || d != 0) {
                        return a + "." + b + "." + c + "." + d;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni == null || !ni.isUp()) continue;
                String name = ni.getName();
                if (name == null) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String host = a.getHostAddress();
                        if (host != null && (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth"))) {
                            return host;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        Shell.Result r = Shell.run("ip -f inet addr show wlan0", 8000);
        Matcher m = Pattern.compile("inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(r.output);
        if (m.find()) return m.group(1);
        Shell.Result r2 = Shell.run("getprop dhcp.wlan0.ipaddress", 6000);
        Matcher m2 = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(r2.output);
        if (m2.find()) return m2.group(1);
        return "";
    }

    public static boolean openDevSettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean openWirelessDebugging(Context ctx) {
        String[] fragments = {
                "com.android.settings.development.WirelessDebuggingFragment",
                "com.android.settings.development.DevelopmentSettingsDashboardFragment"
        };
        for (String f : fragments) {
            try {
                Intent i = new Intent();
                i.setClassName("com.android.settings", "com.android.settings.SubSettings");
                i.putExtra(":settings:show_fragment", f);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return openDevSettings(ctx);
    }

    /** 探测 Android 11+ 无线调试端口 */
    public static int detectWirelessPort() {
        Shell.Result r1 = Shell.run("getprop service.adb.tls.port", 6000);
        try {
            int p = Integer.parseInt(r1.output.trim());
            if (p > 0) return p;
        } catch (Throwable ignored) {
        }
        Shell.Result d = Shell.run("dumpsys adb", 12000);
        Matcher m = Pattern.compile("tls[_ ]?port[=: ]+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(d.output);
        if (m.find()) {
            try {
                int p = Integer.parseInt(m.group(1));
                if (p > 0) return p;
            } catch (Throwable ignored) {
            }
        }
        Matcher m2 = Pattern.compile("port[=: ]+(\\d{4,5})", Pattern.CASE_INSENSITIVE).matcher(d.output);
        while (m2.find()) {
            try {
                int p = Integer.parseInt(m2.group(1));
                if (p >= 1024 && p <= 65535 && p != 5037) return p;
            } catch (Throwable ignored) {
            }
        }
        // 从 mdns 里找 _adb-tls-pairing._tcp
        return 0;
    }

    /** 写 adb 公钥到 /data/misc/adb/adb_keys */
    public static String installPublicKey(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "adbkeys");
        if (!dir.exists()) dir.mkdirs();
        File priv = new File(dir, "adbkey");
        File pub = new File(dir, "adbkey.pub");
        if (!priv.exists() || !pub.exists() || priv.length() == 0) {
            try {
                AdbKeys.KeyFiles k = AdbKeys.generate();
                java.io.FileOutputStream fo = new java.io.FileOutputStream(priv);
                fo.write(k.privateDer);
                fo.close();
                java.io.FileOutputStream fo2 = new java.io.FileOutputStream(pub);
                fo2.write(AdbKeys.pubFileContent(k.publicB64).getBytes("UTF-8"));
                fo2.close();
            } catch (Throwable t) {
                return "ERR: 生成密钥失败 " + t.getMessage();
            }
        }
        Shell.run("mkdir -p /data/misc/adb", 6000);
        Shell.Result r = Shell.run(
                "cat '" + pub.getAbsolutePath() + "' >> /data/misc/adb/adb_keys"
                        + "; chmod 640 /data/misc/adb/adb_keys"
                        + "; chown system:shell /data/misc/adb/adb_keys"
                        + "; wc -l /data/misc/adb/adb_keys", 12000);
        return r.output;
    }

    public static Summary runAll(Context ctx, int port, StepListener listener) {
        List<Step> steps = new ArrayList<Step>();
        int[] idx = {0};
        ListenerProxy lp = new ListenerProxy(listener, steps, idx);
        int sdk = Build.VERSION.SDK_INT;

        // 1
        Shell.Mode mode = Shell.detect();
        boolean root = mode == Shell.Mode.ROOT;
        boolean shizuku = mode == Shell.Mode.SHIZUKU;
        lp.emit("权限环境检测", root || shizuku,
                root ? "su 可用，具备 root 全权限"
                        : shizuku ? "Shizuku 已授权，命令以 shell 身份执行"
                        : "无 root 也无 Shizuku：只能打开设置页引导，开关写入会被系统拒绝");

        // 2 开发者选项
        Shell.run("settings put global development_settings_enabled 1", 8000);
        String dev = Shell.run("settings get global development_settings_enabled", 6000).output.trim();
        lp.emit("开启开发者选项", "1".equals(dev),
                "1".equals(dev) ? "development_settings_enabled=1"
                        : "写入失败，需要 root 或 Shizuku；也可在设置里手动打开一次开发者选项");

        // 3 USB 调试
        Shell.run("settings put global adb_enabled 1", 8000);
        String adb = Shell.run("settings get global adb_enabled", 6000).output.trim();
        lp.emit("开启 USB 调试", "1".equals(adb),
                "1".equals(adb) ? "adb_enabled=1" : "写入失败，需要 root 或 Shizuku 权限");

        // 4 Wi-Fi + IP
        Shell.run("svc wifi enable", 6000);
        String ip = wlanIp(ctx);
        if (ip.isEmpty()) {
            try { Thread.sleep(2500); } catch (Throwable ignored) {}
            ip = wlanIp(ctx);
        }
        final String finalIp = ip;
        lp.emit("读取局域网 IP", !ip.isEmpty(),
                !ip.isEmpty() ? "设备 WLAN IP = " + ip : "设备未连 Wi-Fi，或路由器做了 AP 隔离导致取不到地址");

        // 5 传统 5555
        if (root || shizuku) {
            Shell.run("setprop service.adb.tcp.port " + port, 6000);
            Shell.run("stop adbd; start adbd", 9000);
            try { Thread.sleep(2000); } catch (Throwable ignored) {}
            String p = Shell.run("getprop service.adb.tcp.port", 6000).output.trim();
            boolean ok = String.valueOf(port).equals(p);
            lp.emit("开启 TCP 调试端口 " + port, ok,
                    ok ? "service.adb.tcp.port=" + p + "，adbd 已重启监听 " + finalIp + ":" + port
                            : "设置未生效（读回 '" + p + "'）；部分 ROM 需要 persist.adb.tcp.port 或用无线调试配对");
        } else {
            lp.emit("开启 TCP 调试端口 " + port, false,
                    "需要 root：非 root 无法修改 service.adb.tcp.port 并重启 adbd");
        }

        // 6 Android 11+ 无线调试
        if (sdk >= 30) {
            Shell.run("settings put global adb_wifi_enabled 1", 8000);
            try { Thread.sleep(1200); } catch (Throwable ignored) {}
            String wifi = Shell.run("settings get global adb_wifi_enabled", 6000).output.trim();
            int wp = detectWirelessPort();
            StringBuilder sb = new StringBuilder();
            sb.append("1".equals(wifi) ? "adb_wifi_enabled=1" : "adb_wifi_enabled 写入失败");
            if (wp > 0) sb.append("；检测到端口 ").append(wp).append("（TLS 模式，需配对码）");
            else sb.append("；未取到 TLS 端口，需在「无线调试」页点「使用配对码配对设备」");
            if (root) sb.append("；root 已同时开好 ").append(port).append("，可直接 adb connect");
            lp.emit("Android 11+ 无线调试", "1".equals(wifi) || wp > 0, sb.toString());
        } else {
            lp.emit("Android 11+ 无线调试", true,
                    "系统为 Android " + Build.VERSION.RELEASE + "，不支持 TLS 无线调试，用传统 " + port + " 端口即可");
        }

        // 7 公钥
        if (root) {
            String out = installPublicKey(ctx);
            Shell.run("setprop ro.adb.secure 0", 6000);
            lp.emit("写入 adb 授权公钥", !out.contains("Permission denied") && !out.contains("Read-only"),
                    "公钥写入 /data/misc/adb/adb_keys：" + out.replace('\n', ' ')
                            + "；同时把 ro.adb.secure 置 0，之后 adb connect 不再弹「允许调试」");
        } else {
            lp.emit("写入 adb 授权公钥", false,
                    "需要 root 才能写 /data/misc/adb/adb_keys；密钥已在应用私有目录生成，可导出给电脑用");
        }

        return new Summary(steps, ip, port, root, shizuku, sdk);
    }

    private static final class ListenerProxy {
        private final StepListener l;
        private final List<Step> steps;
        private final int[] idx;
        ListenerProxy(StepListener l, List<Step> s, int[] i) { this.l = l; this.steps = s; this.idx = i; }
        void emit(String title, boolean ok, String detail) {
            idx[0]++;
            Step s = new Step(idx[0], title, ok, detail);
            steps.add(s);
            if (l != null) l.onStep(s);
        }
    }
}
