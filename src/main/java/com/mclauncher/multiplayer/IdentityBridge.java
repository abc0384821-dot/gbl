package com.mclauncher.multiplayer;

import com.mclauncher.AccountStore;

/**
 * 把启动器现有账号桥接成联机引擎需要的身份（player / uuid / accessToken）。
 *
 * <p><b>不改 AccountStore</b>：账号文件由 {@link AccountStore#load()} 读，当前账号用
 * {@link AccountStore#current()}（源码确认过，没有 get() 这个静态入口）。
 * 离线账号只给名字；外置/微软账号给真实 uuid 与 accessToken。
 * token 是否过期<b>不在本类刷新</b>——那属于现有 AuthlibInjector 的职责。
 * 读账号失败一律安全降级为 null / 空串，绝不 NPE 到调用线程。
 */
public final class IdentityBridge {

    private IdentityBridge() {
        // 纯工具类
    }

    /** 联机身份快照。type 直接复用 AccountStore.Type，避免重复定义枚举。 */
    public record Identity(String playerName, String uuid, String accessToken,
                           AccountStore.Type type) {
    }

    /** 当前账号对应的联机身份；无账号 / 读失败返回 null。 */
    public static Identity current() {
        try {
            AccountStore store = AccountStore.load();
            AccountStore.Account account = (store == null) ? null : store.current();
            if (account == null || account.name == null || account.name.trim().isEmpty()) {
                return null;
            }
            String name = account.name.trim();
            String uuid = account.uuid == null ? "" : account.uuid;
            String token = account.accessToken == null ? "" : account.accessToken;
            AccountStore.Type type = account.type == null ? AccountStore.Type.OFFLINE : account.type;
            if (type == AccountStore.Type.OFFLINE) {
                // 离线账号只报名字，不伪造 uuid / 令牌
                uuid = "";
                token = "";
            }
            return new Identity(name, uuid, token, type);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 联机引擎 player 参数；无账号返回空串。 */
    public static String playerName() {
        Identity identity = current();
        return identity == null ? "" : identity.playerName();
    }

    /** 是否外置账号（LITTLESKIN / MICROSOFT）。 */
    public static boolean isExternal() {
        Identity identity = current();
        return identity != null && identity.type() != AccountStore.Type.OFFLINE;
    }

    /** UI 展示名：离线只给名字，外置/微软附一个简短来源后缀（不用于 player 参数）。 */
    public static String displayName() {
        Identity identity = current();
        if (identity == null) {
            return "";
        }
        if (identity.type() == AccountStore.Type.OFFLINE) {
            return identity.playerName();
        }
        String source = identity.type() == AccountStore.Type.LITTLESKIN ? "外置" : "微软";
        return identity.playerName() + "（" + source + "）";
    }
}
