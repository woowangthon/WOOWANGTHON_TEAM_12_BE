package com.woowangthon.backend.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 같은 자료 알아보기(S-69)의 해시 — 받은 글자의 \r\n · \r 을 \n 으로 바꾸고 앞뒤 공백 · 줄바꿈을 뗀 뒤 UTF-8 로 SHA-256.
 * 그 밖의 차이(가운데 공백 · 글자 하나)는 다른 자료다. 비교에만 쓰고 저장하는 text 는 받은 그대로다.
 */
public final class SourceHash {

    private SourceHash() {
    }

    public static String of(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 은 모든 JRE 가 가진다
        }
    }
}
