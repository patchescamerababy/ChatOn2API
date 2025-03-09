package utils;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BearerTokenGenerator {
    private static final byte[] __ = System.getenv("KEY_A").getBytes(StandardCharsets.UTF_8);
    private static final byte[] ___ = System.getenv("KEY_B").getBytes(StandardCharsets.UTF_8);
    public static final String UA = System.getenv("User-Agent");
    public static String GetBearer(byte[] ____, String _____, String ______, String _______) {
        String ________ = _______ + new String(new byte[]{58}) + _____ + new String(new byte[]{58}) + ______ + new String(new byte[]{10});
        byte[] _________ = ________.getBytes(StandardCharsets.UTF_8);
        byte[] __________ = connect(_________, ____);
        byte[] ___________ = signature(___, __________);
        String ____________ = Base64.getEncoder().encodeToString(___________);
        String _____________ = Base64.getEncoder().encodeToString(__);
        return new String(new byte[]{66, 101, 97, 114, 101, 114, 32}) + _____________ + "." + ____________;
    }

    public static byte[] signature(byte[] ____, byte[] _____) {
        try {
            Mac ______ = Mac.getInstance(new String(new byte[]{72, 109, 97, 99, 83, 72, 65, 50, 53, 54}));
            SecretKeySpec _______ = new SecretKeySpec(____, new String(new byte[]{72, 109, 97, 99, 83, 72, 65, 50, 53, 54}));
            ______.init(_______);
            return ______.doFinal(_____);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] connect(byte[] __________________, byte[] ___________________) {
        byte[] ____________________ = new byte[__________________.length + ___________________.length];
        System.arraycopy(__________________, 0, ____________________, 0, __________________.length);
        System.arraycopy(___________________, 0, ____________________, __________________.length, ___________________.length);
        return ____________________;
    }
}
