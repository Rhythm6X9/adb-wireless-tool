# 无线调试工具 · Wireless Debugging Tool

真正打开 Android 的**「无线调试」**（不是 `adb tcpip 5555`），并读出配对端口与连接端口。

| 形态 | 入口 | 说明 |
|---|---|---|
| **网页版** | https://Rhythm6X9.github.io/adb-wireless-tool/ | 桌面 Chrome/Edge，WebUSB 直连 ADB，不装东西 |
| **App 版** | [下载 APK](https://Rhythm6X9.github.io/adb-wireless-tool/download/ADBTool.apk) | 手机上直接操作，支持 root / Shizuku |

---

## 关键：无线调试 ≠ USB 调试

这是最常见的误解，两套东西完全不同：

| | USB 调试 | 无线调试（Android 11+） |
|---|---|---|
| 开关 | `adb_enabled` | **`adb_wifi_enabled`** |
| 通道 | USB 线 | Wi-Fi + **TLS** |
| 端口 | 固定 5555（`adb tcpip`） | **系统随机分配**，每次重开都变 |
| 认证 | 点「允许 USB 调试」 | **6 位配对码**（`adb pair`） |
| 服务发现 | 无 | **mDNS**：`_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp` |

所以「只开 USB 调试」+ `adb tcpip 5555` **不等于**打开了无线调试。本工具做的是后者。

### 真正的无线调试流程

```
① 开无线调试开关      settings put global adb_wifi_enabled 1
② 手机进配对模式      设置 → 系统 → 开发者选项 → 无线调试
                     → 点「使用配对码配对设备」
                     → 页面显示 IP:端口 和 6 位配对码
③ 配对（电脑）        adb pair 192.168.1.88:42135
                     → 输入 6 位码 → Successfully paired
④ 连接（电脑）        adb connect 192.168.1.88:38201
                     → 端口是配对后系统新分配的，不是配对端口
```

**配对码和配对端口是一次性的**，页面关闭即失效。连接端口也一样，无线调试重开就变。

---

## 网页版

打开 https://Rhythm6X9.github.io/adb-wireless-tool/ ，桌面 Chrome/Edge。

**它怎么工作**

不是模拟，是真的说 ADB 协议。`adb-web.js` 通过 WebUSB 拿到手机的 ADB 接口
（class `0xFF` / subclass `0x42` / protocol `0x01`），自己组 24 字节包头，
走 `CNXN → AUTH(SIGNATURE) → AUTH(RSAPUBLICKEY) → CNXN` 握手，
再用 `shell:` 服务通道执行命令。

**一键开启无线调试**会依次做 6 步，每步都验证结果：

```
1. settings put global adb_wifi_enabled 1     → 读回确认
2. settings put global development_settings_enabled 1   → 父开关
3. settings put global adb_enabled 1          → USB 调试（无线调试依赖 adbd）
4. svc wifi enable + ip addr show wlan0       → 取 IP，无线调试必须有 Wi-Fi
5. getprop service.adb.tls.port               → 确认 adbd TLS 服务起来了
   若没有：setprop persist.adb.tls_server.enable 1 + 重启 adbd
6. setprop service.adb.tcp.port 5555          → 兜底直连通道
```

然后**多路探测端口**：
- `dumpsys adb` 里匹配 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`
- `dumpsys mdns` 兜底
- `getprop service.adb.tls.port` / `service.adb.tcp.port`
- `netstat -tlnp | grep adbd` 找监听端口

页面会直接给出可复制的命令：
```
配对：adb pair 192.168.1.88:42135
连接：adb connect 192.168.1.88:38201
兜底：adb connect 192.168.1.88:5555
```

**浏览器要求**：桌面版 Chrome / Edge。Firefox、Safari、iOS 全部浏览器、手机浏览器都不支持 WebUSB。

---

## App 版

手机上自己操作，不需要电脑和线。

**提权方式**

| 方式 | 能做什么 |
|---|---|
| Root（Magisk / KernelSU） | 全部：改开关、开 5555、写 `adb_keys` 免配对确认、改 `ro.adb.secure` |
| Shizuku | 同上（shell 身份，`ro.*` 只读属性改不了） |
| 普通 | 只能一键跳转到「无线调试」页面做引导 |

**界面结构**

```
① 权限环境       root / Shizuku / 普通，实时探测
② 一键开无线调试  6 步流程，每步显示成功/失败与原因
③ 无线调试状态    开关 / TLS端口 / 配对端口 / 连接端口 / mDNS / adbd PID
④ 配对与连接      填 IP + 6位码 → 生成 adb pair / adb connect → 一键复制
⑤ 辅助开关        开发者选项 / USB调试 / 无线调试开关 / Wi-Fi / 写公钥 / 重启adbd
⑥ 日志
```

状态面板会明确告诉你现在卡在哪一步：开关没开、TLS 没起、配对端口没监听、还是连接端口还没分配。

---

## 源码结构

```
.
├── docs/                          # GitHub Pages
│   ├── index.html                 # 网页界面（以无线调试为核心）
│   ├── adb-web.js                 # ADB over WebUSB 协议实现
│   ├── wireless.js                # 无线调试逻辑：开开关 / 探端口 / 生成命令
│   └── download/ADBTool.apk
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/yg/adbtool/
│   │   ├── MainActivity.java      # 原生控件界面
│   │   ├── WirelessDebug.java     # ← 无线调试核心：开关/TLS/端口探测/配对页
│   │   ├── AutoEngine.java        # 编排流程
│   │   ├── Shell.java             # root / Shizuku / 普通 三级执行
│   │   └── AdbKeys.java           # adb 密钥对生成
│   └── res/
├── build.ps1                      # aapt2 + javac + d8 + zipalign + apksigner
└── libs/                          # Shizuku api/provider classes.jar
```

### 构建

不需要 Android Studio / Gradle：

```powershell
.\build.ps1
# 产物 out/ADBTool.apk
```

---

## 技术细节

### 端口探测为什么这么麻烦

Android 不给第三方 App 读无线调试端口。能拿到端口的路径只有几条，得全试：

| 路径 | 命令 | 前提 |
|---|---|---|
| dumpsys 服务表 | `dumpsys adb` | shell 权限，且系统把 mDNS 记录写进去 |
| mDNS dump | `dumpsys mdns` | 同上 |
| 系统属性 | `getprop service.adb.tls.port` | 只能拿到 TLS 端口，不一定是可连接端口 |
| 监听表 | `netstat -tlnp \| grep adbd` | 需 root/Shizuku |

`wireless.js` / `WirelessDebug.java` 用 4 条路径交叉验证，正则匹配多种真实格式：

```
_adb-tls-pairing._tcp  "adb-xxxx"  42135
_adb-tls-pairing._tcp  42135
pairing port: 41234
adb-tls-connect._tcp  38201
```

### 为什么普通 App 写不了开关

`adb_wifi_enabled` 和 `development_settings_enabled` 都在 `Settings.Global`，
写入需要 `WRITE_SECURE_SETTINGS`。这个权限只能 `pm grant` 或 shell 身份授予，
普通 App 拿不到。所以要么 root、要么 Shizuku、要么手动点。

写不进去时工具会**如实报告**（不是假装成功），并引导你去页面手动打开。

### ADB 公钥格式

```
524 字节，全小端：
  u32  7                 "ssh-rsa" 长度
  u32  n0inv             = -1 / N[0] mod 2^32
  u8[256] modulus        N
  u8[256] rr             = (2^2048)^2 mod N
  u32  exponent          e (65537)
→ base64 后正好 700 字符
```

网页版用 WebCrypto 现场生成 RSA-2048，App 版用 `KeyPairGenerator`，
两边算法一致，都用真实 RSA 向量验证过（`n0inv × n0 ≡ -1 mod 2³²`、`rr = R² mod N`）。

---

## 常见问题

**开了开关但端口读不到**
配对端口只在「使用配对码配对设备」对话框打开期间监听，对话框一关就没了。要么在页面开着时点「刷新状态」，要么直接抄页面上的端口。

**配对成功但 connect 不上**
`adb pair` 用的端口 ≠ `adb connect` 用的端口。配对后手机页面会刷新显示新的「IP 地址和端口」，用那个。

**adb_wifi_enabled 写不进去**
普通 App 没有 `WRITE_SECURE_SETTINGS`。用 root/Shizuku，或者手动去页面点开关。

**Android 10 及以下**
没有 TLS 无线调试，只有 `adb tcpip 5555` 这一套。工具会检测到并走对应分支。

**连接端口每次都变**
这是系统行为，无线调试重开就换端口。想固定就用 root 开 5555 兜底。

---

## License

MIT
