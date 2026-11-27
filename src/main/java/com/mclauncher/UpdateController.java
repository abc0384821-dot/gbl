package com.mclauncher;

import javafx.application.Platform;

/**
 * 自动更新总控：开应用后异步查一次，发现新版本就走「对话框 → （取消）提示条」流程。
 *
 * <p>UI 表现交给 {@link UpdateUi}（由 {@code ShellView} 实现），本类只管状态机与流程，
 * 不碰任何 JavaFX 节点，避免对 UI 层做反向依赖，也方便单测。
 *
 * <p>流程（参考 HMCL）：中央白卡「发现更新 X」+「更新 / 取消」；点取消后右上角出现深色提示条；
 * 点提示条重新打开对话框；点提示条关闭则本次会话不再提示。点更新走
 * {@link UpdateInstaller} 下载并自动重启，保留旧的 {@code .minecraft}。
 */
public final class UpdateController {

    /** 0.9.9 r2 指纹：更新失败后给用户明确、可读的中文提示。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_099_R2_FAILMSG = "0.9.9 r2 更新失败明确提示";
    static { System.err.println("[update-ctrl] " + FP_099_R2_FAILMSG); }

    /** 0.9.9 r4 指纹：失败原因直达界面（不再只显示一句固定文案）。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_099_R4_FAILMSG = "0.9.9 r4 更新失败原因直达界面";
    static { System.err.println("[update-ctrl] " + FP_099_R4_FAILMSG); }

    /** 1.2.7 指纹：检查阶段失败走非阻断钩子，绝不调用会弹模态框的 showUpdateFailed。 */
    public static final String FP_1_2_7_SILENT_CHECK = "GBL 1.2.7 启动检查失败静默降级为非阻断toast";
    static { System.err.println("[update-ctrl] " + FP_1_2_7_SILENT_CHECK); }

    /** 失败原因最长展示多少个字。太长的技术串对玩家没意义，也不该把对话框撑爆。 */
    private static final int MAX_REASON_CHARS = 240;

    /** ShellView 必须实现的 UI 钩子。 */
    public interface UpdateUi {
        /** 中央白卡对话框：发现更新 X。onUpdate=点更新；onCancel=点取消。 */
        void showUpdateDialog(UpdateInfo info, Runnable onUpdate, Runnable onCancel);

        /** 关闭对话框（若开着）。 */
        void closeUpdateDialog();

        /** 右上角深色提示条：onTipClick=点提示条（重新打开对话框）；onTipClose=关闭（本次会话不再提示）。 */
        void showUpdateTip(UpdateInfo info, Runnable onTipClick, Runnable onTipClose);

        /** 关闭提示条（若开着）。 */
        void closeUpdateTip();

        /** 进度回调：fraction 0..1（传负表示只更新阶段文案），给对话框画进度用。 */
        void updateProgress(double fraction, String stage);

        /**
         * 更新失败：在对话框内显示可读失败提示，用户可再次点「更新」重试。
         *
         * <p>0.9.9 r4：入参带上真实失败原因（安装器抛出的可读中文话术）。保留 default 实现，
         * 避免破坏任何现有实现；实现方拿到空白 reason 时应回退到自己的通用文案。
         */
        default void showUpdateFailed(String reason) {}

        /**
         * 1.2.7：启动自动检查失败的非阻断钩子。
         *
         * <p>绝不能在这里调用会拉模态遮罩的 {@link #showUpdateFailed(String)}——那条路径
         * 只服务「用户主动点更新后的安装失败」。默认空实现，保持向后兼容。
         */
        default void onAutoCheckFailed(String reason) {}
    }

    private final UpdateUi host;
    private UpdateInfo pending;
    private boolean updating;
    private boolean tipDismissedThisSession;

    private UpdateController(UpdateUi host) {
        this.host = host;
    }

    /**
     * 启动一次异步检查（应用启动后调一次）。
     *
     * <p>1.2.7 根因修复：旧版把 {@code host::showUpdateFailed} 当作检查失败回调，
     * ShellView 的实现会 showOverlayLayer(updateDialog) 拉起模态框，断网时用户一启动
     * 就被「这次没更新成功」卡住。现在检查阶段失败只走 {@code onAutoCheckFailed}
     * 非阻断钩子；安装阶段的失败仍走 showUpdateFailed（用户主动触发）。
     */
    public static void startAsyncCheck(UpdateUi host) {
        UpdateController ctrl = new UpdateController(host);
        UpdateChecker.checkAsync(ctrl::onFound, host::onAutoCheckFailed);
    }

    private void onFound(UpdateInfo info) {
        if (updating || tipDismissedThisSession) {
            return;
        }
        pending = info;
        showDialog();
    }

    private void showDialog() {
        host.showUpdateDialog(pending, this::onUpdate, this::onCancel);
    }

    private void onUpdate() {
        if (updating) {
            return;
        }
        updating = true;
        host.closeUpdateTip();
        UpdateInstaller.installAndRestart(pending, new UpdateInstaller.Progress() {
            @Override
            public void onStage(String text) {
                Platform.runLater(() -> host.updateProgress(-1, text));
            }

            @Override
            public void onFraction(double fraction) {
                Platform.runLater(() -> host.updateProgress(fraction, null));
            }

            @Override
            public void onCanceled() {
                // 0.9.9：用户取消——复位状态、关掉对话框（进度条随之隐藏），
                // 但不弹「重新打开」提示条，也不弹失败对话框。
                Platform.runLater(() -> {
                    updating = false;
                    host.closeUpdateDialog();
                });
            }
        }, t -> {
            // 0.9.9 r2 / r4：失败不能静默回弹——解除 updating，并把自己真正失败的原因交给 UI。
            // 旧版只会显示固定文案「可能是网络不稳定」，把「写不进 runtime/lib/modules」
            // 「更新包不完整」「下载卡死」这些真原因全吞掉了，用户只能反复重试。
            // 安装器已用 Platform.runLater 回调，这里再包一层保证 updating 一定在 FX 线程改。
            Platform.runLater(() -> {
                updating = false;
                host.showUpdateFailed(readableReason(t));
            });
        });
    }

    /**
     * 0.9.9 r4：把 Throwable 压成一句给玩家看的话。
     *
     * <p>只取 message（安装器抛的 IOException 全是已经写好的中文话术），为空才退回类名；
     * 截断到 240 字，且绝不把堆栈拼进来 —— 堆栈进对话框只会吓到用户，日志里已经有了。
     */
    private static String readableReason(Throwable t) {
        if (t == null) {
            return "";
        }
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.getClass().getSimpleName();
        }
        msg = msg.trim();
        if (msg.length() > MAX_REASON_CHARS) {
            msg = msg.substring(0, MAX_REASON_CHARS) + "…";
        }
        return msg;
    }

    private void onCancel() {
        host.closeUpdateDialog();
        host.showUpdateTip(pending, this::onTipClick, this::onTipClose);
    }

    private void onTipClick() {
        host.closeUpdateTip();
        showDialog();
    }

    private void onTipClose() {
        // r2：× 只隐藏提示条，不再永久屏蔽本次会话的更新提醒；
        // 下次启动仍会重新检查并弹窗（onFound 的 tipDismissedThisSession 不再因 × 置位）。
        host.closeUpdateTip();
    }

    /** QA 钩子：模拟一次启动检查失败，验证走的是非阻断钩子而不是模态框回调。 */
    public static void simulateAutoCheckFailureForTests(UpdateUi host, String reason) {
        host.onAutoCheckFailed(reason);
    }
}
