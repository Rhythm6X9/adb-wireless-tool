package com.yg.adbtool;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 三级执行通道：root -> Shizuku(shell/adb 权限) -> 应用自身
 */
public final class Shell {

    public enum Mode {
        ROOT("Root 权限"),
        SHIZUKU("Shizuku 权限"),
        LOCAL("普通应用权限");

        public final String label;
        Mode(String l) { this.label = l; }
    }

    private static volatile Mode mode = Mode.LOCAL;
    private static volatile String detail = "";

    private Shell() {}

    public static Mode getMode() { return mode; }

    public static String getDetail() { return detail; }

    public static boolean shizukuAlive() {
        try {
            return rikka.shizuku.Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shizukuGranted() {
        try {
            return rikka.shizuku.Shizuku.pingBinder()
                    && rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestShizuku() {
        try {
            rikka.shizuku.Shizuku.requestPermission(0);
        } catch (Throwable ignored) {
        }
    }

    /** 探测最高可用权限 */
    public static synchronized Mode detect() {
        String r = execLocal(new String[]{"su", "-c", "id"}, 8000);
        if (r != null && r.contains("uid=0")) {
            mode = Mode.ROOT;
            detail = "su 可用，命令以 root 身份执行";
            return mode;
        }
        if (shizukuGranted()) {
            String s = execShizuku("id", 8000);
            if (s != null && s.contains("uid=")) {
                mode = Mode.SHIZUKU;
                detail = "Shizuku 已授权，命令以 " + s.trim() + " 执行";
                return mode;
            }
        }
        mode = Mode.LOCAL;
        detail = shizukuAlive()
                ? "Shizuku 已运行但未授权本应用，请点「请求 Shizuku 授权」"
                : "无 root 也无 Shizuku：只能打开设置页引导，开关写入会被系统拒绝";
        return mode;
    }

    public static Result run(String cmd, long timeoutMs) {
        String wrapped = "(" + cmd + ") 2>&1";
        switch (mode) {
            case ROOT: {
                String o = execLocal(new String[]{"su", "-c", wrapped}, timeoutMs);
                return new Result(o != null, o == null ? "ERR: su 执行失败" : o.trim());
            }
            case SHIZUKU: {
                String o = execShizuku(wrapped, timeoutMs);
                return new Result(o != null, o == null ? "ERR: Shizuku 执行失败（服务可能已停止）" : o.trim());
            }
            default: {
                String o = execLocal(new String[]{"sh", "-c", wrapped}, timeoutMs);
                return new Result(o != null, o == null ? "ERR: 本地 shell 执行失败" : o.trim());
            }
        }
    }

    public static Result run(String cmd) {
        return run(cmd, 20000);
    }

    private static String execLocal(String[] cmd, long timeoutMs) {
        try {
            final Process p = Runtime.getRuntime().exec(cmd);
            final StringBuilder sb = new StringBuilder();
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } catch (Throwable ignored) {}
                }
            });
            Thread te = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } catch (Throwable ignored) {}
                }
            });
            t.start(); te.start();
            t.join(timeoutMs);
            te.join(1500);
            try { p.waitFor(); } catch (Throwable ignored) {}
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String execShizuku(String cmd, long timeoutMs) {
        try {
            final Process p = newShizukuProcess(new String[]{"sh", "-c", cmd});
            final StringBuilder sb = new StringBuilder();
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } catch (Throwable ignored) {}
                }
            });
            Thread te = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } catch (Throwable ignored) {}
                }
            });
            t.start(); te.start();
            t.join(timeoutMs);
            te.join(1500);
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Shizuku 13.x 把 newProcess 变成 private，这里走反射调用 */
    private static Process newShizukuProcess(String[] cmd) throws Exception {
        java.lang.reflect.Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, null, null);
    }

    public static final class Result {
        public final boolean ok;
        public final String output;
        Result(boolean ok, String output) { this.ok = ok; this.output = output; }
    }
}
