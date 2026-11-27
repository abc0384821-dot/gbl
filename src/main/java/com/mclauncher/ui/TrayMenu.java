package com.mclauncher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 托盘右键菜单：FX 自绘白底圆角卡片，独立 {@code Stage(StageStyle.TRANSPARENT)} + 透明 {@code Scene}。
 * 根节点直接复用主界面现成的 {@code .game-menu} / {@code .menu-item} / {@code .menu-label} /
 * {@code .menu-sep} 样式类（独立 Stage 不继承主 scene 的样式表，所以这里自行加载 theme.css；
 * 独立 Stage 没有 {@code .dark} 类，自动走浅色白底圆角卡片，正是需要的 WorkBuddy 风格）。
 *
 * <p>菜单项全中文，不含任何用户个人化内容。悬停 / 圆角 / 投影 / 细分隔线全部靠复用现有 CSS 类。
 *
 * <p><b>0.9.7 关键修复（任务栏不许多出一格）</b>：JavaFX 的 {@code Stage(StageStyle.TRANSPARENT)}
 * 在 Windows 上是「无主 + 可见 + 没有 WS_EX_TOOLWINDOW」的顶层窗口，系统会给它单独建一个任务栏按钮，
 * 于是「只是弹个托盘菜单」也会让 GBL 出现在任务栏。本机抓原生样式位实测确认：
 * <pre>
 *   无主 TRANSPARENT 窗口 → style=0x960b0000 ex=0x00080000(ToolWindow=false, owner=0) → 占任务栏
 *   有主 TRANSPARENT 窗口 → owner 指向锚窗口 → 任务栏不再单独建格
 *   UTILITY 窗口         → ex 含 0x80(WS_EX_TOOLWINDOW) → 不占任务栏
 * </pre>
 * 所以这里用一个「不可见 1×1 UTILITY 锚窗口」当 owner（UTILITY 自带 TOOLWINDOW，不占任务栏），
 * 菜单 Stage 被它拥有后就不会再往任务栏丢一格。纯 JavaFX，不引入 JNA 之类的原生依赖。
 */
public final class TrayMenu {

    private static final double MENU_WIDTH = 220;

    /** 0.9.7 指纹：托盘菜单不再占任务栏 + 去掉开机自启项。构建门禁扫常量池，勿改这串字。 */
    static final String FP_097_TRAY_MENU = "0.9.7 托盘菜单不占任务栏";

    /** 不可见锚窗口：只为了让菜单 Stage 变成「有主窗口」，从而不占任务栏。 */
    private static Stage anchor;

    private final Stage stage;

    /**
     * @param onOpen 「打开 GBL 主窗口」的动作（由 TraySupport 转发当前回调，不会锁死）
     * @param onQuit 「退出 GBL」的动作（由 TraySupport 负责先摘托盘图标再退出）
     */
    public TrayMenu(Runnable onOpen, Runnable onQuit) {
        System.err.println("[tray] " + FP_097_TRAY_MENU);

        VBox card = new VBox(2);
        card.getStyleClass().add("game-menu");
        card.setPadding(new Insets(6, 0, 6, 0));
        card.setPrefWidth(MENU_WIDTH);
        // 0.9.7：删掉「开机自动启动」——开机自启改到设置页统一管理，托盘里只留打开/退出两个动作。
        card.getChildren().addAll(
                item("打开 GBL 主窗口", onOpen),
                separator(),
                item("退出 GBL", onQuit));

        // 外层留一圈透明 padding，给 .game-menu 的 dropshadow 投影留出显示空间，别被 Scene 裁掉
        StackPane root = new StackPane(card);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, Color.TRANSPARENT);
        // C2：独立 Stage 不继承主 scene 的样式表，必须自己挂 theme.css
        java.net.URL css = getClass().getResource("/com/mclauncher/theme.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        // C3：Esc 关闭
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
            }
        });

        stage = new Stage(StageStyle.TRANSPARENT);
        // 关键：先挂 owner 再 setScene/show，否则 Windows 上 owner 不会生效（实测 owner 仍是 0）
        stage.initOwner(anchor());
        stage.setScene(scene);
        stage.setResizable(false);
        // C3：失焦自动收起
        stage.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused) {
                hide();
            }
        });
    }

    /**
     * 懒建那个不可见的 1×1 UTILITY 锚窗口（必须在 FX 线程调用；菜单构造本身就在 FX 线程）。
     * 放在屏幕外 + 完全透明，既不会出现也不会进 Alt+Tab（UTILITY 自带 WS_EX_TOOLWINDOW），
     * 唯一作用是给菜单 Stage 当 owner，让它别在任务栏单独占一格。
     */
    private static Stage anchor() {
        if (anchor == null) {
            Stage a = new Stage(StageStyle.UTILITY);
            a.setTitle("GBL");
            a.setScene(new Scene(new StackPane(), 1, 1));
            a.setX(-6000);
            a.setY(-6000);
            a.setOpacity(0);
            a.show();
            anchor = a;
        }
        return anchor;
    }

    /** 普通菜单项：复用 .menu-item / .menu-label 样式类；点后先 hide 再执行动作（C3）。 */
    private Node item(String text, Runnable action) {
        Label label = new Label(text);
        label.getStyleClass().setAll("menu-label");
        HBox row = new HBox(label);
        row.getStyleClass().add("menu-item");
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(e -> {
            hide();
            action.run();
        });
        return row;
    }

    /** 一条 .menu-sep 细分隔线。 */
    private Node separator() {
        Region line = new Region();
        line.getStyleClass().add("menu-sep");
        line.setPrefHeight(1);
        line.setMinHeight(1);
        return line;
    }

    /** 打开菜单：贴在托盘图标上方、做右/下边界收敛。 */
    public void show(double screenX, double screenY) {
        // 先用触发点定位再 show，show 之后拿到真实宽高再精确收敛，避免越界与大幅闪移
        stage.setX(screenX);
        stage.setY(screenY);
        if (!stage.isShowing()) {
            stage.show();
        }
        double w = stage.getWidth();
        double h = stage.getHeight();
        if (!(w > 0) || !(h > 0)) {
            stage.sizeToScene();
            w = stage.getWidth();
            h = stage.getHeight();
        }
        Rectangle2D vb = Screen.getPrimary().getVisualBounds();
        // 贴在托盘图标上方：右边缘对齐触发点，纵向翻到图标之上
        double x = screenX - w + 16;
        double y = screenY - h - 6;
        // C3：右 / 下边界收敛（同时兜住左 / 上）
        x = Math.max(vb.getMinX() + 6, Math.min(x, vb.getMaxX() - w - 6));
        y = Math.max(vb.getMinY() + 6, Math.min(y, vb.getMaxY() - h - 6));
        stage.setX(x);
        stage.setY(y);
        stage.toFront();
        stage.requestFocus();
    }

    public void hide() {
        if (stage.isShowing()) {
            stage.hide();
        }
    }

    /** 退出前收尾：连同锚窗口一起收掉，别留一个隐形窗口在进程里。 */
    public static void disposeAnchor() {
        Stage a = anchor;
        anchor = null;
        if (a != null) {
            try {
                a.hide();
            } catch (Throwable ignored) {
                // 退出路径上收不掉无所谓，进程一走系统自己回收
            }
        }
    }

    /** 托盘菜单在 FX 线程外被要求收起时用；工具包已关就静默忽略。 */
    public static void hideQuietly(TrayMenu menu) {
        if (menu == null) {
            return;
        }
        try {
            Platform.runLater(menu::hide);
        } catch (Throwable ignored) {
            // JavaFX 已经退出，无需处理
        }
    }
}
