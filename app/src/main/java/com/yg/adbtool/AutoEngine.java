package com.yg.adbtool;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
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
        public final int pairPort;
        public final int connectPort;
        public final boolean root;
        public final boolean shizuku;
        public final int sdk;
        public final boolean wirelessOn;

        public Summary(List<Step> s, String ip, int port, int pairPort, int connectPort,
                       boolean root, boolean shizuku, int sdk, boolean wirelessOn) {
            this.steps = s; this.ip = ip; this.port = port;
            this.pairPort = pairPort; this.connectPort = connectPort;
            this.root = root; this.shizuku = shizuku; this.sdk = sdk;
            this.wirelessOn = wirelessOn;
        }

        /** Android 11+ 无线调试的直连命令（端口是系统随机分配的） */
        public String wirelessCommand() {
            if (ip == null || ip.isEmpty()) return "";
            if (connectPort > 0) return "adb connect " + ip + ":" + connectPort;
            return "";
        }

        /** 传统 tcpip 端口命令 */
        public String tcpCommand() {
            if (ip == null || ip.isEmpty()) return "";
            return "adb connect " + ip + ":" + port;
        }

        /** 配对命令（需要手机页面显示的 6 位码） */
        public String pairCommand() {
            if (ip == null || ip.isEmpty() || pairPort <= 0) return "";
            return "adb pair " + ip + ":" + pairPort;
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
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    int a = ipInt & 0xFF;
                    int b = (ipInt >> 8) & 0xFF;
                    int c = (ipInt >> 16) & 0xFF;
                    int d = (ipInt >> 24) & 0xFF;
                    if (c != 0 || d != 0) return a + "." + b + "." + c + "." + d;
                }
            }
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
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

    // ==================================================================
    //  核心：以「无线调试」为目标的完整流程
    // ==================================================================
    public static Summary runAll(final Context ctx, final int tcpPort, final StepListener listener) {
        final List<Step> steps = new ArrayList<Step>();
        final int[] idx = {0};

        final WirelessDebug.Step ws = new WirelessDebug.Step() {
            @Override public void on(String title, boolean ok, String detail) {
                idx[0]++;
                Step s = new Step(idx[0], title, ok, detail);
                steps.add(s);
                if (listener != null) listener.onStep(s);
            }
        };

        Shell.Mode mode = Shell.detect();
        boolean root = mode == Shell.Mode.ROOT;
        boolean shizuku = mode == Shell.Mode.SHIZUKU;
        int sdk = Build.VERSION.SDK_INT;

        // ① 权限
        ws.on("权限环境检测", root || shizuku,
                root ? "su 可用，具备 root 全权限"
                        : shizuku ? "Shizuku 已授权，命令以 shell 身份执行"
                        : "无 root / Shizuku：只能引导你手动打开无线调试开关");

        // ② 打开无线调试（核心目标）
        WirelessDebug.enable(ctx, ws);

        // ③ 读 IP
        String ip = wlanIp(ctx);
        ws.on("读取局域网 IP", !ip.isEmpty(),
                !ip.isEmpty() ? "设备 WLAN IP = " + ip : "未连 Wi-Fi，无线调试无法工作");

        // ④ 传统 tcpip 兜底（root/Shizuku 才有）
        WirelessDebug.enableTcpFallback(tcpPort, ws);

        // ⑤ 探测无线调试端口
        WirelessDebug.State st = WirelessDebug.probe(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("配对端口 ").append(st.pairPort > 0 ? st.pairPort : "未监听");
        sb.append(" / 连接端口 ").append(st.connectPort > 0 ? st.connectPort : "未监听");
        sb.append(" / TLS 端口 ").append(st.tlsPort.isEmpty() ? "无" : st.tlsPort);
        ws.on("探测无线调试端口",
                st.pairPort > 0 || st.connectPort > 0 || !st.tlsPort.isEmpty(),
                sb.toString());

        // ⑥ mDNS 发现路径
        ws.on("mDNS 服务发现", st.mdnsAlive,
                st.mdnsAlive ? "系统 mDNS 在跑，Android Studio / adb 可自动发现设备"
                        : "mDNS 未见活动，只能用手动端口连接");

        // ⑦ 配对提示
        if (sdk >= 30) {
            boolean canPair = st.pairPort > 0;
            ws.on("无线调试配对状态", canPair,
                    canPair
                            ? "配对端口已监听：" + ip + ":" + st.pairPort + "，在手机页面抄 6 位码后执行 adb pair"
                            : "还没进入配对模式：在手机「无线调试」页点『使用配对码配对设备』，页面会显示 IP:端口 和 6 位码");
        } else {
            ws.on("无线调试配对状态", true,
                    "Android " + Build.VERSION.RELEASE + " 不需要配对，tcpip " + tcpPort + " 直接连");
        }

        // ⑧ 公钥（配对后免确认）
        if (root) {
            String out = installPublicKey(ctx);
            Shell.run("setprop ro.adb.secure 0", 6000);
            ws.on("写入 adb 授权公钥", !out.contains("Permission denied") && !out.contains("Read-only"),
                    "公钥写入 /data/misc/adb/adb_keys：" + out.replace('\n', ' ')
                            + "；再把 ro.adb.secure 置 0，配对/连接都不再弹授权框");
        } else {
            ws.on("写入 adb 授权公钥", false,
                    "需要 root 才能写 /data/misc/adb/adb_keys；密钥已在应用私有目录生成，可导出给电脑");
        }

        boolean wirelessOn = "1".equals(st.wifiEnabled);
        return new Summary(steps, ip, tcpPort, st.pairPort, st.connectPort,
                root, shizuku, sdk, wirelessOn);
    }

    /** 只做无线调试状态检查（不做写入） */
    public static WirelessDebug.State status(Context ctx) {
        Shell.detect();
        return WirelessDebug.probe(ctx);
    }
}
