package com.mclauncher;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayInputStream;

/**
 * 皮肤预览（0.9.8 重做）：2D 平涂，直接把皮肤 PNG 的像素贴到人形轮廓上。
 *
 * <p><b>为什么不再用 3D</b>：旧 {@code Skin3D} 走 {@code TriangleMesh} + {@code PhongMaterial}
 * + {@code AmbientLight} + {@code DirectionalLight} + {@code PerspectiveCamera}，
 * 用户明确要求「不要光线追踪」。那套光照与透视正是青色/亮紫竖条、两条腿糊成一条、
 * 鞋子变形的来源。这里完全不建 3D 场景：没有光照、没有材质、没有相机、没有旋转，
 * 每一块就是一次 {@code GraphicsContext.drawImage}，皮肤是什么颜色就显示什么颜色。
 *
 * <p><b>分区表照 HMCL</b>：坐标抄自 HMCL {@code org.jackhuang.hmcl.ui.skin.SkinCanvas}
 * 里各 {@code SkinCube} 的 {@code startX / startY}，以及 {@code SkinHelper.x32Tox64()}
 * 的老皮肤补齐思路。64×64 皮肤每块部件的 UV 展开是
 * {@code (startX, startY, (w+d)*2, h+d)}，正面位于
 * {@code (startX+d, startY+d, w, h)}；外层（帽子/外套/袖子/裤腿）在各自的镜像区。
 * 宽臂 4 格、细臂 3 格，头部 8×8×8、身体 8×12×4、四肢 4×12×4。
 */
public final class Skin2D {

    /** 0.9.8 指纹：皮肤预览改用 2D 平涂（构建门禁扫 runtime/lib/modules 常量池）。 */
    public static final String FP_098_SKIN = "0.9.8 皮肤 2D 平涂预览";
    static { System.err.println("[skin2d] " + FP_098_SKIN); }

    private final Canvas canvas;
    private byte[] png;
    private boolean slim;

    public Skin2D(byte[] png, String model) {
        this(300, 340, png, model);
    }

    public Skin2D(double w, double h, byte[] png, String model) {
        this.canvas = new Canvas(w, h);
        this.canvas.getStyleClass().add("skin-2d-canvas");
        setSkin(png, model);
    }

    /** 挂进 UI 的节点；2D 不需要 SubScene，直接给 Canvas。 */
    public Canvas canvas() {
        return canvas;
    }

    public void setSkin(byte[] png, String model) {
        this.png = png;
        this.slim = "slim".equalsIgnoreCase(model);
        draw();
    }

    /** 兼容旧 {@code Skin3D.stop()} 的关闭钩子：2D 没有自转线程，无需停任何东西。 */
    public void stop() {
        // 故意留空：保持皮肤管理弹窗的开关结构不变
    }

    /** 画布尺寸变化时重画（当前容器是固定 300×340，留着给以后改尺寸用）。 */
    public void resize(double w, double h) {
        canvas.setWidth(w);
        canvas.setHeight(h);
        draw();
    }

    private void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double cw = canvas.getWidth();
        double ch = canvas.getHeight();
        gc.clearRect(0, 0, cw, ch);
        // 像素皮肤必须锐利放大，插值会把 1 像素的五官糊成一团
        gc.setImageSmoothing(false);
        if (png == null || png.length == 0) {
            return;
        }
        Image src;
        try {
            src = new Image(new ByteArrayInputStream(png));
        } catch (Exception ex) {
            System.err.println("[skin2d] 皮肤图片读不了: " + ex);
            return;
        }
        if (src.isError() || src.getWidth() < 64 || src.getHeight() < 32) {
            System.err.println("[skin2d] 皮肤尺寸不对（要 64×32 或 64×64）");
            return;
        }
        // 老 64×32 皮肤没有左臂/左腿独立区，也没有外层：按 HMCL 的办法补成 64×64
        Image skin = src.getHeight() < 64 ? x32ToX64(src) : src;

        double arm = slim ? 3 : 4;
        double unitsW = arm + 8 + arm;
        double unitsH = 32;                      // 头 8 + 身 12 + 腿 12
        double s = Math.min((cw - 36) / (unitsW + 2), (ch - 36) / (unitsH + 2));
        if (s <= 0) {
            return;
        }
        double ox = (cw - unitsW * s) / 2;
        double oy = (ch - unitsH * s) / 2;

        // ---- 内层：皮肤本体 ----
        // 头（正面 8×8 在 8,8）
        part(gc, skin, 8, 8, 8, 8, ox + arm * s, oy, 8 * s, 8 * s);
        // 身体（正面 8×12 在 20,20）
        part(gc, skin, 20, 20, 8, 12, ox + arm * s, oy + 8 * s, 8 * s, 12 * s);
        // 屏幕左侧是角色的右臂，右侧是左臂 —— 别反了，反了就会看到「青色竖条」
        part(gc, skin, 44, 20, arm, 12, ox, oy + 8 * s, arm * s, 12 * s);
        part(gc, skin, 36, 52, arm, 12, ox + (arm + 8) * s, oy + 8 * s, arm * s, 12 * s);
        // 两条腿并排，各有自己的正面贴图，绝不会再糊成一条
        part(gc, skin, 4, 20, 4, 12, ox + arm * s, oy + 20 * s, 4 * s, 12 * s);
        part(gc, skin, 20, 52, 4, 12, ox + (arm + 4) * s, oy + 20 * s, 4 * s, 12 * s);

        // ---- 外层（帽子 / 外套 / 袖子 / 裤腿）----
        // 皮肤里这些区域常常是全透明的，drawImage 的 alpha 会自然不覆盖内层，
        // 所以不用先判「有没有外层」，有就叠上去、没有就等于没画。
        double e = 0.30 * s;      // 外层比本体略大一圈，跟游戏里一致
        part(gc, skin, 40, 8, 8, 8,
                ox + arm * s - e / 2, oy - e / 2, 8 * s + e, 8 * s + e);
        part(gc, skin, 20, 36, 8, 12,
                ox + arm * s - e / 2, oy + 8 * s - e / 2, 8 * s + e, 12 * s + e);
        part(gc, skin, 44, 36, arm, 12,
                ox - e / 2, oy + 8 * s - e / 2, arm * s + e, 12 * s + e);
        part(gc, skin, 52, 52, arm, 12,
                ox + (arm + 8) * s - e / 2, oy + 8 * s - e / 2, arm * s + e, 12 * s + e);
        part(gc, skin, 4, 36, 4, 12,
                ox + arm * s - e / 2, oy + 20 * s - e / 2, 4 * s + e, 12 * s + e);
        part(gc, skin, 4, 52, 4, 12,
                ox + (arm + 4) * s - e / 2, oy + 20 * s - e / 2, 4 * s + e, 12 * s + e);

        // 两腿之间擦一道窄缝，露出容器底色。旧 3D 预览因为透视把两条腿糊成一条，
        // 用户明确指出了这一点；2D 平涂也要让人一眼看出是两条腿。
        // 只擦透明、不改任何皮肤像素，浅色 / 深色主题都会自然透出各自背景。
        double gap = Math.max(2.0, 0.42 * s);
        gc.clearRect(ox + (arm + 4) * s - gap / 2, oy + 20 * s - e / 2,
                gap, 12 * s + e);
    }

    /** 一块面：从皮肤图 (sx,sy,sw,sh) 原样贴到画布 (dx,dy,dw,dh)，不做任何着色。 */
    private static void part(GraphicsContext gc, Image skin,
                             double sx, double sy, double sw, double sh,
                             double dx, double dy, double dw, double dh) {
        if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) {
            return;
        }
        gc.drawImage(skin, sx, sy, sw, sh, dx, dy, dw, dh);
    }

    /**
     * 把 64×32 老皮肤补成 64×64。
     *
     * <p>照 HMCL {@code SkinHelper.x32Tox64()} 的思路：头部与帽子区原样搬过去，
     * 身体/右臂/右腿原样搬过去，左臂与左腿由右侧对应面水平镜像生成。
     * 2D 正面预览只用到每块的「正面」，所以这里只补正面即可；
     * 外层区域在老皮肤里本来就不存在，保持透明，绘制时自然不叠。
     */
    private static Image x32ToX64(Image src) {
        WritableImage out = new WritableImage(64, 64);
        PixelReader r = src.getPixelReader();
        PixelWriter w = out.getPixelWriter();
        if (r == null) {
            return out;
        }
        // 头部 + 帽子所在的 0..16 行，整行 64 宽原样复制
        copyRect(src, r, w, 0, 0, 0, 0, 64, 16, false);
        // 右腿 / 身体 / 右臂所在的 16..32 行
        copyRect(src, r, w, 0, 16, 0, 16, 64, 16, false);
        // 左腿正面：右腿正面 (4,20,4,12) 镜像到 (20,52)
        copyRect(src, r, w, 4, 20, 20, 52, 4, 12, true);
        // 左臂正面：右臂正面 (44,20,4,12) 镜像到 (36,52)
        copyRect(src, r, w, 44, 20, 36, 52, 4, 12, true);
        return out;
    }

    private static void copyRect(Image src, PixelReader r, PixelWriter w,
                                 int sx, int sy, int dx, int dy,
                                 int width, int height, boolean mirrorX) {
        int maxX = (int) src.getWidth();
        int maxY = (int) src.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int px = sx + (mirrorX ? width - x - 1 : x);
                int py = sy + y;
                int tx = dx + x;
                int ty = dy + y;
                if (px < 0 || py < 0 || px >= maxX || py >= maxY
                        || tx < 0 || ty < 0 || tx >= 64 || ty >= 64) {
                    continue;
                }
                w.setArgb(tx, ty, r.getArgb(px, py));
            }
        }
    }
}
