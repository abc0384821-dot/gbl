package com.mclauncher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * GBL 1.1.5 自绘折叠面板皮肤：替代原生 TitledPaneSkin 的浅色横条与 350ms 固定时长。
 *
 * <p>保留 TitledPane 本身的 API（expandedProperty / setExpanded / 焦点 / 内容节点），
 * 只替换外观与动画：深色标题条 + 内联矢量箭头 + 裁剪内容 + 150ms EASE_BOTH。
 *
 * <p>连续点击时先停旧 Timeline，再从当前 transition 值起播，动画不会叠加或跳变；
 * 展开时立即显示内容，收起结束时才隐藏，避免中途闪白。
 */
public final class GblTitledPaneSkin extends SkinBase<TitledPane> {

    /** 私有过渡量：同时驱动内容裁剪高度与箭头旋转。 */
    private final DoubleProperty transition = new SimpleDoubleProperty(1.0);

    private final HBox title = new HBox(8);
    private final Label titleText = new Label();
    private final SVGPath arrow = new SVGPath();
    private final StackPane content = new StackPane();
    private final Rectangle clip = new Rectangle();

    private Timeline timeline;
    private Node controlContent;

    /** 创建皮肤并安装到指定 TitledPane（调用点自行 setSkin）。 */
    public GblTitledPaneSkin(TitledPane control) {
        super(control);
        if (control == null) {
            throw new IllegalArgumentException("TitledPane 不能为空");
        }

        title.getStyleClass().add("gbl-disclosure-title");
        title.setAlignment(Pos.CENTER_LEFT);
        title.setFocusTraversable(true);

        titleText.getStyleClass().add("gbl-disclosure-title-text");
        titleText.textProperty().bind(control.textProperty());

        arrow.getStyleClass().add("gbl-disclosure-arrow");
        arrow.setContent("M1 1L5 5L9 1");   // 仅直线段，不用 A 弧线，避免 SvgPath 解析差异

        title.getChildren().addAll(titleText, arrow);
        title.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && control.isCollapsible()) {
                control.setExpanded(!control.isExpanded());
                e.consume();
            }
        });
        title.setOnKeyPressed(this::onTitleKey);

        content.getStyleClass().add("gbl-disclosure-content");
        content.setClip(clip);
        bindContent();

        getChildren().addAll(title, content);

        transition.addListener((obs, oldValue, newValue) -> {
            double t = newValue.doubleValue();
            arrow.setRotate(-90.0 * (1.0 - t));
            content.setVisible(t > 0.0 || control.isExpanded());
            // 高度已由 pref/min 公式按 t 收缩，clip 跟随实际内容高度即可，不能二次乘 t。
            double bodyHeight = content.getHeight() > 0.0
                    ? content.getHeight()
                    : innerHeight(getSkinnable().getWidth(), false);
            clip.setHeight(bodyHeight);
            requestParentLayout();
        });

        registerChangeListener(control.expandedProperty(),
                obs -> runTransition(control.isExpanded() ? 1.0 : 0.0));
        registerChangeListener(control.contentProperty(), obs -> bindContent());
        registerChangeListener(control.collapsibleProperty(),
                obs -> title.setDisable(!control.isCollapsible()));

        title.setDisable(!control.isCollapsible());
        setTransition(control.isExpanded() ? 1.0 : 0.0);
    }

    /** 标题条上的空格 / 回车与鼠标点击等价，沿用现有焦点环。 */
    private void onTitleKey(KeyEvent event) {
        if (event.getCode() != KeyCode.SPACE && event.getCode() != KeyCode.ENTER) {
            return;
        }
        TitledPane pane = getSkinnable();
        if (pane.isCollapsible()) {
            pane.setExpanded(!pane.isExpanded());
            event.consume();
        }
    }

    /** 内容节点随 TitledPane.contentProperty() 变化重新挂到裁剪容器。 */
    private void bindContent() {
        Node next = getSkinnable().getContent();
        if (next == controlContent) {
            return;
        }
        if (controlContent != null) {
            content.getChildren().remove(controlContent);
        }
        controlContent = next;
        if (controlContent != null) {
            content.getChildren().add(controlContent);
        }
        requestParentLayout();
    }

    /** Parent.requestParentLayout() 受保护，这里从皮肤侧显式请求父容器重排。 */
    private void requestParentLayout() {
        Parent parent = getSkinnable().getParent();
        if (parent != null) {
            parent.requestLayout();
        } else {
            getSkinnable().requestLayout();
        }
    }

    /**
     * 开播新过渡：先停旧 Timeline，从当前 transition 起播，连点不叠加。
     * reduceMotion / isAnimated() 任一关闭时直接落终态，不创建 Timeline。
     */
    public void runTransition(double target) {
        double to = Math.max(0.0, Math.min(1.0, target));
        if (!getSkinnable().isCollapsible()) {
            to = 1.0;
        }
        final double targetValue = to;
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        double from = transition.get();
        if (Math.abs(from - to) < 0.001) {
            setTransition(to);
            return;
        }
        if (to > 0.0) {
            content.setVisible(true);
        }
        boolean animate = getSkinnable().isAnimated()
                && GblMotion.allowed(getSkinnable().getScene());
        if (!animate) {
            setTransition(to);
            return;
        }
        timeline = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(transition, from)),
                new KeyFrame(GblMotion.REVEAL,
                        new KeyValue(transition, to, GblMotion.EASE_BOTH)));
        timeline.setOnFinished(e -> {
            timeline = null;
            setTransition(targetValue);
        });
        timeline.play();
    }

    /** 直接落终态；收起时最后隐藏内容。 */
    public void setTransition(double t) {
        double value = Math.max(0.0, Math.min(1.0, t));
        transition.set(value);
        arrow.setRotate(-90.0 * (1.0 - value));
        content.setVisible(value > 0.0 || getSkinnable().isExpanded());
    }

    /** 当前过渡进度（0=完全收起，1=完全展开）。 */
    public double getTransition() {
        return transition.get();
    }

    /** 当前场景是否允许动画（供 QA / 调试读取）。 */
    public boolean motionAllowed() {
        return GblMotion.allowed(getSkinnable().getScene());
    }

    private double headerHeight(double width) {
        return snapSizeY(title.prefHeight(Math.max(0.0, width)));
    }

    private double innerHeight(double width, boolean min) {
        Node node = getSkinnable().getContent();
        if (node == null) {
            return 0.0;
        }
        double w = Math.max(0.0, width);
        double h = min ? node.minHeight(w) : node.prefHeight(w);
        return Double.isFinite(h) && h > 0.0 ? snapSizeY(h) : 0.0;
    }

    /** pref / min / layout 共用同一公式：标题高度 + 内容尺寸 × transition。 */
    private double totalHeight(double width, double topInset, double bottomInset, boolean min) {
        return headerHeight(width)
                + innerHeight(width, min) * transition.get()
                + snapSizeY(topInset + bottomInset);
    }

    @Override
    protected double computePrefHeight(double width, double topInset, double rightInset,
                                       double bottomInset, double leftInset) {
        return totalHeight(width, topInset, bottomInset, false);
    }

    @Override
    protected double computeMinHeight(double width, double topInset, double rightInset,
                                      double bottomInset, double leftInset) {
        return totalHeight(width, topInset, bottomInset, true);
    }

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        double width = Math.max(0.0, w);
        double header = Math.min(headerHeight(width), Math.max(0.0, h));
        title.resize(width, header);
        title.relocate(x, y);

        double body = Math.max(0.0, h - header);
        content.resize(width, body);
        content.relocate(x, y + header);

        clip.setWidth(width);
        // body 已经按 transition 收缩（pref/min 与此处同公式），clip 直接用 body，避免 t²。
        clip.setHeight(body);
        arrow.setRotate(-90.0 * (1.0 - transition.get()));
    }

    @Override
    public void dispose() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        TitledPane pane = getSkinnable();
        unregisterChangeListeners(pane.expandedProperty());
        unregisterChangeListeners(pane.contentProperty());
        unregisterChangeListeners(pane.collapsibleProperty());
        titleText.textProperty().unbind();
        content.setClip(null);
        content.getChildren().clear();
        getChildren().clear();
        controlContent = null;
    }
}
