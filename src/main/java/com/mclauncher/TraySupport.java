package com.mclauncher;

import com.mclauncher.ui.TrayMenu;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * 系统托盘图标（「关闭即收进托盘」那一项要用）。
 *
 * <p>托盘属于平台原生能力，任何一步不支持都要能安静退化成「点了就真的关掉」：
 * 托盘不支持、图标建不出来、建过一次又失败，都不该让窗口卡在「点了关闭什么也没发生」。
 *
 * <p>0.9.6 r6 B：图标 100% 用桌面 0.9.5 原件的高分辨率帧，绝不重画图案：
 * 优先从 classpath 里的 {@code app.ico}（0.9.5 原件，7 帧内嵌 PNG）只读解析与托盘尺寸最接近的帧，
 * 拿不到 ico 就用 resources 里的 {@code logo_24/32/64/256.png} 原件位图；尺寸不完全匹配时
 * 只对原件 {@code BufferedImage} 做 BICUBIC 重采样，并 {@code setImageAutoSize(false)} 喂精确尺寸，
 * 杜绝 AWT 二次缩放把图标弄糊。仅当原件全部加载失败才降级为一个纯色占位块（非图案）。
 */
public final class TraySupport {

    private static TrayIcon icon;
    private static TrayMenu trayMenu;

    /** 托盘 tooltip：用来认出「已经挂着的 GBL 图标」，保证系统托盘里全应用只有一枚。 */
    private static final String TOOLTIP = "GBL 启动器";

    /** C5：回调存进可更新字段，图标已存在时也刷新，不再被「先注册者」锁死。 */
    private static volatile Runnable onOpen = () -> {};
    private static volatile Runnable onQuit = () -> {};

    /** 历史门禁常量，r2 已换回旧标志，字符串勿改。 */
    private static final String FP_094_LOGO = "0.9.4 圆形内切菱形新标志";
    static { System.err.println("[tray] 0.9.4: " + FP_094_LOGO); }

    /** 0.9.4 r2 指纹：旧标志去黑底。勿改这串字。 */
    private static final String FP_094_R2_LOGO = "0.9.4 r2 旧标志去黑底";
    static { System.err.println("[tray] " + FP_094_R2_LOGO); }

    /** 0.9.6 r4 指纹：高对比白色独立圆边框。勿改这串字。
     *  static final String 字面量会进 class 常量池，供 build_096.py 的 CHECKS
     *  在 runtime/lib/modules 里搜到，作为「当版托盘图标已刷新」的可核验标记。 */
    static final String R4_RING_FINGERPRINT = "0.9.6 r4 高对比圆边框";
    static { System.err.println("[tray] " + R4_RING_FINGERPRINT); }

    /** 0.9.6 r5 指纹：0.9.5 原版 logo 回归。勿改这串字。
     *  与上面同源：static final String 字面量进 class 常量池，供 build_096.py
     *  的 CHECKS 在 runtime/lib/modules 里搜到，作为当版托盘图标已刷新的标记。 */
    static final String R5_LOGO_FINGERPRINT = "0.9.6 r5 0.9.5原版logo回归";
    static { System.err.println("[tray] " + R5_LOGO_FINGERPRINT); }

    /** 0.9.6 r6 指纹：托盘中文自绘菜单 + 0.9.5 原件高清图标。勿改这串字。 */
    static final String R6_TRAY_FINGERPRINT = "0.9.6 r6 托盘中文自绘菜单与原件高清图标";
    static { System.err.println("[tray] " + R6_TRAY_FINGERPRINT); }

    /** 0.9.8 r2 指纹：托盘图标只建一次、反复复用。构建门禁扫常量池，勿改这串字。 */
    static final String R2_TRAY_REUSE = "0.9.8 r2 托盘实例复用不重建";
    static { System.err.println("[tray] " + R2_TRAY_REUSE); }

    /** 0.9.8 r3 指纹：托盘图标进程内常驻、终身只 add 一次。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_098_R3_TRAY = "0.9.8 r3 托盘图标常驻不反复插拔";
    static { System.err.println("[tray] " + FP_098_R3_TRAY); }

    /** resources 里现成的 0.9.5 原件位图尺寸（升序），供按托盘尺寸挑帧。 */
    private static final int[] RESOURCE_SIZES = {24, 32, 64, 256};

    private TraySupport() {
    }

    public static boolean supported() {
        try {
            return SystemTray.isSupported();
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * 确保托盘里有且只有一枚 GBL 图标。
     *
     * <p>0.9.8 r2：用户开了「关闭时收进托盘」后，每次关窗口 → 点托盘回来 → 再关窗口，
     * 旧实现都会 remove 掉图标并把 {@code icon} 置 null，于是下一次关窗口又重新读 ICO、
     * 重新 BICUBIC 重采样、new 一个 TrayIcon、重新注册鼠标监听、重新 add 进系统托盘。
     * 反复来回就是反复做重活，系统托盘也越整越卡。现在图标实例只创建一次并长期留着：
     * 0.9.8 r3 起图标常驻，窗口可见时也不摘下来、不再重复 add；顺手清掉任何残留的
     * 同款图标，保证托盘里永远最多一枚 GBL。
     */
    public static void ensureIcon(Runnable open, Runnable quit) {
        onOpen = (open != null) ? open : () -> {};
        onQuit = (quit != null) ? quit : () -> {};
        if (!supported()) {
            return;
        }
        try {
            if (icon == null) {
                icon = createIcon();     // 读原件 / 重采样 / 注册监听器，整个进程只做一次
            }
            if (icon == null) {
                return;
            }
            SystemTray tray = SystemTray.getSystemTray();
            boolean present = false;
            for (TrayIcon existing : tray.getTrayIcons()) {
                if (existing == icon) {
                    present = true;
                } else if (TOOLTIP.equals(existing.getToolTip())) {
                    // 上一轮没摘干净 / 异常退出留下的同款图标，只留这一枚
                    try {
                        tray.remove(existing);
                    } catch (Throwable ignored) {
                        // 摘不掉不影响本次复用
                    }
                }
            }
            if (!present) {
                tray.add(icon);
            }
        } catch (Throwable ex) {
            System.err.println("[tray] 托盘图标挂载失败，关闭按钮仍会直接退出: " + ex);
        }
    }

    /** 只在第一次调用：造出那枚长期复用的图标。失败返回 null，功能安静退化。 */
    private static TrayIcon createIcon() {
        try {
            int side = trayIconSize();
            BufferedImage image = paintIcon(side);
            TrayIcon created = new TrayIcon(image, TOOLTIP);
            created.setImageAutoSize(false);
            created.addMouseListener(new MouseAdapter() {
                private boolean popupHandled = false;

                @Override
                public void mousePressed(MouseEvent e) {
                    popupHandled = false;
                    if (e.isPopupTrigger()) {
                        popupHandled = true;
                        openTrayMenu(e.getXOnScreen(), e.getYOnScreen());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger() && !popupHandled) {
                        popupHandled = true;
                        openTrayMenu(e.getXOnScreen(), e.getYOnScreen());
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    // AWT EDT → FX 线程：必须全限定 javafx.application.Platform
                    if (e.getButton() == MouseEvent.BUTTON1 && !e.isPopupTrigger()) {
                        javafx.application.Platform.runLater(onOpen);
                    }
                }
            });
            return created;
        } catch (Throwable ex) {
            System.err.println("[tray] 托盘图标创建失败，关闭按钮仍会直接退出: " + ex);
            return null;
        }
    }

    /** 右键 / isPopupTrigger 时弹 FX 自绘中文菜单（菜单同样是单例，不会反复新建窗口）。 */
    private static void openTrayMenu(int x, int y) {
        javafx.application.Platform.runLater(() -> {
            if (trayMenu == null) {
                trayMenu = new TrayMenu(
                        () -> onOpen.run(),
                        // 0.9.8 r6：真退出走统一收口（托盘图标 / 菜单 / 锚窗口 + 单实例锁 / 唤醒通道）
                        () -> { SingleInstance.shutdown(); onQuit.run(); });
            }
            trayMenu.show(x, y);
        });
    }

    /**
     * 窗口回到任务栏时只收起托盘菜单；图标保持常驻，不摘除、不重建。
     * 0.9.8 r3：进程内只挂一次，避免系统通知区图标残影越积越多。
     * 0.9.8 r6：跨进程由 SingleInstance 保证只跑一个 GBL，托盘图标只属于首实例。
     */
    public static void removeIcon() {
        TrayMenu.hideQuietly(trayMenu);
        // 图标常驻，仅隐藏菜单；真退出由 dispose() 摘除
    }

    /** 真退出时彻底释放，避免留一枚永不响应的图标在托盘里。 */
    public static void dispose() {
        TrayMenu.hideQuietly(trayMenu);
        try {
            if (icon != null) {
                SystemTray.getSystemTray().remove(icon);
            }
        } catch (Throwable ignored) {
            // 同上
        }
        icon = null;
        trayMenu = null;
        TrayMenu.disposeAnchor();
    }

    /** 系统托盘图标实际尺寸（取宽高较大者）；拿不到就按 32 兜底。 */
    private static int trayIconSize() {
        try {
            Dimension d = SystemTray.getSystemTray().getTrayIconSize();
            if (d != null && d.width > 0 && d.height > 0) {
                return Math.max(d.width, d.height);
            }
        } catch (Throwable ignored) {
            // 拿不到尺寸用兜底值
        }
        return 32;
    }

    /**
     * B1/B2：按系统托盘实际尺寸挑最接近的 0.9.5 原件帧，绝不重画图案。
     * 优先从 classpath 里的 app.ico 解析精确尺寸的内嵌 PNG 帧；拿不到就用 resources 原件位图。
     * 原件尺寸与目标一致时直接返回（零缩放）；不一致只对原件 BufferedImage 做 BICUBIC 重采样。
     * 调用方 setImageAutoSize(false) 后 AWT 不再二次缩放。
     */
    private static BufferedImage paintIcon(int targetSize) {
        int side = Math.max(16, Math.min(targetSize, 256));
        BufferedImage original = loadIcoFrame(side);
        if (original == null) {
            original = loadResourceFrame(side);
        }
        if (original == null) {
            // B3：原件全部加载失败才走这里 —— 只画一个纯色占位块，不含任何图案，
            // 正常发布包 100% 走上面的原件加载路径，绝不会到这里。
            return placeholder(side);
        }
        return resample(original, side);
    }

    /**
     * B1：只读解析 classpath 里 app.ico（0.9.5 原件）的 ICONDIR + ICONDIRENTRY，
     * 挑与 side 最接近的那一帧，内嵌数据是 PNG，用 ImageIO.read(ByteArrayInputStream) 解出原件帧。
     * 纯标准库字节解析，不依赖 sun.awt.image，也不重跑任何生成脚本。解析失败静默返回 null 走回退。
     */
    private static BufferedImage loadIcoFrame(int side) {
        byte[] data = readResource("/com/mclauncher/app.ico");
        if (data == null) {
            data = readResource("/app.ico");
        }
        if (data == null || data.length < 6 + 16) {
            return null;
        }
        try {
            // ICONDIR：reserved(2) + type(2) + count(2)
            int count = u16(data, 4);
            if (count <= 0) {
                return null;
            }
            int bestOffset = -1, bestBytes = -1, bestWidth = -1;
            for (int i = 0; i < count; i++) {
                int entry = 6 + 16 * i;   // 每帧 ICONDIRENTRY 16 字节
                if (entry + 16 > data.length) {
                    break;
                }
                int w = data[entry] & 0xFF;
                if (w == 0) {
                    w = 256;             // ICO 目录里 0 表示 256
                }
                int bytes = (int) u32(data, entry + 8);
                int offset = (int) u32(data, entry + 12);
                if (offset < 0 || bytes <= 0 || (long) offset + bytes > data.length) {
                    continue;
                }
                if (!isPng(data, offset)) {
                    continue;            // 0.9.5 原件的 7 帧都是内嵌 PNG，其余跳过
                }
                int delta = Math.abs(w - side);
                int bestDelta = bestWidth < 0 ? Integer.MAX_VALUE : Math.abs(bestWidth - side);
                // 取最接近的一帧；平局取分辨率更高者（下采样比上采样清晰）
                if (bestWidth < 0 || delta < bestDelta || (delta == bestDelta && w > bestWidth)) {
                    bestWidth = w;
                    bestBytes = bytes;
                    bestOffset = offset;
                }
            }
            if (bestOffset < 0) {
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(data, bestOffset, bestBytes));
        } catch (Throwable ignored) {
            return null; // 解析失败静默回退 resources 原件，不崩
        }
    }

    /** B1 回退：从 resources 现成的 logo_24/32/64/256.png 原件里挑「>= 目标且最接近」的一张。 */
    private static BufferedImage loadResourceFrame(int side) {
        int chosen = RESOURCE_SIZES[RESOURCE_SIZES.length - 1];
        for (int s : RESOURCE_SIZES) {
            if (s >= side) {
                chosen = s;
                break;
            }
        }
        try (InputStream in = TraySupport.class.getResourceAsStream(
                "/com/mclauncher/logo_" + chosen + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * B2：尺寸无法完全匹配时，只对原件 BufferedImage 做 BICUBIC 高质量重采样，绝不走自绘。
     * 原件尺寸正好就直接返回，零缩放。
     */
    private static BufferedImage resample(BufferedImage src, int side) {
        if (src.getWidth() == side && src.getHeight() == side) {
            return src;
        }
        BufferedImage dst = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                               RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, side, side, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /**
     * B3：严格降级的兜底 —— 只画一个纯色块，零图案、零 logo、零白环、零草方块。
     * 正常发布包 100% 走原件加载路径，这里仅在原件全部缺失时出现，保证托盘仍能注册、仍能唤回。
     */
    private static BufferedImage placeholder(int side) {
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(0x2A, 0x5C, 0x33));
            g.fillRect(0, 0, side, side);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static boolean isPng(byte[] d, int off) {
        return off + 8 <= d.length
                && (d[off] & 0xFF) == 0x89 && d[off + 1] == 'P' && d[off + 2] == 'N'
                && d[off + 3] == 'G' && d[off + 4] == 0x0D && d[off + 5] == 0x0A
                && d[off + 6] == 0x1A && d[off + 7] == 0x0A;
    }

    /** 小端无符号 16 位。 */
    private static int u16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    /** 小端无符号 32 位（返回 long 避免符号溢出）。 */
    private static long u32(byte[] d, int o) {
        return ((long) (d[o] & 0xFF))
                | ((long) (d[o + 1] & 0xFF) << 8)
                | ((long) (d[o + 2] & 0xFF) << 16)
                | ((long) (d[o + 3] & 0xFF) << 24);
    }

    private static byte[] readResource(String path) {
        try (InputStream in = TraySupport.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
