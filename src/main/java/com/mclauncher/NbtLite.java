package com.mclauncher;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 极简<strong>只读</strong> NBT 解析器，只为读世界存档 {@code level.dat} 里的版本服务。
 *
 * <p>{@code level.dat} 是 gzip 压缩的大端 NBT。这里用 {@link GZIPInputStream} +
 * {@link DataInputStream}（DataInputStream 本身就是大端）把根 Compound 解析成
 * {@link Map}，然后按名字取 {@code Data → Version → Name / Id}。
 *
 * <p>保护措施（坏文件不能吃内存 / 卡死）：
 * <ul>
 *   <li>压缩文件大小上限 16 MiB；</li>
 *   <li>解压后读取总字节上限 64 MiB（{@link CountingStream}），防 gzip 炸弹；</li>
 *   <li>Compound / List 嵌套深度上限 32；</li>
 *   <li>数组 / List 长度上限 1 Mi 个元素；</li>
 *   <li>字符串走 {@link DataInputStream#readUTF()}，自带 65535 字节上限。</li>
 * </ul>
 * 支持全部 13 种 tag 类型（0–12）的 payload 读取，因此任意<strong>合法</strong>嵌套结构
 * 都能被正确跳过而不整体失败；只有出现规范外的 type id 才抛 {@link IOException}。
 * 所有异常都由调用方（{@link ContentStore#worldVersion}）吞掉回退，绝不往外漏运行时异常。
 */
public final class NbtLite {

    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ARRAY_LEN = 1 << 20;

    private NbtLite() {
        // 静态工具类，别让人 new 出来
    }

    /**
     * 读 {@code level.dat} → {@code Data.Version.Name}（如 {@code 1.20.1}），
     * 没有则 {@code Data.Version.Id} 转字符串，再没有返回 null。
     * 文件不存在 / 过大返回 null；解压或解析失败抛 {@link IOException}，由调用方吞掉回退。
     */
    public static String worldVersion(Path levelDat) throws IOException {
        if (levelDat == null || !Files.isRegularFile(levelDat)) {
            return null;
        }
        if (Files.size(levelDat) > MAX_FILE_BYTES) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new CountingStream(new GZIPInputStream(Files.newInputStream(levelDat)),
                        MAX_UNCOMPRESSED_BYTES)))) {
            int rootType = in.readUnsignedByte();
            if (rootType != 10) {   // 根必须是 TAG_Compound
                return null;
            }
            in.readUTF();           // 根名字（通常空串），读掉即可
            Object root = readCompound(in, 0);
            if (!(root instanceof Map<?, ?> top)) {
                return null;
            }
            Object data = top.get("Data");
            if (!(data instanceof Map<?, ?> dataMap)) {
                return null;
            }
            Object version = dataMap.get("Version");
            if (!(version instanceof Map<?, ?> verMap)) {
                return null;
            }
            Object name = verMap.get("Name");
            if (name instanceof String s && !s.isBlank()) {
                return s;
            }
            Object id = verMap.get("Id");
            if (id instanceof Number n) {
                return Integer.toString(n.intValue());
            }
            return null;
        }
    }

    /** 读一个 Compound，返回 {@code Map<String,Object>}；按名字查找子 tag 用。 */
    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT 嵌套过深");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {   // TAG_End：Compound 结束
                return map;
            }
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    /** 读一个 List：元素类型 + 长度，然后逐个 payload（List 元素无名字）。 */
    private static List<Object> readList(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT 嵌套过深");
        }
        int elemType = in.readUnsignedByte();
        int len = checkedLength(in.readInt());
        List<Object> out = new ArrayList<>(Math.min(len, 1024));
        for (int i = 0; i < len; i++) {
            out.add(readPayload(in, elemType, depth + 1));
        }
        return out;
    }

    /**
     * 按 type 读一个 payload。覆盖 0–12 全部类型，所以不关心内容的嵌套结构
     * 也能被完整跳过；规范外的 type id 视为坏文件抛出（调用方吞掉）。
     */
    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        return switch (type) {
            case 1 -> in.readByte();                                   // TAG_Byte
            case 2 -> in.readShort();                                  // TAG_Short
            case 3 -> in.readInt();                                    // TAG_Int
            case 4 -> in.readLong();                                   // TAG_Long
            case 5 -> in.readFloat();                                  // TAG_Float
            case 6 -> in.readDouble();                                 // TAG_Double
            case 7 -> {                                                // TAG_Byte_Array
                int len = checkedLength(in.readInt());
                byte[] a = new byte[len];
                in.readFully(a);
                yield a;
            }
            case 8 -> in.readUTF();                                    // TAG_String（自带 64K 上限）
            case 9 -> readList(in, depth);                             // TAG_List
            case 10 -> readCompound(in, depth);                        // TAG_Compound
            case 11 -> {                                               // TAG_Int_Array
                int len = checkedLength(in.readInt());
                int[] a = new int[len];
                for (int i = 0; i < len; i++) {
                    a[i] = in.readInt();
                }
                yield a;
            }
            case 12 -> {                                               // TAG_Long_Array
                int len = checkedLength(in.readInt());
                long[] a = new long[len];
                for (int i = 0; i < len; i++) {
                    a[i] = in.readLong();
                }
                yield a;
            }
            default -> throw new IOException("未知 NBT tag 类型 " + type);
        };
    }

    private static int checkedLength(int len) throws IOException {
        if (len < 0 || len > MAX_ARRAY_LEN) {
            throw new IOException("NBT 数组 / List 长度异常: " + len);
        }
        return len;
    }

    /** 统计解压后读取字节数，超过上限即抛，防 gzip 炸弹吃内存。 */
    private static final class CountingStream extends FilterInputStream {
        private final long max;
        private long count;

        CountingStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++count > max) {
                throw new IOException("NBT 解压数据过大");
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count > max) {
                    throw new IOException("NBT 解压数据过大");
                }
            }
            return n;
        }
    }
}
