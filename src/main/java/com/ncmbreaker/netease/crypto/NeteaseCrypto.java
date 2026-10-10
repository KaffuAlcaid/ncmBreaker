package com.ncmbreaker.netease.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

// Protocol adapted from api-enhanced; attribution is in META-INF/licenses/netease-api.txt.
public final class NeteaseCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final byte[] EAPI_KEY = bytes("e82ckenh8dichen8");
    private static final byte[] WEAPI_KEY = bytes("0CoJUm6Qyw8W8jud");
    private static final byte[] WEAPI_IV = bytes("0102030405060708");
    private static final String PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";

    private NeteaseCrypto() {
    }

    public static String eapi(String path, String json) throws GeneralSecurityException {
        var digest = MessageDigest.getInstance("MD5").digest(bytes("nobody" + path + "use" + json + "md5forencrypt"));
        var plaintext = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + HexFormat.of().formatHex(digest);
        var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(EAPI_KEY, "AES"));
        return "params=" + HexFormat.of().withUpperCase().formatHex(cipher.doFinal(bytes(plaintext)));
    }

    public static String weapi(String json) throws GeneralSecurityException {
        var secret = new StringBuilder(16);
        for (var index = 0; index < 16; index++) {
            secret.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        var first = aesCbc(bytes(json), WEAPI_KEY);
        var params = aesCbc(bytes(first), bytes(secret.toString()));
        var key = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        var rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(Cipher.ENCRYPT_MODE, key);
        var encryptedKey = rsa.doFinal(bytes(secret.reverse().toString()));
        return "params=" + URLEncoder.encode(params, StandardCharsets.UTF_8)
                + "&encSecKey=" + HexFormat.of().formatHex(encryptedKey);
    }

    private static String aesCbc(byte[] plaintext, byte[] key) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(WEAPI_IV));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
