package com.mclauncher;

import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import javafx.scene.paint.Material;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 0.9.8 r5：逐行移植 HMCL {@code org.jackhuang.hmcl.ui.skin.SkinMultipleCubes}（第二图层「壳」）。
 *
 * <p>HMCL 的做法是「每个不透明像素摆一个小方块」：贴图区按 interval 取样，非全透明才生成一个
 * 厚 {@code thick} 的 Box，颜色直接取像素色。好处是壳有真实厚度、不会挡住里面的第一图层；
 * 旧实现用「放大壳 + 逐像素面片」，透明像素仍会写深度，正是「预览透视 / 图案移位」的来源。
 */
public class SkinMultiCubes extends Group {

    /** 一个面：把贴图区里不透明的像素逐个变成小方块。 */
    public static class Face extends Group {

        public Face(Image image, int startX, int startY, int width, int height, int interval,
                    boolean reverseX, boolean reverseY,
                    Supplier<Box> supplier, BiConsumer<Box, Point2D> consumer) {
            PixelReader reader = image.getPixelReader();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int argb = reader.getArgb(startX + (reverseX ? width - x - 1 : x) * interval,
                            startY + (reverseY ? height - y - 1 : y) * interval);
                    if ((argb >>> 24) < 8) {
                        continue;   // 半透明到看不见的像素不建几何（HMCL 用 != 0，这里略收紧防噪点）
                    }
                    Box pixel = supplier.get();
                    consumer.accept(pixel, new Point2D(x, y));
                    pixel.setMaterial(createMaterial(Color.rgb(
                            (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)));
                    getChildren().add(pixel);
                }
            }
        }

        protected Material createMaterial(Color color) {
            return new PhongMaterial(color);
        }
    }

    private int width, height, depth;
    private float startX, startY;
    private double length, thick;

    public SkinMultiCubes(int width, int height, int depth, float startX, float startY,
                          double length, double thick) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.startX = startX;
        this.startY = startY;
        this.length = length;
        this.thick = thick;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDepth() {
        return depth;
    }

    /** 按皮肤图重建所有方块；贴图宽了（放大过的）就按 width/64 取步长。 */
    public void updateSkin(Image skin) {
        getChildren().clear();
        int startXpx = (int) (startX * skin.getWidth()), startYpx = (int) (startY * skin.getHeight()),
                interval = (int) Math.max(skin.getWidth() / 64, 1),
                widthSpan = width * interval, heightSpan = height * interval, depthSpan = depth * interval;

        // FRONT
        getChildren().add(new Face(skin, startXpx + depthSpan, startYpx + depthSpan, width, height, interval, false, false,
                () -> new Box(length, length, thick), (b, p) -> {
            b.setTranslateX(((width - 1) / 2.0 - p.getX()) * b.getWidth());
            b.setTranslateY(-((height - 1) / 2.0 - p.getY()) * b.getHeight());
            b.setTranslateZ((depth * length + thick) / 2.0);
        }));
        // BACK
        getChildren().add(new Face(skin, startXpx + widthSpan + depthSpan * 2, startYpx + depthSpan, width, height, interval, true, false,
                () -> new Box(length, length, thick), (b, p) -> {
            b.setTranslateX(((width - 1) / 2.0 - p.getX()) * b.getWidth());
            b.setTranslateY(-((height - 1) / 2.0 - p.getY()) * b.getHeight());
            b.setTranslateZ(-(depth * length + thick) / 2.0);
        }));
        // LEFT
        getChildren().add(new Face(skin, startXpx + widthSpan + depthSpan, startYpx + depthSpan, depth, height, interval, false, false,
                () -> new Box(thick, length, length), (b, p) -> {
            b.setTranslateX(-(width * length + thick) / 2.0);
            b.setTranslateY(-((height - 1) / 2.0 - p.getY()) * b.getHeight());
            b.setTranslateZ(((depth - 1) / 2.0 - p.getX()) * b.getDepth());
        }));
        // RIGHT
        getChildren().add(new Face(skin, startXpx, startYpx + depthSpan, depth, height, interval, true, false,
                () -> new Box(thick, length, length), (b, p) -> {
            b.setTranslateX((width * length + thick) / 2.0);
            b.setTranslateY(-((height - 1) / 2.0 - p.getY()) * b.getHeight());
            b.setTranslateZ(((depth - 1) / 2.0 - p.getX()) * b.getDepth());
        }));
        // TOP
        getChildren().add(new Face(skin, startXpx + depthSpan, startYpx, width, depth, interval, false, false,
                () -> new Box(length, thick, length), (b, p) -> {
            b.setTranslateX(((width - 1) / 2.0 - p.getX()) * b.getWidth());
            b.setTranslateY(-(height * length + thick) / 2.0);
            b.setTranslateZ(-((depth - 1) / 2.0 - p.getY()) * b.getDepth());
        }));
        // BOTTOM
        getChildren().add(new Face(skin, startXpx + widthSpan + depthSpan, startYpx, width, depth, interval, false, false,
                () -> new Box(length, thick, length), (b, p) -> {
            b.setTranslateX(((width - 1) / 2.0 - p.getX()) * b.getWidth());
            b.setTranslateY((height * length + thick) / 2.0);
            b.setTranslateZ(-((depth - 1) / 2.0 - p.getY()) * b.getDepth());
        }));
    }
}
