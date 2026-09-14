package com.paicli.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件内容指纹：以内容 sha256 判断文件是否变更（mtime 会骗人，hash 不会）。
 */
final class FileHashes {

    private FileHashes() {
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
        byte[] bytes = Files.readAllBytes(file);
        byte[] hashed = digest.digest(bytes);
        StringBuilder sb = new StringBuilder(hashed.length * 2);
        for (byte b : hashed) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 读取失败的文件按无 hash 处理，调用方可据此判定过期。
     */
    static String sha256HexQuietly(Path file) {
        try {
            return sha256Hex(file);
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }
}
