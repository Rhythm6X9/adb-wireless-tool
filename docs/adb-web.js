/* =====================================================================
 * adb-web.js —— 在浏览器里直接说 ADB 协议（WebUSB）
 * 不依赖任何库，纯原生 WebUSB + WebCrypto
 *
 * 原理：Android 的 ADB 接口是 USB 类 0xFF / 子类 0x42 / 协议 0x01，
 *      浏览器通过 WebUSB 拿到这个接口后，按 ADB 的 24 字节包头做
 *      CNXN / AUTH / OPEN / WRTE / OKAY / CLSE 握手，就能跑 shell。
 *
 * 前提（重要）：
 *   1. Chrome / Edge 桌面版（WebUSB 只有 Chromium 系支持，且不能是 iframe）
 *   2. 手机 USB 调试已开，且本机的 adb 授权已存在（或设备 ro.adb.secure=0）
 *   3. 手机上弹「允许 USB 调试」时要点允许
 * ===================================================================== */
(function (global) {
  'use strict';

  const ADB_CLASS = 0xff;
  const ADB_SUBCLASS = 0x42;
  const ADB_PROTOCOL = 0x01;

  const A_CNXN = 0x4e584e43;
  const A_AUTH = 0x48545541;
  const A_OPEN = 0x4e45504f;
  const A_OKAY = 0x59414b4f;
  const A_CLSE = 0x45534c43;
  const A_WRTE = 0x45545257;
  const A_STLS = 0x534c5453;

  const AUTH_TOKEN = 1;
  const AUTH_SIGNATURE = 2;
  const AUTH_RSAPUBLICKEY = 3;

  const VERSION = 0x01000001;
  const MAXDATA = 1024 * 1024;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function u32(v) {
    const b = new Uint8Array(4);
    new DataView(b.buffer).setUint32(0, v >>> 0, true);
    return b;
  }
  function rd32(dv, off) {
    return dv.getUint32(off, true);
  }
  function readString(u8) {
    let end = u8.length;
    for (let i = 0; i < u8.length; i++) {
      if (u8[i] === 0) { end = i; break; }
    }
    return new TextDecoder().decode(u8.subarray(0, end));
  }
  function encodeStr(s) {
    const body = new TextEncoder().encode(s);
    const out = new Uint8Array(body.length + 1);
    out.set(body, 0);
    out[body.length] = 0;
    return out;
  }
  function checksum(u8) {
    let sum = 0;
    for (let i = 0; i < u8.length; i++) sum = (sum + u8[i]) >>> 0;
    return sum;
  }
  function concat(list) {
    let len = 0;
    for (const a of list) len += a.length;
    const out = new Uint8Array(len);
    let off = 0;
    for (const a of list) { out.set(a, off); off += a.length; }
    return out;
  }

  /* ---------------- ADB 密钥（浏览器内生成，存 localStorage） ---------------- */
  const KEY_STORE = 'adbweb_privkey_jwk';

  class AdbKeys {
    constructor() { this.privateKey = null; this.publicBlob = null; this.pubB64 = ''; }

    async load() {
      const raw = localStorage.getItem(KEY_STORE);
      if (raw) {
        try {
          const jwk = JSON.parse(raw);
          this.privateKey = await crypto.subtle.importKey(
            'jwk', jwk,
            { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-1' },
            true, ['sign']
          );
          await this._derivePublic(jwk);
          return;
        } catch (e) { /* 重新生成 */ }
      }
      await this.generate();
    }

    async generate() {
      const kp = await crypto.subtle.generateKey(
        { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' },
        true, ['sign', 'verify']
      );
      this.privateKey = kp.privateKey;
      const jwk = await crypto.subtle.exportKey('jwk', kp.privateKey);
      localStorage.setItem(KEY_STORE, JSON.stringify(jwk));
      await this._derivePublic(jwk);
    }

    async _derivePublic(jwk) {
      const n = b64urlToBytes(jwk.n);
      const e = b64urlToBytes(jwk.e);
      this.publicBlob = buildAdbPublicKey(n, e);
      this.pubB64 = bytesToB64(this.publicBlob);
    }

    async sign(token) {
      const sig = await crypto.subtle.sign(
        { name: 'RSASSA-PKCS1-v1_5' }, this.privateKey, token
      );
      return new Uint8Array(sig);
    }
  }

  function b64urlToBytes(s) {
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    const bin = atob(s);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function bytesToB64(u8) {
    let bin = '';
    for (let i = 0; i < u8.length; i++) bin += String.fromCharCode(u8[i]);
    return btoa(bin);
  }

  /** 组装 adb 公钥 blob（小端：len(7) | n0inv | modulus 256 | rr 256 | exponent） */
  function buildAdbPublicKey(nBe, eBe) {
    const N = beToBigInt(nBe);
    const E = beToBigInt(eBe);
    const MOD = 256;
    const out = new Uint8Array(3 * 4 + 2 * MOD);

    const modLE = bigIntToFixedLE(N, MOD);
    const two32 = 1n << 32n;
    const n0 = N % two32;
    const inv = modInverse(n0, two32);
    const n0inv = (two32 - inv) % two32;
    const rr = modPow(1n << 4096n, 1n, N); // 2^4096 mod N

    let off = 0;
    off = wr32(out, off, 7);
    off = wr32(out, off, Number(n0inv & 0xffffffffn));
    out.set(modLE, off); off += MOD;
    out.set(bigIntToFixedLE(rr, MOD), off); off += MOD;
    wr32(out, off, Number(E & 0xffffffffn));
    return out;
  }
  function wr32(buf, off, v) {
    const dv = new DataView(buf.buffer, buf.byteOffset);
    dv.setUint32(off, v >>> 0, true);
    return off + 4;
  }
  function beToBigInt(be) {
    let h = '0x0';
    for (let i = 0; i < be.length; i++) h += be[i].toString(16).padStart(2, '0');
    return BigInt(h);
  }
  function bigIntToFixedLE(v, size) {
    const out = new Uint8Array(size);
    let x = v;
    const mask = 0xffn;
    for (let i = 0; i < size; i++) {
      out[i] = Number(x & mask);
      x >>= 8n;
    }
    return out;
  }
  function modPow(base, exp, mod) {
    let r = 1n, b = base % mod, e = exp;
    while (e > 0n) {
      if (e & 1n) r = (r * b) % mod;
      b = (b * b) % mod;
      e >>= 1n;
    }
    return r;
  }
  function modInverse(a, m) {
    let [old_r, r] = [a % m, m];
    let [old_s, s] = [1n, 0n];
    while (r !== 0n) {
      const q = old_r / r;
      [old_r, r] = [r, old_r - q * r];
      [old_s, s] = [s, old_s - q * s];
    }
    return ((old_s % m) + m) % m;
  }

  /* ---------------------------- ADB 连接 ---------------------------- */
  class AdbConnection extends EventTarget {
    constructor(device, iface, epIn, epOut) {
      super();
      this.device = device;
      this.iface = iface;
      this.epIn = epIn;
      this.epOut = epOut;
      this.packetSize = (epOut && epOut.packetSize) ? Math.min(epOut.packetSize, 16384) : 512;
      this.rxBuf = new Uint8Array(0);
      this.nextLocalId = 1;
      this.streams = new Map();      // localId -> stream 对象
      this.closed = false;
      this.keys = new AdbKeys();
      this.banner = '';
      this.maxData = MAXDATA;
      this._readLoopPromise = null;
      this._okayWaiters = new Map(); // localId -> resolve
    }

    static supported() {
      return !!(navigator.usb && navigator.usb.requestDevice);
    }

    /** 让用户选择设备（必须由点击事件触发） */
    static async pick() {
      const device = await navigator.usb.requestDevice({
        filters: [
          { classCode: ADB_CLASS, subclassCode: ADB_SUBCLASS, protocolCode: ADB_PROTOCOL }
        ]
      });
      return device;
    }

    async open() {
      await this.device.open();
      if (!this.device.configuration) {
        await this.device.selectConfiguration(1);
      }
      let target = null;
      for (const itf of this.device.configuration.interfaces) {
        for (const alt of itf.alternates) {
          if (alt.interfaceClass === ADB_CLASS &&
              alt.interfaceSubclass === ADB_SUBCLASS &&
              alt.interfaceProtocol === ADB_PROTOCOL) {
            target = { itf, alt };
            break;
          }
        }
        if (target) break;
      }
      if (!target) {
        throw new Error('这个 USB 接口里没有 ADB 端点。请确认手机已开启 USB 调试，并在弹窗点「允许」。');
      }
      this.iface = target.itf;
      await this.device.claimInterface(this.iface.interfaceNumber);

      let epIn = null, epOut = null;
      for (const ep of target.alt.endpoints) {
        if (ep.type !== 'bulk') continue;
        if (ep.direction === 'in') epIn = ep;
        else epOut = ep;
      }
      if (!epIn || !epOut) throw new Error('ADB 接口缺少 bulk 端点');
      this.epIn = epIn;
      this.epOut = epOut;
      this.packetSize = (epOut && epOut.packetSize) ? Math.min(epOut.packetSize, 16384) : 512;

      this._startReadLoop();
      await this._handshake();
      return this;
    }

    _startReadLoop() {
      this._readLoopPromise = (async () => {
        while (!this.closed) {
          let res;
          try {
            res = await this.device.transferIn(this.epIn.endpointNumber, 65536);
          } catch (e) {
            if (this.closed) break;
            this.dispatchEvent(new CustomEvent('error', { detail: e }));
            break;
          }
          if (res.status !== 'ok' || !res.data) continue;
          const chunk = new Uint8Array(res.data.buffer, res.data.byteOffset, res.data.byteLength);
          this.rxBuf = concat([this.rxBuf, chunk]);
          this._parsePackets();
        }
      })();
    }

    _parsePackets() {
      while (this.rxBuf.length >= 24) {
        const dv = new DataView(this.rxBuf.buffer, this.rxBuf.byteOffset, this.rxBuf.length);
        const cmd = rd32(dv, 0);
        const magic = rd32(dv, 20);
        if ((cmd ^ 0xffffffff) >>> 0 !== magic >>> 0) {
          // 错位，丢掉 1 字节重新同步
          this.rxBuf = this.rxBuf.subarray(1);
          continue;
        }
        const arg0 = rd32(dv, 4);
        const arg1 = rd32(dv, 8);
        const len = rd32(dv, 12);
        if (this.rxBuf.length < 24 + len) return;
        const payload = this.rxBuf.subarray(24, 24 + len);
        this.rxBuf = this.rxBuf.subarray(24 + len);
        this._onPacket(cmd, arg0, arg1, payload);
      }
    }

    _onPacket(cmd, arg0, arg1, payload) {
      switch (cmd) {
        case A_CNXN:
          this.banner = readString(payload);
          this.maxData = arg1 || MAXDATA;
          this.dispatchEvent(new CustomEvent('ready', { detail: this.banner }));
          break;
        case A_AUTH:
          // adb 协议：AUTH 包的 arg0 才是认证类型（1=TOKEN 2=SIGNATURE 3=RSAPUBLICKEY）
          this._onAuth(arg0, payload).catch((e) => {
            this.dispatchEvent(new CustomEvent('error', { detail: new Error('认证失败: ' + (e && e.message ? e.message : e)) }));
          });
          break;
        case A_OKAY: {
          const waiter = this._okayWaiters.get(arg1);
          if (waiter) { this._okayWaiters.delete(arg1); waiter(); }
          const st = this.streams.get(arg1);
          if (st && st.onOkay) st.onOkay(arg0);
          break;
        }
        case A_WRTE: {
          const st = this.streams.get(arg1);
          if (st) {
            st.onData(payload);
            this._send(A_OKAY, arg1, arg0, new Uint8Array(0)).catch(() => {});
          }
          break;
        }
        case A_CLSE: {
          const st = this.streams.get(arg1);
          if (st) {
            this.streams.delete(arg1);
            st.onClose();
          }
          break;
        }
        default:
          break;
      }
    }

    async _onAuth(type, token) {
      if (!this.keys.privateKey) await this.keys.load();
      this._authTries = (this._authTries || 0) + 1;

      // 第一次：用私钥签名；设备不认会再发一次 token，第二次就必须交公钥
      if (type === AUTH_TOKEN && this._authTries === 1) {
        try {
          const sig = await this.keys.sign(token);
          await this._send(A_AUTH, AUTH_SIGNATURE, 0, sig);
          return;
        } catch (e) { /* 落到交公钥 */ }
      }
      // 交公钥：手机会弹「允许 USB 调试吗」
      await this._send(A_AUTH, AUTH_RSAPUBLICKEY, 0, encodeStr(this.keys.pubB64 + ' web-adb'));
      this.dispatchEvent(new CustomEvent('auth', {
        detail: '已向手机发送公钥，请在手机上点「允许 USB 调试」（可勾选一律允许）'
      }));
    }

    async _send(cmd, arg0, arg1, payload) {
      const data = payload || new Uint8Array(0);
      const head = new Uint8Array(24);
      const dv = new DataView(head.buffer);
      dv.setUint32(0, cmd >>> 0, true);
      dv.setUint32(4, arg0 >>> 0, true);
      dv.setUint32(8, arg1 >>> 0, true);
      dv.setUint32(12, data.length, true);
      dv.setUint32(16, checksum(data), true);
      dv.setUint32(20, (cmd ^ 0xffffffff) >>> 0, true);
      const full = concat([head, data]);
      // 必须按端点包长切片写，否则部分控制器会丢包
      let off = 0;
      while (off < full.length) {
        const end = Math.min(off + this.packetSize, full.length);
        await this.device.transferOut(this.epOut.endpointNumber, full.subarray(off, end));
        off = end;
      }
    }

    async _handshake() {
      await this.keys.load();
      const banner = 'host::features=cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,abb_exec,remount_shell,track_app,sendrecv_v2';
      const ready = new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error('握手超时：手机没有回应。请确认 USB 调试已开，并在手机上点「允许 USB 调试」')), 25000);
        const onReady = () => { clearTimeout(t); this.removeEventListener('ready', onReady); resolve(); };
        this.addEventListener('ready', onReady);
      });
      await this._send(A_CNXN, VERSION, MAXDATA, encodeStr(banner));
      await ready;
      return this.banner;
    }

    /** 打开一个服务流，返回可读写的 handle */
    async openStream(service) {
      const localId = this.nextLocalId++;
      if (this.nextLocalId > 0x7fffffff) this.nextLocalId = 1;
      const self = this;
      const stream = {
        localId,
        remoteId: 0,
        chunks: [],
        closed: false,
        onData(d) { this.chunks.push(new Uint8Array(d)); },
        onClose() { this.closed = true; if (this._closeResolve) this._closeResolve(); },
        onOkay() { if (this._okayResolve) { this._okayResolve(); this._okayResolve = null; } },
        _closeResolve: null,
        _okayResolve: null
      };
      this.streams.set(localId, stream);

      // 等设备 OKAY，拿到 remoteId
      const established = new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error('服务 ' + service + ' 打开超时')), 15000);
        stream.onOkay = (remoteId) => {
          if (!stream.remoteId) {
            stream.remoteId = remoteId;
            clearTimeout(t);
            resolve();
          }
        };
      });
      await this._send(A_OPEN, localId, 0, encodeStr(service));
      await established;

      return {
        async write(text) {
          const bytes = typeof text === 'string' ? new TextEncoder().encode(text) : text;
          let off = 0;
          const size = Math.min(self.maxData - 64, 64 * 1024);
          while (off < bytes.length) {
            const end = Math.min(off + size, bytes.length);
            await self._send(A_WRTE, stream.localId, stream.remoteId, bytes.subarray(off, end));
            off = end;
          }
        },
        async readAll() {
          await new Promise((resolve) => {
            if (stream.closed) return resolve();
            stream._closeResolve = resolve;
            setTimeout(resolve, 15000);
          });
          return concat(stream.chunks);
        },
        async close() {
          if (stream.closed) return;
          try { await self._send(A_CLSE, stream.localId, stream.remoteId, new Uint8Array(0)); } catch (e) {}
          self.streams.delete(stream.localId);
        },
        get closed() { return stream.closed; }
      };
    }

    /** 跑一条 shell 命令，返回文本输出 */
    async shell(cmd, timeoutMs = 20000) {
      const svc = 'shell:' + cmd;
      const st = await this.openStream(svc);
      const done = st.readAll();
      const text = await Promise.race([
        done.then((u8) => new TextDecoder().decode(u8)),
        sleep(timeoutMs).then(() => '(timeout)')
      ]);
      await st.close();
      return text;
    }

    /** 执行命令并把结果读干净（不等待 CLSE，用于 stop adbd 这类会断链的） */
    async shellFire(cmd) {
      try {
        const st = await this.openStream('shell:' + cmd);
        const t0 = Date.now();
        while (Date.now() - t0 < 2500) {
          if (st.closed) break;
          await sleep(100);
        }
        return 'sent';
      } catch (e) {
        return 'sent (no reply)';
      }
    }

    async close() {
      this.closed = true;
      try { await this.device.releaseInterface(this.iface.interfaceNumber); } catch (e) {}
      try { await this.device.close(); } catch (e) {}
    }
  }

  global.AdbWeb = { AdbConnection, AdbKeys, supported: () => AdbConnection.supported() };
})(window);
