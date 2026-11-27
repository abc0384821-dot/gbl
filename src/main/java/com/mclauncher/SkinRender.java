package com.mclauncher;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * 从皮肤 PNG（标准 MC 皮肤，64×64 或老式 64×32）渲染「头部」头像，以及给 3D 预览用的工具。
 *
 * <p>头部取皮肤正面脸区域（x 8..15, y 8..15 共 8×8），再叠加帽子层（x 40..47, y 8..15，不透明才盖）。
 * 老式 64×32 皮肤的头/帽区域坐标与 64×64 一致，所以这里两种格式都能用。
 *
 * <p>清晰度：原来只输出 8×8 的 WritableImage，被 ImageView 放大到 34~96px 时即使 nearest-neighbor
 * 也偏肉（尤其小头像）。现在按 {@code SCALE} 倍硬边放大到 64×64，源就是清晰的，显示端再 smooth=false
 * 即可做到像素级锐利、不糊。
 */
public final class SkinRender {

    private SkinRender() {
    }

    /** 放大倍数：8×8 脸 → 64×64 输出（硬边，不抗锯齿）。 */
    private static final int SCALE = 8;

    /** 从字节渲染 8×8 头部（放大到 64×64，WritableImage，UI 侧用 ImageView 显示）。 */
    public static WritableImage head(byte[] png) {
        return headFromBuffered(loadBuffered(png));
    }

    public static WritableImage head(InputStream png) {
        return headFromBuffered(loadBuffered(png));
    }

    /** PNG 签名；IHDR 宽高在字节 16..23（大端），任一维 > 4096 视为超大图拒解码。 */
    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int MAX_PNG_DIM = 4096;

    /**
     * 0.9.9 P3：ImageIO 解码超大 PNG 会抛 OutOfMemoryError（Error，catch(Exception) 抓不到），
     * 先把 JVM 打爆。这里只读 PNG 头 24 字节的 IHDR 宽高做预检，非 PNG 格式不误判。
     */
    private static boolean isOversizedPng(byte[] png) {
        if (png == null || png.length < 24) {
            return false;
        }
        for (int i = 0; i < PNG_SIG.length; i++) {
            if (png[i] != PNG_SIG[i]) {
                return false; // 非 PNG（JPEG/WebP 等）不按 IHDR 判，交给 ImageIO 自己处理
            }
        }
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16)
                | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16)
                | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return w > MAX_PNG_DIM || h > MAX_PNG_DIM;
    }

    /**
     * 同步解码 PNG 字节为 BufferedImage。采用 javax.imageio.ImageIO 同步解码，
     * 不走 JavaFX Image 的异步解码，彻底消除解码完成前读取像素返回 null / 未填充
     * 而导致「蓝白花屏」灰块（0xFF9AA3AD）的竞态。失败返回 null。
     */
    private static BufferedImage loadBuffered(byte[] png) {
        if (png == null) {
            return null;
        }
        if (isOversizedPng(png)) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException ex) {
            return null;
        }
    }

    /** 同步解码 PNG 流为 BufferedImage（失败返回 null）。先整体读入内存走同一套尺寸预检。 */
    private static BufferedImage loadBuffered(InputStream png) {
        if (png == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = png.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return loadBuffered(bos.toByteArray());
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * 从已同步解码的 BufferedImage 渲染头部头像。
     * 脸区取 (8..15, 8..15) 共 8×8；64×64 皮肤叠加帽子层 (40..47, 8..15，不透明才盖)；
     * 老式 64×32 皮肤无帽子层，height<64 时跳过叠加，避免误把下半身区域当成帽子糊到脸上。
     */
    private static WritableImage headFromBuffered(BufferedImage skin) {
        int f = 8;
        WritableImage out = new WritableImage(f * SCALE, f * SCALE);
        PixelWriter pw = out.getPixelWriter();
        // 0.9.9 P1：宽<48 时下面 getRGB(40+x,…) 最大列 47 会越界；高<16 时 getRGB(…,8+y)
        // 最大行 15 会越界。非法尺寸的坏图曾被写进 skins.json 并 save，之后每次打开弹窗都崩，
        // 这里统一退化为灰块兜底，绝不抛异常。
        if (skin == null || skin.getWidth() < 48 || skin.getHeight() < 16) {
            // 图加载失败或尺寸非法：给个灰块兜底
            for (int y = 0; y < f * SCALE; y++) {
                for (int x = 0; x < f * SCALE; x++) {
                    pw.setArgb(x, y, 0xFF9AA3AD);
                }
            }
            return out;
        }
        int height = skin.getHeight();
        for (int y = 0; y < f; y++) {
            for (int x = 0; x < f; x++) {
                int face = skin.getRGB(8 + x, 8 + y);
                // 仅 64×64 皮肤有帽子层；64×32 老皮肤跳过，避免把非帽子区域叠加到脸上。
                if (height >= 64) {
                    int hat = skin.getRGB(40 + x, 8 + y);
                    // 帽子层不透明才覆盖脸（透明帽子=无帽子）
                    if ((hat & 0xFF000000L) != 0) {
                        face = hat;
                    }
                }
                for (int dy = 0; dy < SCALE; dy++) {
                    for (int dx = 0; dx < SCALE; dx++) {
                        pw.setArgb(x * SCALE + dx, y * SCALE + dy, face);
                    }
                }
            }
        }
        return out;
    }

    /** 便捷方法：直接取账号当前皮肤头部。 */
    public static WritableImage headOf(AccountStore.Account acc) {
        try {
            return head(SkinStore.skinBytes(acc));
        } catch (IOException ex) {
            try {
                return head(SkinStore.defaultBytes("steve"));
            } catch (IOException ignore) {
                return new WritableImage(8 * SCALE, 8 * SCALE);
            }
        }
    }

    /** 整张皮肤图（供 3D 预览当纹理用）。 */
    public static Image fullImage(byte[] png) {
        return new Image(new ByteArrayInputStream(png));
    }

    /**
     * 1.3.1：照抄 HMCL {@code NormalizedSkin.isSlim()} 的四区域透明检测 + 纯黑兜底。
     *
     * <p>旧实现只查左臂正面第 4 列一列，遇到「透明列被花纹覆盖」或 HD 皮肤（宽>64）就误判成宽臂，
     * 导入时被存成 classic → 用错 UV → 细臂预览不对。这里按 HMCL 同款坐标：
     * 右臂/右腿的下半 second-layer 区有任一透明像素，或四区全为纯黑（透明被当黑导出），
     * 都判为细臂；坐标按 {@code width / 64} 缩放以兼容 HD 皮肤。
     * 老式 64×32 皮肤没有细臂概念，保持原口径一律 classic。
     */
    public static boolean isSlim(byte[] png) {
        BufferedImage skin = loadBuffered(png);
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 64) {
            return false;
        }
        int scale = skin.getWidth() / 64;
        if (scale < 1) {
            return false;
        }
        return hasTransparency(skin, scale, 50, 16, 2, 4)
                || hasTransparency(skin, scale, 54, 20, 2, 12)
                || hasTransparency(skin, scale, 42, 48, 2, 4)
                || hasTransparency(skin, scale, 46, 52, 2, 12)
                || (isBlackArea(skin, scale, 50, 16, 2, 4)
                && isBlackArea(skin, scale, 54, 20, 2, 12)
                && isBlackArea(skin, scale, 42, 48, 2, 4)
                && isBlackArea(skin, scale, 46, 52, 2, 12));
    }

    /** 1.3.1 QA 钩子：细臂自动判定（与 {@link #isSlim(byte[])} 同实现，便于像素级断言）。 */
    public static boolean isSlimForTests(byte[] png) {
        return isSlim(png);
    }

    /** HMCL hasTransparencyRelative：区域任一像素 alpha != 0xff → true。越界部分跳过。 */
    private static boolean hasTransparency(BufferedImage skin, int scale, int x0, int y0, int w, int h) {
        int sx = x0 * scale, sy = y0 * scale, sw = w * scale, sh = h * scale;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int px = sx + x, py = sy + y;
                if (px >= skin.getWidth() || py >= skin.getHeight()) {
                    continue;
                }
                if (((skin.getRGB(px, py) >>> 24) & 0xFF) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    /** HMCL isAreaBlackRelative：区域每个像素都是不透明纯黑 → true。越界像素按不成立处理。 */
    private static boolean isBlackArea(BufferedImage skin, int scale, int x0, int y0, int w, int h) {
        int sx = x0 * scale, sy = y0 * scale, sw = w * scale, sh = h * scale;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int px = sx + x, py = sy + y;
                if (px >= skin.getWidth() || py >= skin.getHeight()) {
                    return false;
                }
                if (skin.getRGB(px, py) != 0xFF000000) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 皮肤是否带帽子层（hat 区域有任何不透明像素）。3D 预览靠它决定是否画帽壳，
     *  避免无帽皮肤被渲染成黑壳。 */
    public static boolean hasHat(byte[] png) {
        BufferedImage skin = loadBuffered(png);
        if (skin == null) {
            return false;
        }
        int maxX = skin.getWidth();
        int maxY = skin.getHeight();
        for (int y = 0; y < 16 && y < maxY; y++) {
            for (int x = 32; x < 64 && x < maxX; x++) {
                int a = (skin.getRGB(x, y) >> 24) & 0xFF;
                if (a > 10) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 皮肤指定矩形区域是否含任何不透明像素（用于判断某部位的第二图层 overlay 是否该画）。
     *  超出图像边界的部分自动截断，老式 64×32 皮肤下半（overlay 带）全透明 → 返回 false，
     *  不会多画一层壳。 */
    public static boolean hasOverlay(byte[] png, int x0, int y0, int w, int h) {
        BufferedImage skin = loadBuffered(png);
        if (skin == null) {
            return false;
        }
        int maxX = skin.getWidth(), maxY = skin.getHeight();
        for (int y = y0; y < y0 + h && y < maxY; y++) {
            for (int x = x0; x < x0 + w && x < maxX; x++) {
                int a = (skin.getRGB(x, y) >> 24) & 0xFF;
                if (a > 10) {
                    return true;
                }
            }
        }
        return false;
    }
}
