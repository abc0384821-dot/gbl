package com.mclauncher.ui;

import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 1.3.3 调色面板：色环（{@link ColorWheel}）+ HEX 输入 + 一排预设色。
 *
 * <p>面板是<b>内联展开</b>在设置卡里的，不开弹窗。理由很实在：GBL 是
 * {@code StageStyle.TRANSPARENT} 的自绘窗口，再开一层 {@code Popup}/独立 {@code Stage}
 * 要么带一圈系统白底、要么圆角对不上、要么深色主题里还要单独再挂一遍样式表——
 * 内联进来这些坑一个都不存在，还顺手拿到了"点哪都能看见实时预览"。
 *
 * <p>实时预览：色环拖动 / 预设点击 / HEX 回车，一律立刻回调 {@code onLiveChange}，
 * 界面当场上色；节流（避免每帧重写 114 KB 样式表）由调用方 {@code ThemeRuntime} 负责。
 */
public final class ColorPickerPanel extends VBox {

    /** 预设色：出厂绿 + 一组覆盖常见色相的颜色 + 两个近黑白（文字色用得上）。 */
    private static final String[] PRESETS = {
            "#237a44", "#2563eb", "#7c3aed", "#db2777", "#dc2626", "#ea580c",
            "#ca8a04", "#0d9488", "#0891b2", "#475569", "#111827", "#f8fafc"};

    private final ColorWheel wheel = new ColorWheel();
    private final TextField hexField = new TextField();
    private final Region preview = new Region();
    private static final String HEX_HINT = "HEX 值，例如 #237a44";

    private final Label hexHint = new Label(HEX_HINT);
    private final Consumer<String> onLiveChange;

    /** 防止"色环改色 → 回填输入框 → 输入框触发改色"这种自激。 */
    private boolean syncing;

    /**
     * @param title        面板标题（如「主题色」）
     * @param subtitle     一句说明
     * @param currentHex   打开时的颜色
     * @param onLiveChange 改色回调（节流由调用方做）
     * @param onReset      点「恢复默认」
     * @param onDone       点「完成」（收起面板）
     */
    public ColorPickerPanel(String title, String subtitle, String currentHex,
                            Consumer<String> onLiveChange, Runnable onReset, Runnable onDone) {
        this.onLiveChange = onLiveChange;
        getStyleClass().add("color-picker-panel");
        setSpacing(10);
        setPadding(new Insets(18, 20, 16, 20));
        // 面板自身是一张有底有边的小卡片，宽度跟着内容走就够（色环 196 + 20 + 右列 184 + 40 内边距）。
        // 不钉住的话 VBox 会把它拉成整行宽，右边空出几百像素的荒地，footer 的「完成」也被甩到屏幕尽头。
        setMaxWidth(Region.USE_PREF_SIZE);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        preview.getStyleClass().add("color-preview-dot");

        Region headGap = new Region();
        HBox.setHgrow(headGap, Priority.ALWAYS);
        HBox head = new HBox(10, titleLabel, headGap);
        head.setAlignment(Pos.CENTER_LEFT);

        Label subLabel = new Label(subtitle);
        subLabel.getStyleClass().add("setting-desc");
        subLabel.setWrapText(true);

        hexField.getStyleClass().add("color-hex-field");
        hexField.setPrefColumnCount(8);
        hexField.setPrefWidth(118);
        hexField.setOnAction(e -> commitHexField());
        hexField.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                commitHexField();
            }
        });
        hexHint.getStyleClass().add("setting-desc");
        // 色块紧贴输入框：一眼就是"当前色 = 这个值"，比把色块挂在标题旁边好读
        HBox hexRow = new HBox(8, preview, hexField);
        hexRow.setAlignment(Pos.CENTER_LEFT);

        Label presetLabel = new Label("预设");
        presetLabel.getStyleClass().add("setting-desc");
        FlowPane presetBox = new FlowPane(8, 8);
        // 12 个预设排成整齐的 6×2：6×22 + 5×8 = 172，留一点余量给 184 的列宽
        presetBox.setPrefWrapLength(176);
        for (String preset : PRESETS) {
            presetBox.getChildren().add(presetDot(preset));
        }

        Label hexLabel = new Label("HEX");
        hexLabel.getStyleClass().add("setting-desc");

        // 两组之间留一道气口：上面是"输入"，下面是"挑一个"，别糊成一列
        Region groupGap = new Region();
        groupGap.setMinHeight(4);
        groupGap.setPrefHeight(4);

        VBox right = new VBox(6, hexLabel, hexRow, hexHint, groupGap, presetLabel, presetBox);
        right.setPrefWidth(184);
        right.setMinWidth(184);

        HBox body = new HBox(20, wheel, right);
        body.setAlignment(Pos.TOP_LEFT);

        Button reset = new Button("恢复默认");
        reset.getStyleClass().add("btn-ghost");
        reset.setOnAction(e -> {
            if (onReset != null) {
                onReset.run();
            }
        });
        Button done = new Button("完成");
        done.getStyleClass().add("btn-ghost");
        done.setOnAction(e -> {
            if (onDone != null) {
                onDone.run();
            }
        });
        Region footGap = new Region();
        HBox.setHgrow(footGap, Priority.ALWAYS);
        HBox foot = new HBox(10, reset, footGap, done);
        foot.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(head, subLabel, body, foot);

        wheel.setOnColorChanged(hex -> {
            if (syncing) {
                return;
            }
            syncing = true;
            try {
                hexField.setText(hex);
                hexField.getStyleClass().remove("color-hex-invalid");
                hexHint.setText(HEX_HINT);
                paintPreview(hex);
            } finally {
                syncing = false;
            }
            fire(hex);
        });

        setColor(currentHex);
    }

    /** 外部回填当前色（不回调和）。 */
    public void setColor(String hex) {
        String normalized = normalize(hex);
        if (normalized == null) {
            return;
        }
        syncing = true;
        try {
            wheel.setColor(normalized);
            hexField.setText(normalized);
            hexField.getStyleClass().remove("color-hex-invalid");
            paintPreview(normalized);
        } finally {
            syncing = false;
        }
    }

    /** 当前面板上的颜色。 */
    public String colorHex() {
        return wheel.colorHex();
    }

    // ------------------------------------------------------------------ 内部

    private Region presetDot(String hex) {
        Region dot = new Region();
        dot.getStyleClass().add("color-preset");
        dot.setMinSize(22, 22);
        dot.setPrefSize(22, 22);
        dot.setMaxSize(22, 22);
        dot.setStyle("-fx-background-color: " + hex + ";");
        dot.setOnMouseClicked(e -> {
            setColor(hex);
            fire(hex);
        });
        return dot;
    }

    private void commitHexField() {
        if (syncing) {
            return;
        }
        String raw = hexField.getText();
        String normalized = normalize(raw);
        if (normalized == null) {
            // 非法输入：标红边框 + 提示不改文案，绝不把界面刷成奇怪的颜色
            if (!hexField.getStyleClass().contains("color-hex-invalid")) {
                hexField.getStyleClass().add("color-hex-invalid");
            }
            hexHint.setText("格式不对：要 6 位 HEX，例如 #237a44");
            return;
        }
        hexField.getStyleClass().remove("color-hex-invalid");
        hexHint.setText(HEX_HINT);
        syncing = true;
        try {
            wheel.setColor(normalized);
            hexField.setText(normalized);
            paintPreview(normalized);
        } finally {
            syncing = false;
        }
        fire(wheel.colorHex());
    }

    private void fire(String hex) {
        if (onLiveChange != null && !syncing) {
            onLiveChange.accept(hex);
        }
    }

    private void paintPreview(String hex) {
        preview.setStyle("-fx-background-color: " + hex + ";");
    }

    /** HEX 规范化统一走 {@link com.mclauncher.ThemeColor}，与持久化/QA 钩子是同一套规则。 */
    private static String normalize(String hex) {
        return com.mclauncher.ThemeColor.normalize(hex);
    }
}
