package com.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 账号持久化（v0.5.2 引入）。
 *
 * <p>本期只实装「离线」一种类型；{@link Type} 里给外置(little skin)/微软留了位置，
 * 但那两种的登录流程本期不做，界面上也不暴露——加账号时只有「离线」这一种可建。
 *
 * <p>配置落在 {@code ~/.gbl/accounts.json}，和 {@link SettingsStore}（config.json）同目录。
 * 读写风格与 SettingsStore 一致：带 schemaVersion、缺字段用默认值、坏 JSON 降级为空、
 * 写盘走「临时文件 + 原子改名」。
 */
public final class AccountStore {

    public static final int SCHEMA_VERSION = 1;

    /** 账号类型。OFFLINE 离线；LITTLESKIN 外置（little skin）用户名 + 密码；MICROSOFT 微软 OAuth。 */
    public enum Type { OFFLINE, LITTLESKIN, MICROSOFT }

    private static final Path FILE =
            Paths.get(System.getProperty("user.home"), ".gbl", "accounts.json");

    /** 一条账号。OFFLINE 仅需 name；LITTLESKIN / MICROSOFT 还需 uuid / accessToken（真实登录凭据）。 */
    public static final class Account {
        public final String id;
        public final Type type;
        public final String name;
        /** 外置 / 微软账号的真实 uuid（带横线），离线账号为空。 */
        public final String uuid;
        /** 外置 / 微软账号的登录令牌，离线账号为空。 */
        public final String accessToken;
        /** 微软账号的刷新令牌（静默续期用）；非微软账号为空。 */
        public final String refreshToken;
        /** 微软账号的 MC 访问令牌过期时刻（epoch 毫秒），0 表示未知。 */
        public final long expiresAt;

        public Account(String id, Type type, String name, String uuid, String accessToken,
                       String refreshToken, long expiresAt) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.uuid = uuid == null ? "" : uuid;
            this.accessToken = accessToken == null ? "" : accessToken;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.expiresAt = expiresAt;
        }

        /** 返回一份换过令牌的副本（微软刷新后更新凭据用），其余字段不变。 */
        public Account withToken(String accessToken, String refreshToken, long expiresAt) {
            return new Account(id, type, name, uuid, accessToken, refreshToken, expiresAt);
        }
    }

    private final List<Account> accounts;
    private final String currentId;

    private AccountStore(List<Account> accounts, String currentId) {
        this.accounts = accounts;
        this.currentId = currentId;
    }

    public static AccountStore empty() {
        return new AccountStore(new ArrayList<>(), null);
    }

    /** 读配置；文件不存在/读不到/坏 JSON 都返回空（首次运行本就无配置，是正常路径）。 */
    public static AccountStore load() {
        Map<String, Object> map = readObject();
        if (map == null) {
            return empty();
        }
        List<Account> list = new ArrayList<>();
        Object rawAccounts = map.get("accounts");
        if (rawAccounts instanceof List) {
            for (Object o : (List<?>) rawAccounts) {
                if (!(o instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> a = (Map<String, Object>) o;
                String id = asString(a.get("id"));
                String name = asString(a.get("name"));
                if (id == null || name == null) {
                    continue;   // 缺关键字段的条目丢掉，别让坏数据带崩整个列表
                }
                // type 字段缺失或不是已知值都按 OFFLINE 兜（兼容早期只有离线的存档）
                Type t = Type.OFFLINE;
                String typeStr = asString(a.get("type"));
                if ("LITTLESKIN".equals(typeStr)) {
                    t = Type.LITTLESKIN;
                } else if ("MICROSOFT".equals(typeStr)) {
                    t = Type.MICROSOFT;
                }
                String uuid = asString(a.get("uuid"));
                String token = asString(a.get("accessToken"));
                String refresh = asString(a.get("refreshToken"));
                long exp = 0;
                Object rawExp = a.get("expiresAt");
                if (rawExp instanceof Number) {
                    exp = ((Number) rawExp).longValue();
                }
                list.add(new Account(id, t, name,
                        uuid == null ? "" : uuid, token == null ? "" : token,
                        refresh == null ? "" : refresh, exp));
            }
        }
        String cur = asString(map.get("currentId"));
        if (cur != null) {
            final String wanted = cur;
            boolean ok = list.stream().anyMatch(x -> x.id.equals(wanted));
            if (!ok) {
                cur = null;   // 当前 id 指向已删的账号就降级成 null
            }
        }
        return new AccountStore(list, cur);
    }

    public List<Account> accounts() {
        return accounts;
    }

    public Account current() {
        if (currentId == null) {
            return null;
        }
        for (Account a : accounts) {
            if (a.id.equals(currentId)) {
                return a;
            }
        }
        return null;
    }

    /** 新增一条账号，返回新状态。加第一个账号时自动设为当前，省一次「设为当前」点击。 */
    public AccountStore withAdded(Account acc) {
        List<Account> list = new ArrayList<>(accounts);
        list.add(acc);
        String cur = (currentId == null && list.size() == 1) ? acc.id : currentId;
        return new AccountStore(list, cur);
    }

    public AccountStore withRemoved(String id) {
        List<Account> list = new ArrayList<>();
        for (Account a : accounts) {
            if (!a.id.equals(id)) {
                list.add(a);
            }
        }
        // 删掉的就是当前账号，才把 currentId 清空；删别的账号不影响当前选择
        String cur = (currentId != null && currentId.equals(id)) ? null : currentId;
        return new AccountStore(list, cur);
    }

    public AccountStore withCurrent(String id) {
        boolean ok = accounts.stream().anyMatch(a -> a.id.equals(id));
        return new AccountStore(accounts, ok ? id : currentId);
    }

    /** 把某账号的令牌换成刷新后的新凭据（微软续期后落盘用），其余字段不变。 */
    public AccountStore withTokenRefreshed(String id, String accessToken,
                                           String refreshToken, long expiresAt) {
        List<Account> list = new ArrayList<>();
        for (Account a : accounts) {
            if (a.id.equals(id)) {
                list.add(a.withToken(accessToken, refreshToken, expiresAt));
            } else {
                list.add(a);
            }
        }
        return new AccountStore(list, currentId);
    }

    public boolean save() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", SCHEMA_VERSION);
        List<Object> list = new ArrayList<>();
        for (Account a : accounts) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.id);
            o.put("type", a.type.name());
            o.put("name", a.name);
            o.put("uuid", a.uuid);
            o.put("accessToken", a.accessToken);
            o.put("refreshToken", a.refreshToken);
            o.put("expiresAt", a.expiresAt);
            list.add(o);
        }
        map.put("accounts", list);
        map.put("currentId", currentId);
        String text = Json.write(map);
        Path dir = FILE.toAbsolutePath().getParent();
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[accounts] 建配置目录失败: " + ex);
            return false;
        }
        // 写临时文件再整体改名：别用「截断 + 边写」直写目标文件，
        // 那样别处读配置可能读到写到一半的半截 JSON。
        Path tmp = dir.resolve(FILE.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, FILE, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException fallback) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // 删不掉就留着，下次写同名覆盖
            }
            System.err.println("[accounts] 保存失败: " + ex);
            return false;
        }
    }

    /** 建一条离线账号（随机 id）。name 由调用方保证非空。 */
    public static Account newOffline(String name) {
        return new Account(UUID.randomUUID().toString(), Type.OFFLINE, name.trim(), "", "", "", 0);
    }

    /** 建一条外置（little skin）账号：用登录拿到的真实 name / uuid / accessToken。 */
    public static Account newLittleSkin(String name, String uuid, String accessToken) {
        return new Account(UUID.randomUUID().toString(), Type.LITTLESKIN, name.trim(),
                uuid == null ? "" : uuid, accessToken == null ? "" : accessToken, "", 0);
    }

    /** 建一条微软账号：用登录链拿到的真实 name / uuid / accessToken + 续期用的 refreshToken。 */
    public static Account newMicrosoft(String name, String uuid, String accessToken,
                                       String refreshToken, long expiresAt) {
        return new Account(UUID.randomUUID().toString(), Type.MICROSOFT, name.trim(),
                uuid == null ? "" : uuid, accessToken == null ? "" : accessToken,
                refreshToken == null ? "" : refreshToken, expiresAt);
    }

    private static String asString(Object o) {
        if (!(o instanceof String)) {
            return null;
        }
        String s = ((String) o).trim();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject() {
        String text;
        try {
            if (!Files.isRegularFile(FILE)) {
                return new LinkedHashMap<>();
            }
            text = Files.readString(FILE, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[accounts] 读取失败，按空兜: " + ex);
            return null;
        }
        try {
            Object parsed = Json.parse(text);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            return null;
        } catch (RuntimeException ex) {
            System.err.println("[accounts] 配置不是合法 JSON，按空兜: " + ex);
            return null;
        }
    }
}
