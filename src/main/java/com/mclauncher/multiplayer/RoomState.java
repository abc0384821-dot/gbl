package com.mclauncher.multiplayer;

import com.mclauncher.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 联机引擎状态模型（纯数据，无线程、无 IO）。
 *
 * <p>引擎 {@code GET /state} 返回的 JSON 形状：
 * <pre>{@code
 * {"state":"host-ok","index":3,"room":"U/ABCD-EFGH-IJKL-MNOP",
 *  "profiles":{"target_tuple":"...","easytier_version":"..."},
 *  "difficulty":"...","url":"...","exception_type":0}
 * }</pre>
 *
 * <p>本文件把三个公开类型都收在 {@link RoomState} 里（一个 .java 只能有一个 public 顶层类型）。
 * 外部引用写作 {@code RoomState.State} / {@code RoomState.StateSnapshot} / {@code RoomState.Profiles}。
 * 所有解析都宽容降级：字段缺失、类型不对、坏 JSON 一律给出可安全使用的默认值，绝不抛到调用线程。
 */
public final class RoomState {

    private RoomState() {
        // 纯工具类
    }

    /** 引擎状态枚举。{@link #parse(String)} 同时吃 kebab-case 与 camelCase。 */
    public enum State {
        WAITING,
        HOST_SCANNING,
        HOST_STARTING,
        HOST_OK,
        GUEST_CONNECTING,
        GUEST_STARTING,
        GUEST_OK,
        EXCEPTION,
        UNKNOWN;

        /**
         * 字符串 → 状态。{@code "host-scanning"} / {@code "hostScanning"} / {@code "host_scanning"}
         * 都映射到 {@link #HOST_SCANNING}；null / 空 / 未知串映射到 {@link #UNKNOWN}。
         */
        public static State parse(String s) {
            if (s == null || s.isBlank()) {
                return UNKNOWN;
            }
            String flat = s.trim().toLowerCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_').replace("_", "");
            for (State v : values()) {
                String name = v.name().toLowerCase(Locale.ROOT).replace("_", "");
                if (name.equals(flat)) {
                    return v;
                }
            }
            return UNKNOWN;
        }
    }

    /** profiles 子对象；{@code raw} 保留原始 Map，供体检卡粗判节点/中继信息。 */
    public record Profiles(String targetTuple, String easytierVersion, Object raw) {
    }

    /** 一份状态快照。{@code state} 保留引擎原始串，便于日志/诊断；枚举用 {@link #stateEnum()}。 */
    public record StateSnapshot(String state, long index, String room, Profiles profiles,
                                String difficulty, String url, int exceptionType,
                                List<String> players) {

        public State stateEnum() {
            return State.parse(state);
        }

        /** 活跃态：非 waiting / exception / unknown。供上层决定是否值得展示进度。 */
        public boolean isActive() {
            State s = stateEnum();
            return s != State.WAITING && s != State.EXCEPTION && s != State.UNKNOWN;
        }
    }

    /** 解析失败 / 尚未连接时的安全哨兵，任何调用方都可以直接使用而不判空。 */
    public static final StateSnapshot UNKNOWN_SNAPSHOT =
            new StateSnapshot("UNKNOWN", -1L, "", null, "", "", 0, List.of());

    /** 与 {@link State#parse(String)} 等价的便捷入口。 */
    public static State parse(String s) {
        return State.parse(s);
    }

    /**
     * 把 {@code /state} 的响应体解析成快照。坏 JSON / 非对象 / 字段缺失都不抛，
     * 返回 {@link #UNKNOWN_SNAPSHOT} 或按默认值补齐的快照。
     */
    public static StateSnapshot fromJson(String json) {
        if (json == null || json.isBlank()) {
            return UNKNOWN_SNAPSHOT;
        }
        try {
            Object parsed = Json.parse(json);
            if (!(parsed instanceof Map<?, ?> map)) {
                return UNKNOWN_SNAPSHOT;
            }
            String state = str(map.get("state"));
            if (state == null || state.isBlank()) {
                state = "UNKNOWN";
            }
            long index = longOf(map.get("index"), -1L);
            String room = str(map.get("room"));
            String difficulty = str(map.get("difficulty"));
            String url = str(map.get("url"));
            Object rawEx = map.get("exception_type") != null
                    ? map.get("exception_type") : map.get("exceptionType");
            int ex = (int) longOf(rawEx, 0L);
            return new StateSnapshot(
                    state,
                    index,
                    room == null ? "" : room,
                    parseProfiles(map.get("profiles")),
                    difficulty == null ? "" : difficulty,
                    url == null ? "" : url,
                    ex,
                    parsePlayers(map));
        } catch (RuntimeException e) {
            return UNKNOWN_SNAPSHOT;
        }
    }

    /**
     * 宽容解析玩家列表：优先 players，其次 peers / members / clients / users；
     * 支持字符串数组与对象数组（name / player / nickname / user 键，大小写不敏感）。
     * 坏数据、嵌套错类型、超长文本一律安全降级为空表，绝不抛到调用线程。
     */
    private static List<String> parsePlayers(Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String key : new String[]{"players", "peers", "members", "clients", "users"}) {
            Object raw = map.get(key);
            collectPlayers(raw, seen);
            if (seen.size() >= 64) {
                break;
            }
        }
        return seen.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(seen));
    }

    private static void collectPlayers(Object raw, Set<String> out) {
        if (raw instanceof String s) {
            addPlayer(s, out);
            return;
        }
        if (!(raw instanceof Iterable<?> items)) {
            return;
        }
        for (Object item : items) {
            if (out.size() >= 64) {
                return;
            }
            if (item instanceof String s) {
                addPlayer(s, out);
                continue;
            }
            if (!(item instanceof Map<?, ?> playerMap)) {
                continue;
            }
            for (Map.Entry<?, ?> e : playerMap.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    continue;
                }
                String lower = k.toLowerCase(Locale.ROOT).replace("_", "");
                if ("name".equals(lower) || "player".equals(lower)
                        || "nickname".equals(lower) || "user".equals(lower)
                        || "playername".equals(lower)) {
                    addPlayer(str(e.getValue()), out);
                    break;
                }
            }
        }
    }

    private static void addPlayer(String value, Set<String> out) {
        if (value == null) {
            return;
        }
        String name = value.trim();
        if (!name.isEmpty() && name.length() <= 64) {
            out.add(name);
        }
    }

    private static Profiles parseProfiles(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            String tuple = str(map.get("target_tuple"));
            if (tuple == null) {
                tuple = str(map.get("targetTuple"));
            }
            String ver = str(map.get("easytier_version"));
            if (ver == null) {
                ver = str(map.get("easytierVersion"));
            }
            return new Profiles(tuple == null ? "" : tuple, ver == null ? "" : ver, map);
        }
        if (raw != null) {
            // 引擎偶尔把 profiles 塞成字符串，仍保留原始值供诊断，不丢信息
            return new Profiles("", "", raw);
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
        if (o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        return null;
    }

    private static long longOf(Object o, long fallback) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到 fallback
            }
        }
        return fallback;
    }
}
