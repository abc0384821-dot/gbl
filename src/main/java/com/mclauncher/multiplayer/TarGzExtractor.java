package com.mclauncher.multiplayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 极简 tar.gz 解压（纯 JDK，零依赖）：只把 {@code keepNames} 里的文件解到目标目录，
 * 其余条目跳过。Terracotta 的发布包里我们只需要 exe 与 VCRUNTIME140.DLL，
 * 不需要把整个包（可能带源码、文档、其它平台二进制）摊到用户磁盘。
 *
 * <p>遵循 POSIX ustar 头：512 字节一块，name[0..100]、size[124..136]（八进制）、
 * typeflag[156]。跳过 GNU 长名（'L'）与 PAX 扩展头（'x'/'g'）。
 *
 * <p><b>安全</b>：所有写出路径做 zip-slip 校验——拒绝含 {@code ..} 段、绝对路径、
 * 盘符路径，并用 {@code normalize + startsWith} 确保最终落点仍在 destDir 内。
 */
public final class TarGzExtractor {

    private static final int BLOCK = 512;
    private static final int NAME_OFF = 0;
    private static final int NAME_LEN = 100;
    private static final int SIZE_OFF = 124;
    private static final int SIZE_LEN = 12;
    private static final int TYPE_OFF = 156;

    private TarGzExtractor() {
    }

    /**
     * 把 tarGz 中名字命中 {@code keepNames} 的普通文件解到 {@code destDir}。
     *
     * @throws IOException 归档损坏、条目越界（zip-slip）或 I/O 失败
     */
    public static void extract(Path tarGz, Path destDir, Set<String> keepNames) throws IOException {
        Path dest = destDir.toAbsolutePath().normalize();
        Files.createDirectories(dest);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(tarGz))) {
            byte[] header = new byte[BLOCK];
            String pendingLongName = null;   // GNU 'L' 头给出的长名，作用于下一个条目
            while (readFully(in, header, BLOCK)) {
                if (isAllZero(header)) {
                    break;                    // 两个全零块 = 归档结束
                }
                String name = pendingLongName != null ? pendingLongName : parseName(header);
                pendingLongName = null;
                long size = parseOctal(header, SIZE_OFF, SIZE_LEN);
                char type = (char) (header[TYPE_OFF] & 0xFF);
                long padded = ((size + BLOCK - 1) / BLOCK) * BLOCK;   // 数据按 512 对齐

                if (type == 'L') {            // GNU 长名：数据就是下一个条目的真名
                    byte[] data = readEntry(in, size, padded);
                    String longName = new String(data, 0, (int) size, StandardCharsets.UTF_8);
                    int nul = longName.indexOf('\0');
                    pendingLongName = nul >= 0 ? longName.substring(0, nul) : longName;
                    continue;
                }
                if (type == 'x' || type == 'g') {   // PAX 扩展头：跳过
                    skipFully(in, padded);
                    continue;
                }

                // 每个条目都校验路径（即便不在 keepNames 里）：恶意 ../、绝对路径一律拒绝，
                // 绝不静默跳过——否则带毒归档会被当成"无害"放过。
                Path target = safeTarget(dest, name);
                boolean isFile = type == '0' || type == '\0';
                if (isFile && keepNames.contains(name) && size <= Integer.MAX_VALUE) {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    byte[] data = readEntry(in, size, padded);
                    try (OutputStream out = Files.newOutputStream(target)) {
                        out.write(data, 0, (int) size);
                    }
                } else {
                    skipFully(in, padded);
                }
            }
        }
    }

    /** zip-slip 防护：拒绝 ../、绝对路径、盘符，并确认最终路径仍在 dest 内。 */
    private static Path safeTarget(Path dest, String name) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("tar 条目名为空");
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf(':') >= 0) {
            throw new IOException("拒绝绝对路径的 tar 条目: " + name);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new IOException("拒绝含 .. 的 tar 条目: " + name);
            }
        }
        Path target = dest.resolve(normalized).normalize();
        if (!target.startsWith(dest)) {
            throw new IOException("tar 条目越出目标目录: " + name);
        }
        return target;
    }

    private static boolean isAllZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String parseName(byte[] header) {
        int len = 0;
        while (len < NAME_LEN && header[NAME_OFF + len] != 0) {
            len++;
        }
        return new String(header, NAME_OFF, len, StandardCharsets.UTF_8);
    }

    /** tar 里的 size 是 ASCII 八进制，前导 0/空格，遇空格或 NUL 结束。 */
    private static long parseOctal(byte[] header, int off, int len) throws IOException {
        long value = 0;
        int digits = 0;
        for (int i = 0; i < len; i++) {
            byte b = header[off + i];
            if (b == 0 || b == ' ') {
                if (digits > 0) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                throw new IOException("tar size 字段非法: " + (char) b);
            }
            value = value * 8 + (b - '0');
            digits++;
        }
        return value;
    }

    /** 读整条数据（size 字节）+ 跳过对齐填充。 */
    private static byte[] readEntry(InputStream in, long size, long padded) throws IOException {
        byte[] data = new byte[(int) size];
        readFullyExact(in, data, (int) size);
        skipFully(in, padded - size);
        return data;
    }

    /** 读满 len 字节；返回 false 表示流提前结束（用于头块的 EOF 判定）。 */
    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) {
                return false;
            }
            off += r;
        }
        return true;
    }

    private static void readFullyExact(InputStream in, byte[] buf, int len) throws IOException {
        if (!readFully(in, buf, len)) {
            throw new IOException("tar 数据提前结束");
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] scratch = new byte[8192];
        long remaining = n;
        while (remaining > 0) {
            int r = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (r < 0) {
                throw new IOException("tar 填充数据提前结束");
            }
            remaining -= r;
        }
    }
}
