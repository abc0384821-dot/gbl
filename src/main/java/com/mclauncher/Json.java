package com.mclauncher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写，零依赖。
 *
 * <p>v0.4.7 之前这份解析器是 {@link SettingsStore} 的私有内部类。启动流程要读
 * {@code version.json}——里面有 {@code libraries[]} 这种嵌套数组、每条还带
 * {@code downloads.artifact.path} 两层结构，正则抠不出来，只能真解析；
 * 而配置那套也已经在跑，就把它提上来两人共用，别在项目里养两份解析器。
 *
 * <p>能力边界说清楚（**不做通用库**）：只支持顶层对象（配置和 version.json 都是这形状），
 * 值覆盖对象 / 数组 / 字符串 / 数字 / 布尔 / null。数字一律按 {@code Double} 出——
 * 配置里没整数，version.json 里要的整数都在下标上，用 {@code intValue()} 转。
 */
public final class Json {

    private Json() {
        // 静态工具类
    }

    /** 解析一份 JSON 文本；格式不对抛 {@link IllegalArgumentException}。 */
    public static Object parse(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容");
        }
        return value;
    }

    /** 把一个顶层对象写回 JSON 文本。 */
    public static String write(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, entry.getKey());
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        return sb.append('}').toString();
    }

    /**
     * **必须递归**：嵌套的对象 / 数组要接着往下写。
     *
     * <p>v0.4.7 在这栽过：第一版 {@code writeValue} 只会把值塞进引号里，
     * 落盘的完整 version.json 里 {@code libraries} 变成一条三万字符的**字符串**——
     * 于是第二次启动读缓存读到的是个字符串不是数组，缺 114 个库、游戏起不来，
     * 而缓存文件看着「挺像那么回事」。
     */
    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list);
        } else {
            quote(sb, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }

    private static void quote(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- 解析

    private static final class Reader {
        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object value() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", Boolean.TRUE);
                case 'f' -> word("false", Boolean.FALSE);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;   // {
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw new IllegalArgumentException("JSON 缺少 ':'");
                }
                pos++;
                map.put(key, value());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("JSON 对象未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 对象里出现多余的字符 " + c);
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;   // [
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("JSON 数组未闭合");
                }
                char c = text.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 数组里出现多余的字符 " + c);
                }
            }
        }

        private String string() {
            if (atEnd() || text.charAt(pos) != '"') {
                throw new IllegalArgumentException("JSON 期望字符串");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new IllegalArgumentException("JSON 转义符后断了");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append("\\");
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw new IllegalArgumentException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("JSON 非法转义 \\" + esc);
                }
            }
        }

        private Object word(String literal, Object result) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalArgumentException("JSON 非法字面量，位置 " + pos);
            }
            pos += literal.length();
            return result;
        }

        private Object number() {
            int start = pos;
            while (pos < text.length() && "+-.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String token = text.substring(start, pos);
            if (token.isEmpty()) {
                throw new IllegalArgumentException("JSON 非法值，位置 " + start);
            }
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("JSON 数字解析失败: " + token);
            }
        }
    }
}
