package com.mclauncher.ui;

import javafx.application.Platform;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.2.6：全局弹层登记表。
 *
 * <p>跨主菜单切换时，弹层「隐藏但不销毁、切回原样恢复」的唯一判据是
 * <b>用户是否真的关闭过它</b>——不再使用任何「关闭世代计数」。任何弹层
 * {@code show} 时登记、{@code close} 时注销；切走只把仍登记且仍打开的层
 * {@code setVisible(false)/setManaged(false)}，切回对仍在登记表里的层
 * {@code setVisible(true)/setManaged(true)} 并调用 {@link Controller#restoreState()}。
 * 已注销（用户真关闭）的层自然不会被恢复。
 *
 * <p>线程纪律：所有改动 JavaFX 树的调用必须发生在 FX 线程；本类对写操作做了显式校验，
 * 读操作（{@link #size()} / {@link #summaryForPage(int)}）允许在测试线程调用。
 */
public final class OverlayRegistry {

    /** 弹层控制器：保存/恢复输入内容与状态，并如实报告逻辑上是否仍打开。 */
    public interface Controller {

        /** 切走前保存内容（输入文字、错误文案、计时起点等）。 */
        default void saveState() {
        }

        /** 切回后恢复内容；本方法不得依赖节点可见性（可见性由登记表接管）。 */
        default void restoreState() {
        }

        /** 逻辑上是否仍打开——唯一判据，绝不读取被登记表改过的 visible 值。 */
        boolean isOpen();
    }

    /** 无内容快照的简单开关控制器（ShellView 的确认弹窗/安装弹窗/启动遮罩共用）。 */
    public static final class FlagController implements Controller {

        private boolean open;

        public FlagController(boolean open) {
            this.open = open;
        }

        public void setOpen(boolean open) {
            this.open = open;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    /** 一条登记记录：(pageOrdinal, name, layer, controller)。 */
    public static final class Entry {

        private final int pageOrdinal;
        private final String name;
        private final Node layer;
        private final Controller controller;
        private boolean hiddenBySwitch;

        Entry(int pageOrdinal, String name, Node layer, Controller controller) {
            this.pageOrdinal = pageOrdinal;
            this.name = name;
            this.layer = layer;
            this.controller = controller;
        }

        public int pageOrdinal() {
            return pageOrdinal;
        }

        public String name() {
            return name;
        }

        public Node layer() {
            return layer;
        }

        public Controller controller() {
            return controller;
        }

        public boolean hiddenBySwitch() {
            return hiddenBySwitch;
        }
    }

    /** 用名字做 key：同一个 layer 同时只登记一次，重复 show 幂等覆盖。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private boolean frozen;

    /** 登记一个弹层。同一 name 重复登记覆盖旧记录（同时清掉旧的隐藏标记）。 */
    public void register(int pageOrdinal, String name, Node layer, Controller controller) {
        requireFx();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("overlay name is blank");
        }
        if (layer == null || controller == null) {
            throw new IllegalArgumentException("overlay layer/controller is null: " + name);
        }
        entries.put(name, new Entry(pageOrdinal, name, layer, controller));
    }

    /** 注销一个弹层（用户真正关闭时调用）。注销后切回绝不复活。 */
    public void unregister(String name) {
        requireFx();
        if (name != null) {
            entries.remove(name);
        }
    }

    public boolean contains(String name) {
        return name != null && entries.containsKey(name);
    }

    /**
     * 切走一页：只隐藏「该页 + 仍打开 + 当前可见」的弹层，保存内容，不 dispose、不清 children。
     * 同时进入冻结态，避免 onShown 内部刷新的状态在恢复前后再次改动弹层可见性。
     */
    public void hidePage(int pageOrdinal) {
        requireFx();
        for (Entry e : new ArrayList<>(entries.values())) {
            if (e.pageOrdinal == pageOrdinal && e.controller.isOpen() && e.layer.isVisible()) {
                e.controller.saveState();
                e.layer.setVisible(false);
                e.layer.setManaged(false);
                e.hiddenBySwitch = true;
            }
        }
        frozen = true;
    }

    /**
     * 切回一页：对仍在登记表且逻辑上仍打开的层恢复可见性并还原内容。
     * 用户已关闭（已注销）或逻辑上已结束（isOpen()==false）的层不复活。
     */
    public void restorePage(int pageOrdinal) {
        requireFx();
        for (Entry e : new ArrayList<>(entries.values())) {
            if (e.pageOrdinal != pageOrdinal || !e.hiddenBySwitch) {
                continue;
            }
            e.hiddenBySwitch = false;
            if (e.controller.isOpen()) {
                e.layer.setVisible(true);
                e.layer.setManaged(true);
                e.controller.restoreState();
            }
        }
        frozen = false;
    }

    /** 冻结/解冻：冻结期间业务刷新不得改动弹层可见性（MultiplayerPage 会查这个标志）。 */
    public void setFrozen(boolean value) {
        frozen = value;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /** 当前登记表里的层数量。 */
    public int size() {
        return entries.size();
    }

    /** 某页当前被切换隐藏的层数量（兼容旧 QA 钩子）。 */
    public int hiddenCountForPage(int pageOrdinal) {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.pageOrdinal == pageOrdinal && e.hiddenBySwitch) {
                n++;
            }
        }
        return n;
    }

    public boolean hasHiddenForPage(int pageOrdinal) {
        return hiddenCountForPage(pageOrdinal) > 0;
    }

    /** 某页登记层摘要：{@code name:open/hidden | ...}，供真机探针点名断言。 */
    public String summaryForPage(int pageOrdinal) {
        List<String> parts = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.pageOrdinal != pageOrdinal) {
                continue;
            }
            String state;
            if (e.controller.isOpen()) {
                state = e.hiddenBySwitch ? "hidden" : "open";
            } else {
                state = "closed";
            }
            parts.add(e.name + ":" + state);
        }
        return String.join("|", parts);
    }

    /** 清空（自测/重挂树时用；产品流程不调用）。 */
    public void clear() {
        requireFx();
        entries.clear();
        frozen = false;
    }

    private static void requireFx() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("OverlayRegistry must be mutated on the FX thread");
        }
    }
}
