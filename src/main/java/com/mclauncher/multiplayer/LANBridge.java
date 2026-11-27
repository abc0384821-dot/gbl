package com.mclauncher.multiplayer;

import com.mclauncher.multiplayer.RoomState.State;

/**
 * 局域网开放提示与房间元数据（P2 预留点）。
 *
 * <p>纯状态→文案映射：房间已经建好/加进去之后，MC 客户端还得在游戏内手动
 * 「对局域网开放」，房间才会出现在多人列表。本类只负责给出提示，不弹窗、不碰 UI 线程。
 */
public final class LANBridge {

    /**
     * 1.1.9：GBL 无法从系统层真实读到你是否在 MC 游戏内点了「对局域网开放」，
     * 因此这里只给中性引导，绝不断言「你没开」。已开的玩家直接忽略即可，
     * 也不会因为 CGNAT/中继反复弹「请开启对局域网开放」的警告。
     */
    private static final String LAN_HINT =
            "若尚未在游戏内开启：选项 → 游戏 → 对局域网开放；已经开启请忽略本提示，房间会自动出现在多人列表";

    private static final LANBridge INSTANCE = new LANBridge();

    public static LANBridge get() {
        return INSTANCE;
    }

    /** HOST_OK / GUEST_OK 才需要玩家去游戏内开局域网。 */
    public static boolean needsLanOpen(State state) {
        return state == State.HOST_OK || state == State.GUEST_OK;
    }

    /** 给 UI 的局域网开放引导文案。 */
    public static String lanHint(State state) {
        return LAN_HINT;
    }

    /** P2 预留：刷新房间元数据（当前无操作）。 */
    public void refresh() {
        // no-op
    }

    /** P2 预留：房间 MOTD（当前为空串）。 */
    public String motd() {
        return "";
    }

    /** P2 预留：房间延迟（当前为 0）。 */
    public int latency() {
        return 0;
    }
}
