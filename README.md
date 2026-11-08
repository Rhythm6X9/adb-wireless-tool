# USB 无线调试工具

一键开启 **开发者选项 → USB 调试 → 无线调试**。两种形态：

| 形态 | 入口 | 提权方式 | 说明 |
|---|---|---|---|
| **网页版** | 打开 Pages 链接，桌面 Chrome/Edge | WebUSB 直连 ADB | 不装任何东西，插线就能用 |
| **App 版** | 下载 APK 装到手机 | Root / Shizuku | 手机上自己操作，不依赖电脑 |

---

## 一、网页版（推荐先用这个）

**打开：** https://Rhythm6X9.github.io/adb-wireless-tool/

原理不是模拟，是**真的说 ADB 协议**：页面的 `adb-web.js` 通过 WebUSB 拿到 Android 的 ADB 接口
（USB class `0xFF` / subclass `0x42` / protocol `0x01`），自己组 24 字节包头，
完成 `CNXN → AUTH(SIGNATURE) → AUTH(RSAPUBLICKEY) → CNXN` 握手，然后走 `shell:` 服务通道执行命令。

**用法**

1. 手机用数据线连电脑，打开「开发者选项」+「USB 调试」
2. 点「选择 USB 设备并连接」，弹窗里选手机
3. 手机弹「允许 USB 调试吗」→ 勾「一律允许」→ 允许
4. 点「一键全自动」，它依次跑：
   ```
   settings put global development_settings_enabled 1
   settings put global adb_enabled 1
   svc wifi enable
   ip -f inet addr show wlan0          # 取 IP
   setprop service.adb.tcp.port 5555
   stop adbd; start adbd
   getprop service.adb.tls.port        # Android 11+ 无线调试端口
   settings get global adb_wifi_enabled
   ```
5. 拿到 `adb connect 192.168.x.x:5555`，拔掉数据线即可无线调试

**浏览器要求**

- ✅ 桌面版 Chrome / Edge（Chromium 内核）
- ❌ Firefox、Safari、iOS 全部浏览器
- ❌ iframe 内嵌（Pages 是顶层页面，没问题）
- ❌ 手机浏览器（WebUSB 不支持）

**首次连接要点「允许」的原因**

手机对每个 adb 主机有个白名单 `/data/misc/adb/adb_keys`。网页版第一次连会在浏览器里用 WebCrypto
生成一对 RSA-2048 密钥，把公钥按 adb 的格式（小端 `n0inv | modulus | rr | exponent`，base64 后 700 字符）
发给手机，手机弹窗让你授权。授权一次之后，同一个浏览器再连就不用再点（密钥存在 `localStorage`）。

---

## 二、App 版

**下载：** [ADBTool.apk](https://Rhythm6X9.github.io/adb-wireless-tool/download/ADBTool.apk) （44 KB）

**提权方式**

| 方式 | 要求 | 能做什么 |
|---|---|---|
| Root | 有 `su`（Magisk / KernelSU） | 全部功能：改开关、开 5555、写 `adb_keys` 免确认、改 `ro.adb.secure` |
| Shizuku | 装 [Shizuku](https://github.com/RikkaApps/Shizuku) 并授权本应用 | 同上（以 shell 身份执行，`ro.*` 只读属性改不了） |
| 普通 | 无 | 只能一键跳转到开发者选项 / 无线调试设置页做引导 |

**功能**

- 一键全自动：7 步流程，每步显示成功/失败与原因
- 单项操作：单独开开发者选项 / USB 调试 / TCP 端口
- 写 adb 公钥：在应用私有目录生成 adb 密钥对，追加到 `/data/misc/adb/adb_keys`，之后电脑连不用再点允许
- 设备信息：型号、Android 版本、构建类型、ABI、WLAN IP、TLS 端口、当前权限
- 快捷工具：复制 `adb connect` 命令、读取无线调试端口

**为什么 App 版要 root**

`settings put global development_settings_enabled` 和 `adb_enabled` 属于 `Settings.Global`，
写它们需要 `WRITE_SECURE_SETTINGS` 权限。这个权限只能通过 `pm grant` 或 shell 身份授予，
普通 App 拿不到。所以要么 root、要么 Shizuku、要么手动点开关。

---

## 三、源码结构

```
.
├── docs/                          # GitHub Pages 站点
│   ├── index.html                 # 网页版界面
│   ├── adb-web.js                 # ADB over WebUSB 协议实现（无依赖）
│   └── download/ADBTool.apk       # App 安装包
├── app/src/main/                  # Android 源码（纯 Java，无 AndroidX/Compose）
│   ├── AndroidManifest.xml
│   ├── java/com/yg/adbtool/
│   │   ├── MainActivity.java      # 原生控件界面
│   │   ├── AutoEngine.java        # 7 步自动化流程
│   │   ├── Shell.java             # root / Shizuku / 普通 三级执行
│   │   └── AdbKeys.java           # adb 密钥对生成
│   └── res/                       # 图标与字符串
├── build.ps1                      # 不走 Gradle，直接 aapt2 + javac + d8 + apksigner
└── libs/                          # Shizuku api/provider 的 classes.jar
```

### 构建 App

不需要 Android Studio、不需要 Gradle。只要有 JDK 17 + Android SDK build-tools：

```powershell
# 需要的环境：jdk17、sdk（platforms;android-34 + build-tools;34.0.0）、keystore
# 目录约定见 build.ps1 顶部变量

.\build.ps1
# 产物：out/ADBTool.apk
```

流程：`aapt2 compile` 资源 → `aapt2 link` 生成基础 APK + R.java → `javac` 编译 →
`jar` 打包 → `d8` 转 dex → 写入 APK → `zipalign` → `apksigner` 签名。

---

## 四、技术备注

**ADB 协议握手**

```
host → device   CNXN  version=0x01000001  maxdata=1MB  "host::features=..."
device → host   AUTH  type=1 (TOKEN)      20 字节随机 token
host → device   AUTH  type=2 (SIGNATURE)  RSA-2048 PKCS#1 v1.5 / SHA-1 签名
device → host   AUTH  type=1 (TOKEN)      不认 → 再给一次
host → device   AUTH  type=3 (RSAPUBLICKEY)  base64 公钥 + \0
device          → 手机弹「允许 USB 调试」
device → host   CNXN  "device::ro.product.model=...;features=..."
```

**公钥格式**（`adb-web.js` / `AdbKeys.java` 都实现了，已用真实 RSA 向量验证）

```
524 字节，全部小端：
  u32  7                 "ssh-rsa" 长度
  u32  n0inv             = -1 / N[0] mod 2^32
  u8[256] modulus        N
  u8[256] rr             = (2^2048)^2 mod N
  u32  exponent          e（通常是 65537）
→ base64 后正好 700 字符
```

**流控**：`OPEN(localId) → OKAY(remoteId, localId) → WRTE(remoteId, localId, data) → OKAY 应答 → CLSE`。
每个 `WRTE` 都要回 `OKAY`，否则对端会卡住。

**Android 11+ 的坑**

无线调试改成了 TLS + 配对码：端口是随机的、连接要证书、配对码每次打开页面都变。
网页版只能读出端口号，配对必须走系统弹窗。要免掉这套，用 App 版在 root 下直接开传统 `5555`
（`setprop service.adb.tcp.port 5555` + `stop adbd; start adbd`）。

---

## 五、常见问题

**连不上 / 握手超时**
- 数据线是充电线（没数据线芯）→ 换一根
- 手机没点「允许 USB 调试」→ 拔插重来，注意看手机屏幕
- 系统里已有别的 adb 在跑（Android Studio / 手机助手）→ 先 `adb kill-server`，会抢 USB 接口
- Windows 装了厂商驱动接管了接口 → 设备管理器里换成「Android ADB Interface」

**握手成功但 `settings put` 返回失败**
- 极少数 ROM 对 `settings put global` 做了额外校验，这时只能手动点开关键

**拿到 IP 但 `adb connect` 连不上**
- 手机和电脑不在同一局域网
- 路由器开了 AP 隔离
- 手机息屏后断 Wi-Fi → 开发者选项里关掉「Wi-Fi 省电」

**Android 11+ 读不到 TLS 端口**
- 要先在手机上打开「无线调试」总开关，页面开着时端口才有效

---

## License

MIT
