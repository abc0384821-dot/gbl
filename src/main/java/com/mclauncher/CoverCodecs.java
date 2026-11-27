package com.mclauncher;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.IIOServiceProvider;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javafx.scene.image.Image;

/**
 * 封面解码（0.8.6 三次修订）。
 *
 * <p><b>背景：</b>Modrinth 的 search / project 接口现在把 {@code icon_url} 全量返回成
 * {@code _96.webp}，而 JavaFX 21 的 {@link Image} 只认 PNG / JPEG / GIF / BMP —— 下载成功也照样
 * 解码失败，用户看到的就是「图片根本没有」。OpenJFX 官方目前仍无原生 WebP 计划，HMCL 的做法
 * 是打包 TwelveMonkeys 的 WebP 解码器，用 ImageIO 解成 BufferedImage 再交给 JavaFX。这里照此办理。
 *
 * <p>解码器 class 由构建脚本打进 {@code com.mclauncher} 模块 jar（纯 Java、无 native 库），
 * 用反射注册 / 兜底实例化，避免给 {@code module-info.java} 引入自动模块依赖。
 */
public final class CoverCodecs {

    private static final String WEBP_SPI_CLASS =
            "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi";
    private static volatile boolean installed = false;

    static {
        install();
    }

    private CoverCodecs() {
    }

    /** 把 WebP reader 挂进 ImageIO 注册表；jpackage 模块化下 ServiceLoader 未必能自动发现。 */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            Class<?> c = Class.forName(WEBP_SPI_CLASS);
            ImageReaderSpi spi = (ImageReaderSpi) c.getDeclaredConstructor().newInstance();
            IIORegistry.getDefaultInstance().registerServiceProvider(spi, ImageReaderSpi.class);
            // 0.9.2 r4：只在「注册成功之后」才置 installed=true；此前失败也置 true，
            // 导致后续 decode 永不重试，是「别人机器封面全不显示」的隐性放大器。
            installed = true;
            System.out.println("[image] WebP 解码器已就绪 (TwelveMonkeys)");
        } catch (Throwable t) {
            // 0.9.2 r4：失败不再静默吞异常，打印完整异常类名 + 堆栈，便于跨机器排查。
            // installed 保持 false，decode() 下次会重试 install()。
            System.err.println("[image] WebP 解码器注册失败（WebP 封面将显示占位图）: " + t);
            t.printStackTrace(System.err);
        }
    }

    /**
     * 把任意封面字节解成 JavaFX Image。先走 JavaFX 原生格式，失败再走 ImageIO（含 WebP）。
     * 返回 null 表示解码失败，调用方保留占位图。
     */
    public static Image decode(byte[] data, double size) {
        if (data == null || data.length == 0) {
            return null;
        }
        // 0.9.2 r4：静态初始化那次注册若失败（installed 仍为 false），每次解码都允许重试一次，
        // 不再因为一次失败就永久放弃 WebP 解码。
        if (!installed) {
            install();
        }
        try {
            Image direct = new Image(new ByteArrayInputStream(data), size, size, true, true);
            if (!direct.isError()) {
                return direct;
            }
        } catch (Exception ignored) {
            // WebP / 其它 JavaFX 不认识的格式走下面
        }
        BufferedImage bi = decodeViaImageIo(data);
        if (bi == null) {
            return null;
        }
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!ImageIO.write(bi, "png", png)) {
                return null;
            }
            Image img = new Image(new ByteArrayInputStream(png.toByteArray()),
                    size, size, true, true);
            return img.isError() ? null : img;
        } catch (Exception ex) {
            return null;
        }
    }

    /** 注册表优先；万一注册失败，直接用 SPI 造一个 reader，绕开 ServiceLoader。 */
    private static BufferedImage decodeViaImageIo(byte[] data) {
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
            if (bi != null) {
                return bi;
            }
        } catch (Exception ignored) {
            // 落到下面的直接实例化
        }
        ImageReader reader = null;
        ImageInputStream in = null;
        try {
            Class<?> c = Class.forName(WEBP_SPI_CLASS);
            ImageReaderSpi spi = (ImageReaderSpi) c.getDeclaredConstructor().newInstance();
            reader = spi.createReaderInstance();
            in = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
            reader.setInput(in);
            return reader.read(0);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (reader != null) {
                reader.dispose();
            }
        }
    }
}
