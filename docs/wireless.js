/* =====================================================================
 * wireless.js —— 无线调试（Wireless Debugging）专用逻辑
 *
 * 关键点：Android 11+ 的「无线调试」不是 tcpip 5555：
 *   1) 独立开关 Settings.Global.ADB_WIFI_ENABLED
 *   2) 系统起 mDNS：_adb-tls-pairing._tcp / _adb-tls-connect._tcp
 *   3) 连接走 TLS，端口随机，必须先 6 位配对码配对
 *
 * 本文件负责：开开关 → 多路探测端口 → 生成配对/连接命令
 * ===================================================================== */
(function (global) {
  'use strict';

  const RE_PAIR = /_adb-tls-pairing\._tcp[^\d]{0,80}?(\d{4,5})/;
  const RE_CONN = /_adb-tls-connect\._tcp[^\d]{0,80}?(\d{4,5})/;
  const RE_PAIR_PORT = /pairing[_ ]?port[=:\s]+(\d{4,5})/i;
  const RE_TLS_PORT = /(?:tls[_ ]?port|connect[_ ]?port|service\.adb\.tls\.port)[=:\s]+(\d{4,5})/i;

  const Wireless = {
    /** 打开无线调试（分步返回结果） */
    async enable(adb, log) {
      const steps = [];
      const push = (title, ok, detail) => {
        steps.push({ title, ok, detail });
        if (log) log(title + ' → ' + detail, ok ? 'ok' : 'err');
      };

      // 1) 无线调试总开关
      await adb.shell('settings put global adb_wifi_enabled 1', 10000);
      let wifi = (await adb.shell('settings get global adb_wifi_enabled', 8000)).trim();
      push('写入无线调试开关', wifi === '1',
        'adb_wifi_enabled=' + (wifi || '(空)') + (wifi === '1' ? '' : '  ← 写入被拒，需手动去页面开'));

      // 2) 开发者选项（父开关）
      await adb.shell('settings put global development_settings_enabled 1', 10000);
      const dev = (await adb.shell('settings get global development_settings_enabled', 8000)).trim();
      push('确认开发者选项', dev === '1', 'development_settings_enabled=' + (dev || '(空)'));

      // 3) adb 总开关
      await adb.shell('settings put global adb_enabled 1', 10000);
      const adbOn = (await adb.shell('settings get global adb_enabled', 8000)).trim();
      push('确认 USB 调试', adbOn === '1', 'adb_enabled=' + (adbOn || '(空)'));

      // 4) Wi-Fi 必须在线
      await adb.shell('svc wifi enable', 8000);
      let ip = '';
      for (let i = 0; i < 3; i++) {
        const raw = await adb.shell('ip -f inet addr show wlan0', 8000);
        const m = raw.match(/inet\s+(\d+\.\d+\.\d+\.\d+)/);
        if (m) { ip = m[1]; break; }
        await new Promise(r => setTimeout(r, 1500));
      }
      push('确认 Wi-Fi 在线', !!ip, ip ? 'wlan0 IP = ' + ip : 'wlan0 无 IPv4 地址');

      // 5) adbd 的 TLS 服务
      let tls = (await adb.shell('getprop service.adb.tls.port', 8000)).trim();
      let tlsOk = /^\d+$/.test(tls) && parseInt(tls, 10) > 0;
      if (!tlsOk) {
        await adb.shell('setprop persist.adb.tls_server.enable 1', 6000);
        await adb.shellFire('stop adbd; start adbd');
        await new Promise(r => setTimeout(r, 2500));
        tls = (await adb.shell('getprop service.adb.tls.port', 8000)).trim();
        tlsOk = /^\d+$/.test(tls) && parseInt(tls, 10) > 0;
      }
      push('启动 adbd TLS 服务', tlsOk,
        tlsOk ? 'service.adb.tls.port = ' + tls : '未取到 TLS 端口（多数 ROM 需在页面手动打开无线调试）');

      // 6) 传统 tcpip 兜底
      let tcpPort = 0;
      await adb.shell('setprop service.adb.tcp.port 5555', 6000);
      await adb.shellFire('stop adbd; start adbd');
      await new Promise(r => setTimeout(r, 2200));
      const tcp = (await adb.shell('getprop service.adb.tcp.port', 8000)).trim();
      if (/^\d+$/.test(tcp) && parseInt(tcp, 10) > 0) tcpPort = parseInt(tcp, 10);
      push('传统 tcpip 兜底', tcpPort === 5555,
        tcpPort === 5555 ? 'service.adb.tcp.port=5555（配对之外多一条直连）'
                         : '写入未生效，读回 "' + tcp + '"');

      return { steps, ip, wifiOn: wifi === '1', tlsPort: tls, tcpPort };
    },

    /** 多路探测配对/连接端口 */
    async probe(adb) {
      const out = { pairPort: 0, connectPort: 0, tlsPort: 0, tcpPort: 0, mdns: false, raw: '' };

      const dumpsys = await adb.shell('dumpsys adb', 14000);
      out.raw = dumpsys;
      this._scan(dumpsys, out);

      // mDNS 活动判定：配对/连接端口出现，或 dumpsys mdns 有内容
      out.mdns = !!(out.pairPort || out.connectPort);
      if (!out.pairPort || !out.connectPort) {
        const mdns = await adb.shell('dumpsys mdns', 12000);
        this._scan(mdns, out);
        if (mdns && mdns.trim().length > 10 && !/not found|unknown|Permission/i.test(mdns)) {
          out.mdns = true;
        }
      }
      if (!out.mdns && out.tlsPort > 0) out.mdns = true;  // TLS 服务在跑说明 mDNS 也起了

      const tls = (await adb.shell('getprop service.adb.tls.port', 8000)).trim();
      if (/^\d+$/.test(tls)) out.tlsPort = parseInt(tls, 10);
      const tcp = (await adb.shell('getprop service.adb.tcp.port', 8000)).trim();
      if (/^\d+$/.test(tcp)) out.tcpPort = parseInt(tcp, 10);

      if (!out.connectPort && out.tlsPort > 0) out.connectPort = 0; // TLS 端口不等于可连接端口

      // netstat 兜底
      if (!out.connectPort) {
        const net = await adb.shell('netstat -tlnp 2>/dev/null | grep adbd', 10000);
        const re = /:(\d{4,5})\s/g;
        let m;
        while ((m = re.exec(net)) !== null) {
          const p = parseInt(m[1], 10);
          if (p > 1024 && p !== 5037) { out.connectPort = p; break; }
        }
      }
      return out;
    },

    _scan(text, out) {
      if (!text) return;
      let m = text.match(RE_PAIR) || text.match(RE_PAIR_PORT);
      if (m && !out.pairPort) out.pairPort = parseInt(m[1], 10);
      m = text.match(RE_CONN);
      if (m && !out.connectPort) out.connectPort = parseInt(m[1], 10);
      if (!out.tlsPort) {
        m = text.match(RE_TLS_PORT);
        if (m) out.tlsPort = parseInt(m[1], 10);
      }
    },

    /** 生成完整指引文本 */
    guide(ip, st, tcpPort) {
      const lines = [];
      lines.push('【无线调试连接指引】');
      lines.push('设备 IP：' + (ip || '未取到'));
      if (st.wifiOn) lines.push('无线调试开关：已开启');
      else lines.push('无线调试开关：未开启（需手动去页面打开）');

      if (st.pairPort > 0) {
        lines.push('');
        lines.push('① 配对（在电脑上执行）：');
        lines.push('   adb pair ' + ip + ':' + st.pairPort);
        lines.push('   然后输入手机上显示的 6 位配对码');
      } else {
        lines.push('');
        lines.push('① 手机上打开：设置 → 系统 → 开发者选项 → 无线调试');
        lines.push('   点「使用配对码配对设备」，页面会显示 IP:端口 和 6 位码');
      }

      if (st.connectPort > 0) {
        lines.push('');
        lines.push('② 连接：');
        lines.push('   adb connect ' + ip + ':' + st.connectPort);
      } else {
        lines.push('');
        lines.push('② 配对完成后，同一页面会显示「IP 地址和端口」，用它连接：');
        lines.push('   adb connect ' + ip + ':<页面上的端口>');
      }

      if (tcpPort > 0) {
        lines.push('');
        lines.push('③ 兜底直连（免配对）：');
        lines.push('   adb connect ' + ip + ':' + tcpPort);
      }
      return lines.join('\n');
    }
  };

  global.Wireless = Wireless;
})(window);
