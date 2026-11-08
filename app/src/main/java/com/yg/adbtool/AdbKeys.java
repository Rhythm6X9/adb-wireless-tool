package com.yg.adbtool;

import android.util.Base64;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

/**
 * 生成 adb 认的 RSA 密钥对。
 *
 * adbkey.pub = Base64( 小端: 4B长度(7) | n0inv | modulus(256B) | rr(256B) | exponent )
 * adbkey     = PKCS#8 DER 原始字节
 *
 * 把 adbkey.pub 内容追加到手机的 /data/misc/adb/adb_keys，电脑端用对应 adbkey 私钥，
 * 之后 adb connect 就不再弹「允许 USB 调试」。
 */
public final class AdbKeys {

    private static final int MODULUS_SIZE = 256;
    private static final int ENCODED_SIZE = 3 * 4 + 2 * MODULUS_SIZE;

    public static final class KeyFiles {
        public final byte[] privateDer;
        public final String publicB64;
        KeyFiles(byte[] priv, String pub) {
            this.privateDer = priv;
            this.publicB64 = pub;
        }
    }

    private AdbKeys() {}

    public static KeyFiles generate() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
        return new KeyFiles(kp.getPrivate().getEncoded(), encodePublic(pub));
    }

    private static String encodePublic(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();

        byte[] modulus = toFixedLE(n, MODULUS_SIZE);

        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.mod(two32);
        BigInteger inv = n0.modInverse(two32);
        BigInteger n0inv = two32.subtract(inv).mod(two32);

        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        byte[] rrBytes = toFixedLE(rr, MODULUS_SIZE);

        byte[] out = new byte[ENCODED_SIZE];
        int off = 0;
        off = putLE32(out, off, 7);
        off = putLE32(out, off, n0inv.longValue());
        System.arraycopy(modulus, 0, out, off, MODULUS_SIZE);
        off += MODULUS_SIZE;
        System.arraycopy(rrBytes, 0, out, off, MODULUS_SIZE);
        off += MODULUS_SIZE;
        putLE32(out, off, e.longValue());

        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static int putLE32(byte[] buf, int offset, long value) {
        long x = value & 0xFFFFFFFFL;
        for (int i = 0; i < 4; i++) {
            buf[offset + i] = (byte) (x & 0xFF);
            x >>>= 8;
        }
        return offset + 4;
    }

    private static byte[] toFixedLE(BigInteger v, int size) {
        byte[] be = v.toByteArray();
        byte[] src;
        if (be.length > 1 && be[0] == 0) {
            src = new byte[be.length - 1];
            System.arraycopy(be, 1, src, 0, src.length);
        } else {
            src = be;
        }
        byte[] out = new byte[size];
        int len = Math.min(src.length, size);
        for (int i = 0; i < len; i++) {
            out[i] = src[src.length - 1 - i];
        }
        return out;
    }

    public static String pubFileContent(String b64) {
        return b64 + " yg-adbtool\n";
    }
}
