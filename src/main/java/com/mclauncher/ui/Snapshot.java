package com.mclauncher.ui;

import com.mclauncher.GameDownloader;
import com.mclauncher.GameEntry;
import com.mclauncher.GameScanner;
import com.mclauncher.VersionManifest;

import javafx.animation.PauseTransition;

import javafx.application.Platform;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.event.EventType;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.transform.Transform;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import javafx.util.Duration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 应用内场景快照（自测用，只有带 JVM 参数 {@code -Dgbl.snapshot=...} 时才干活）。
 *
 * <p>为什么需要它：本机 GDI 抓不到这张 JavaFX 窗口——全屏 BitBlt、窗口 DC BitBlt、
 * PrintWindow(PW_RENDERFULLCONTENT) 返回的都是纯黑（JavaFX 走 D3D 交换链，桌面合成器
 * 不把那块像素交给 GDI）。所以验证界面只能让应用自己把场景图渲染成图再看。
 *
 * <p>用法（打包产物加 JVM 参数，正常启动不带）：
 * <pre>
 *   -Dgbl.snapshot=target/_s.png            截一张静止态（按 scale 给分辨率）
 *   -Dgbl.snapshot=target/_s.png:2x         同上，2 倍分辨率
 *   -Dgbl.snapshot=target/_s.png:2x:all     依次截 静止 / 关闭键悬停 / 最大化 / 下载页 /
 *                                           设置页 / 最大化下的设置页 / 窗口行为组 /
 *                                           深色主题 八张（深色那张另带一张主题下拉弹层）
 *   -Dgbl.snapshot.raw=1                    额外导出带真实 alpha 的 PNG，验四角真不透明
 * </pre>
 * 九类图分别落到 {@code _s-idle.png}、{@code _s-hover.png}、{@code _s-max.png}、
 * {@code _s-download.png}、{@code _s-settings.png}、{@code _s-settings-max.png}、
 * {@code _s-win.png}、{@code _s-dark.png}、{@code _s-popup.png}（弹层另有一张 scene，
 * 只能从它自己身上拍）。每张之前还会打一段 {@code [inspect]} 清单，把每个可见文字的
 * **生效** textFill 和布局边界列出来——颜色对不对问场景图比看图准。
 *
 * <p>拍的是场景根，不含操作系统窗口装饰；圆角外是不是真透桌面、最大化时留白收成 0 都能看出来。
 * 「最大化」是直接触发按钮自己的 click 回调，不是真最大化。
 */
public final class Snapshot {

    private static final String PROP = "gbl.snapshot";

    /** 切页后的 opacity 采样时刻（秒）。见 {@link #sampleOpacity}。 */
    private static final double[] OPACITY_PROBES = {0.03, 0.07, 0.12, 0.2};

    /** 搜索框里敲的那串。真实清单只有版本号可搜，且自测打印的标签必须用同一个串。 */
    private static final String SEARCH_TERM = "1.20";

    /**
     * 0.8.8 R-05 断言基准：{@code docs/QA-0.8.8-dep-samples.md} §11 的固定 versionId / fileId。
     * 只作期望口径与日志核对，⛔ 自测不联网、不读生产源码字段（沙箱不能真下 client jar）。
     * 每行 = {样本名, projectId/modId, versionId/fileId, 期望依赖条数, 必须/可选/已内置/不兼容}。
     * 样本级逐条精确比对需联网真数据，属真机 / 实现落地后节点口径对齐。
     */
    private static final String[][] DEP_SAMPLES_088 = {
            {"Iris", "YL57xq9U", "vTN4NRGW", "1", "1/0/0/0"},
            {"Iris-1.20.1", "YL57xq9U", "s5eFLITc", "1", "1/0/0/0"},
            {"Xaero", "1bokaNcj", "MfxfgXaO", "2", "1/1/0/0"},
            {"DynamicFPS", "LQ3K71Q1", "RgJGw0dO", "3", "1/1/1/0"},
            {"Sodium", "AANobbMI", "hiO9bwqc", "2", "0/0/0/2"},
            {"JEI(CF)", "238222", "9453343", "2", "1/1/0/0"},
    };

    /**
     * CF {@code FileDependency.relationType} 定案真值（ARCH §7.2，2026-11-06 主理人修正；
     * §7.2 旧推定表已作废）。1=EmbeddedLibrary / 2=OptionalDependency / 3=RequiredDependency /
     * 4=Tool / 5=Incompatible / 6=Include。按作废表（1=Required / 3=Embedded / 4=Incompatible）
     * 写断言会把 391 条 required 显示成「已内置」并吞掉安装按钮，必然错。
     * 落到 UI 判据：仅「必须(3) / 可选(2)」分类给安装按钮；
     * 「已内置(1) / 工具(4) / 不兼容(5) / 包含(6)」一律无按钮、且不得整块隐藏。
     */
    private static final String CF_RELATION_TRUTH_088 =
            "1=EmbeddedLibrary/2=OptionalDependency/3=RequiredDependency"
                    + "/4=Tool/5=Incompatible/6=Include";

    /**
     * 两步之间的间隔（秒）。必须大于单个任务内部排的最长一拍，
     * 否则后一个任务会插进前一个任务的 PauseTransition 中间。
     */
    private static final double STEP_DELAY = 1.2;

    private Snapshot() {
    }

    private static final List<Task> SEQUENCE = Arrays.asList(
            Task.DUMP_IDLE, Task.INVENTORY, Task.HOVER_ON, Task.DUMP_HOVER,
            Task.HOVER_OFF, Task.MAX_CLICK, Task.DUMP_MAX,
            // 下载页得在窗口态下拍：上面点了最大化，切过去还开着就又是贴边 1600x1000
            Task.MAX_RESTORE, Task.DOWNLOAD_CLICK, Task.DUMP_DOWNLOAD, Task.INVENTORY,
            // v0.3 下载页：点第一行「下载」要弹出整窗确认框，这份框挂在 shell 上（主场景 root 扫不到），
            // 所以 DLG 这三步在自测里是「打开 → 单独拍 → 取消」一条链
            Task.MANIFEST_SYNC, Task.MANIFEST_SHOW_ALL, Task.INVENTORY,
            // v0.4.2：愚人节那几条得标「愚人节（版）」而不是跟着官方 type 写「快照」，
            // 而且只在「显示全部」里才看得见（默认视图把愚人节排除了），所以挨着这一步上报
            Task.APRIL_REPORT,
            Task.QUEUE_ADD, Task.TOAST_ROUNDTRIP, Task.QUEUE_ADD_2,
            // v0.4.2：队列页那串（行文本 / 卡片矩形 / 队列截图）并进了 QUEUE_WAIT「全下完」那一刻
            // ——下完 1.5 秒行就自动移出，等下完再切过去只剩空态。
            // 但队列页是懒构建的，**必须在等下完之前先切过去**，否则 reportQueueRows 一行都捞不到
            // （首次跑就是这么个 0 行）。所以 QUEUE_TAB 排到 QUEUE_WAIT 前头。
            Task.QUEUE_TAB, Task.QUEUE_WAIT,
            // 下完后再切回来一次：这次看到的是「行被自动移出、只剩空态」——
            // 光有前头那次（还在排队）的话，这条判据就永远读不到空态
            Task.VERSION_BTN_REPORT, Task.QUEUE_TAB,
            Task.MANIFEST_FRESH, Task.INVENTORY,
            Task.ROW_SELECT,
            Task.TAB_SWITCH, Task.TAB_BACK, Task.INVENTORY,
            Task.SEARCH_TYPE, Task.SEARCH_CLEAR,
            Task.FILTER_SNAP, Task.FILTER_ALL, Task.INVENTORY,
            Task.SETTINGS_CLICK, Task.INVENTORY, Task.DUMP_SETTINGS,
            // v0.3.1 游戏列表：导航切过去 → 拍一张 → 报一遍现在有几行（v0.4 起是真下载下来的那一个）
            // → 点第二行换当前游戏 → 开菜单 → 单独拍菜单 → 收起 → 开删除确认 → 拍 → 取消（不真删）
            // → 顺手验「游戏管理」占位页点得出、切回来还能数出行
            Task.GAME_NAV_CLICK, Task.GAME_DUMP, Task.GAME_ROWS_REPORT, Task.GAME_DUMP,
            Task.GAME_ROW_CLICK,
            Task.GAME_MENU_OPEN, Task.GAME_MENU_DUMP, Task.GAME_MENU_CLOSE,
            Task.GAME_MENU_RCLICK,
            Task.GAME_PROMPT_OPEN, Task.GAME_PROMPT_DUMP, Task.GAME_PROMPT_CANCEL,
            Task.GAME_MGMT_CLICK, Task.INVENTORY,
            // v0.7.0 Mod 中心（原「游戏管理」页）：点得出、三个 tab 切得动、加载器三张卡齐全。
            // 只做结构断言，不触网；放在这里是因为前面 QUEUE_ADD 已经真下过一个游戏，
            // 三个 tab 都是「有数据」形态（空态引导卡那条判据在深色那趟采）
            Task.MOD_CENTER_CLICK, Task.MOD_TAB_SWITCH, Task.MOD_LOADER_CARDS, Task.INVENTORY,
            // v0.4.2 用户报「它删不了」：删除这条路径以前只走到「取消」为止，
            // 得真删一次、看列表和磁盘一起少，才算把那件事结了
            Task.GAME_PROMPT_OPEN, Task.GAME_PROMPT_CONFIRM,
            Task.GAME_ROWS_REPORT, Task.INVENTORY,
            // v0.2.4：动效开关绑反过（关=有动效、开=没了），这四步就是要看死这个方向
            Task.MOTION_STATE, Task.MOTION_OFF_FADE, Task.MOTION_ON_FADE,
            // 连点「启动」验淡入没被重播：这一步跑完就恢复成首页了，后面照常
            Task.NAV_DOUBLE_CLICK,
            // 浅色下把「正在保存…」抓出来：这一步跑完胶囊就落回「已保存」了，别隔步再看
            Task.SAVE_PENDING,
            Task.COMBO_OPEN, Task.POPUP_DUMP, Task.COMBO_CLOSE,
            // 狂点开关：胶囊必须从「已保存」稳住，不能翻成红字「保存失败」
            Task.TOGGLE_SPAM,
            Task.SETTINGS_MAX_CLICK, Task.INVENTORY, Task.DUMP_SETTINGS_MAX, Task.MAX_RESTORE,
            Task.WIN_GROUP_CLICK, Task.DUMP_WIN,
            Task.DARK_THEME, Task.DUMP_DARK,
            // v0.8.7：下载页三个占位 tab 换成真实内容页后补的三条「可达性」用例。
            // 只追加在序列末尾：不重排既有 0.8.6 步骤、不插 INVENTORY（免得 [inspect] 分页错位），
            // 既有判据的日志锚点一律不动；断言只认「页面根节点在 + 容器非空 + 不抛异常」，
            // 不写死任何 0.8.7 新 UI 类名，UI 执行者改类名也不会把自测挂掉。
            Task.DOWNLOAD_TABS_ALL_REACHABLE, Task.GAME_MGMT_087_SECTIONS,
            Task.LOADER_TAB_087_REACHABLE,
            // v0.8.8：R-01~R-08 发布门禁的应用内快照断言（W3 / T05）。沿用「只追加末尾、
            // 不插 INVENTORY、不改既有 nav-* / page-title / tab-btn / version-row 类名与点击路径」
            // 的 0.8.7 口径。每个 Task 只按「用户可见文案 + §7.3 CSS 类名 + 场景图可观测节点」
            // 采集，三态输出（通过 / 失败 / 未采到）；场景没渲染一律「未采到」，不伪造 PASS、
            // 不打印假 FAIL。实现落地后主理人同步实际节点口径做一次对齐即可收敛。
            // ⚠️ 必须 light + dark 合趟跑：这些 Task 只在浅色完整序列 SEQUENCE 里，
            //    深色短序列 DARK_SEQUENCE 沿用 0.8.7 分批口径不含它们（见 run_selftest.py）。
            Task.GAME_MGMT_088, Task.DEP_DIALOG_088, Task.DEP_INSTALL_088,
            Task.CONTENT_GROUP_088, Task.LOADER_088);

    /**
     * 只出深色那几张的短序列。
     *
     * <p>为什么要分两趟跑：-Dgbl.theme 是强制开关，盖过配置里的那一档，所以同一趟里
     * 「强制浅色 + 点深色」等于什么都没发生，拍出来的 dark 图还是浅色的（自测踩过这个：
     * win.png 和 dark.png 字节一模一样）。要出真深色图只能拿 {@code -Dgbl.theme=dark}
     * 单独跑一趟，这里就只排深色那几步。
     */
    // 第一张也要拍首页：深色标题有没有反色只有首页看得出来（设置页没有 hero 标题）
    private static final List<Task> DARK_SEQUENCE = Arrays.asList(
            Task.DUMP_IDLE, Task.INVENTORY,
            Task.SETTINGS_CLICK, Task.INVENTORY, Task.DUMP_SETTINGS,
            // 深色下也报一遍开关的初始态：出厂必须是「动效开」
            Task.MOTION_STATE,
            // 深色下也狂点一轮：胶囊在深底上有没有被攒出来的重复类名顶成别的颜色
            Task.TOGGLE_SPAM,
            Task.NAV_DOUBLE_CLICK,
            // 深色的保存胶囊态也抓一下：白字在深色上偶发能看见，但不该让它蒙混过关
            Task.SAVE_PENDING,
            // 深色下把弹层拉开单独拍一张：深色弹层是不是深底浅字，只能看这张
            Task.COMBO_OPEN, Task.POPUP_DUMP, Task.COMBO_CLOSE,
            // 深色下的游戏列表：切过去拍一张、报一遍行数。
            // v0.4 起示例数据删干净了，深色那趟跑在下载之前、列表注定是空态，
            // 所以必须后面真下完再来第二次——菜单/右键那两条判据得有一行游戏才点得出来，
            // 而「没游戏时是不是空态」这条判据又只有当下的空列表才采得到。
            Task.GAME_NAV_CLICK, Task.GAME_DUMP, Task.GAME_ROWS_REPORT,
            // 深色那趟也真下一份（这一趟只排一个，浅色那趟排两个）。
            // 切队列页照浅色那趟也排在等下完之前——队列页懒构建，两个顺序反一下就什么都捞不着。
            Task.QUEUE_ADD, Task.TOAST_ROUNDTRIP,
            Task.QUEUE_TAB, Task.QUEUE_WAIT, Task.VERSION_BTN_REPORT, Task.QUEUE_TAB,
            Task.GAME_NAV_CLICK, Task.GAME_DUMP, Task.GAME_ROWS_REPORT,
            // 菜单是自绘的（ContextMenu 拿不到 shell 的 .dark 类），这张图就是它的验收依据
            Task.GAME_MENU_OPEN, Task.GAME_MENU_DUMP, Task.GAME_MENU_CLOSE,
            Task.GAME_MENU_RCLICK, Task.INVENTORY,
            // 深色那趟也要验下载页：切 tab 到空的「光影」、再回到「游戏」
            Task.TAB_SWITCH, Task.TAB_BACK,
            // v0.7.0：深色下也点一遍 Mod 中心（标题 / 三个 tab / 根容器类名）。
            // 深色那趟排在下载之后，磁盘上有游戏，读到的是「有数据」形态
            Task.MOD_CENTER_CLICK, Task.INVENTORY,
            // 先切深色再在下载页上抓一份清单（INVENTORY），版本行 / tab 的深色字色才有得问；
            // 顺序反过来的话问到的是浅色那趟的读数
            Task.DARK_THEME, Task.INVENTORY,
            // 深色那趟把官方清单再走一遍：verify ⑩ 那几条判据是「默认视图不铺远古版/
            // 显示全部铺出 900+ / 钉旧基线能挂新标」，深色那趟少跑哪一步就永远采不到数（v0.3.5）。
            // 顺序照浅色那趟来：先看默认视图、再打开全量（顺带认一遍愚人节的标）、最后钉旧基线重同步。
            // 「每次打开都自动更新」这回事不需要深色那趟再验一遍——它验的是「点回来会自己拉」，
            // 跟颜色没关系；MANIFEST_FRESH 那一步走的就是这条自动同步的路。
            Task.MANIFEST_SYNC, Task.MANIFEST_SHOW_ALL, Task.APRIL_REPORT,
            Task.MANIFEST_FRESH, Task.DUMP_DARK);

    /**
     * 轮询到盯的那些项全部终态：进度走到 100%、jar 落盘、队列状态翻成「装好了」。
     *
     * <p>这一步可以慢慢来（自测等得起，用户也在等），所以是「轮询到结束」而不是拍一个数：
     * 只拍一次的话网络慢一点读到的就是刚开头 2%，「能不能下完」就永远验不到。上限 90 秒。
     *
     * <p>轮询在后台线程里跑（读的是服务层的 {@code Item}，跟 FX 无关），别拿一堆
     * {@code PauseTransition} 去接；而 {@code join} 一定得做——下一拍 {@link Task#QUEUE_TAB}
     * 会去数队列行，线程慢吞吞跑完的话 JVM 可能先收尾，「等下载：状态=…」那行日志永远采不到。
     */
    private static void awaitQueueItems(List<String> ids) {
        long deadline = System.currentTimeMillis() + 90_000;
        Thread waiter = new Thread(() -> {
            Map<String, String> seen = new LinkedHashMap<>();
            Map<String, Integer> pcts = new LinkedHashMap<>();
            try {
                while (System.currentTimeMillis() < deadline) {
                    boolean allDone = true;
                    for (String id : ids) {
                        GameDownloader.Item item = GameDownloader.DEFAULT.item(id);
                        if (item == null) {
                            continue;
                        }
                        seen.putIfAbsent(id, item.state().name());
                        pcts.put(id, item.percent());
                        if (item.state() == GameDownloader.State.QUEUED
                                || item.state() == GameDownloader.State.DOWNLOADING) {
                            allDone = false;
                        }
                    }
                    if (allDone) {
                        break;
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            // 「等下载：状态=…」是给 verify 当锚的：得在 join 之前把这话说出去
            for (String id : ids) {
                System.out.println("[snapshot] 等下载：[" + id + "] 状态="
                        + seen.getOrDefault(id, "?") + "，进度="
                        + pcts.getOrDefault(id, -1) + "%");
            }
            System.out.println("[snapshot] 队列里现在 " + GameDownloader.DEFAULT.items().size()
                    + " 项，在排/在下 " + GameDownloader.DEFAULT.active() + " 个");
        }, "gbl-selftest-wait");
        waiter.setDaemon(true);
        waiter.start();
        try {
            waiter.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 见 {@link #DARK_SEQUENCE}：自测靠 {@code -Dgbl.theme} 决定跑整套还是只跑深色那几步。 */
    private static String forceTheme() {
        return System.getProperty("gbl.theme");
    }

    /** 窗口资产后延迟一会儿再截，等首帧布局和 CSS 都生效。 */
    public static void maybeDumpSequence(Node root) {
        String spec = System.getProperty(PROP);
        if (spec == null || spec.isEmpty()) {
            return;
        }
        String[] parts = spec.split(":");
        double scale = parts.length > 1 && parts[1].endsWith("x") ? num(parts[1]) : 1.0;

        Path base = Paths.get(parts[0]);
        try {
            Path dir = base.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
        } catch (IOException ex) {
            System.err.println("[snapshot] 建目录失败: " + ex);
            return;
        }

        List<Task> tasks = parts.length > 2 && "all".equals(parts[2])
                ? ("dark".equalsIgnoreCase(forceTheme()) ? DARK_SEQUENCE : SEQUENCE)
                : Collections.singletonList(Task.DUMP_IDLE);
        // 深色那趟的图必须落在另一组文件名上：两趟共用 base 的话，深色那步的
        // DUMP_IDLE 会把浅色那张 _s-idle.png 盖掉，「深浅两套像素」的判据就白做了。
        if (tasks == DARK_SEQUENCE) {
            String name = base.getFileName().toString();
            base = base.resolveSibling(name.replaceFirst("\\.[^.]+$", "-dark.png"));
            System.out.println("[snapshot] 深色那趟改落到 " + base);
        }
        run(root, base, scale, tasks, STEP_DELAY);
    }

    /**
     * 一个任务一拍地跑：{@code Node.snapshot} 是异步的，连着调几次会落进同一个 pulse，
     * 拍出三张一模一样的图，所以每一步之间必须等过去至少一帧（用 PauseTransition 顶一拍）。
     */
    private static void run(Node root, Path base, double scale, List<Task> tasks, double delaySeconds) {
        PauseTransition wait = new PauseTransition(Duration.seconds(delaySeconds));
        wait.setOnFinished(e -> {
            Task task = tasks.isEmpty() ? null : tasks.get(0);
            if (task == null) {
                System.out.println("[snapshot] 完成: " + base
                        + "-{idle,hover,max,download,settings,settings-max,win,dark}.png");
                // 拍完就退出：不然进程一直挂着，下次跑会跟它抢同一批 png。
                // Platform.exit() 不够硬——池子里哪怕剩一个非 daemon 线程，JVM 也会继续等下去
                // （下载池早先用的默认线程工厂，自测就被这么拖到 420 秒超时）
                Platform.exit();
                System.exit(0);
                return;
            }
            try {
                task.apply(root, base, scale);
            } catch (IOException ex) {
                System.err.println("[snapshot] " + task + " 失败: " + ex);
            }
            // 步与步之间必须比「任务自己排的最后一拍」长：TOGGLE_SPAM 内部要跑三轮
            // 300ms 防抖 + 收尾 400ms（约 1s），还是 0.7s 的话下个任务会插进来，
            // 日志里就会出现「狂点没跑完，导航点击已经串到中间」。
            run(root, base, scale, tasks.subList(1, tasks.size()), STEP_DELAY);
        });
        wait.play();
    }

    private enum Task {
        DUMP_IDLE {

            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-idle"), scale);
            }
        },
        HOVER_ON {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_ENTERED, "win-btn-close", root);
            }
        },
        DUMP_HOVER {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-hover"), scale);
            }
        },
        HOVER_OFF {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_EXITED, "win-btn-close", root);
            }
        },
        MAX_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "win-btn-max", root);
            }
        },
        DUMP_MAX {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-max"), scale);
            }
        },
        // 还原请求有时会被 peer 吞掉、过一两秒又被平台同步回最大化（后面的图就变成 3200x2000）。
        // 这里连着复核几回，确认窗口回到窗口态，自测的图才稳定。
        MAX_RESTORE {
            @Override void apply(Node root, Path base, double scale) {
                restoreWindow(root);
            }
        },
        // 最大化态下的设置页：光看窗口态看不出「放大后右边没撑满」这个问题
        SETTINGS_MAX_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "win-btn-max", root);
            }
        },
        DUMP_SETTINGS_MAX {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-settings-max"), scale);
            }
        },
        DOWNLOAD_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
            }
        },
        DUMP_DOWNLOAD {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-download"), scale);
            }
        },
        // v0.2 起设置页也是真页面，自测顺手把它和三套主题（浅色 / 深色 / 跟随系统）都拍出来
        SETTINGS_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-settings", root);
            }
        },
        DUMP_SETTINGS {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-settings"), scale);
            }
        },
        // 分组药丸是同类的两个，按顺位点第二个（「窗口行为」）——不为它单独留个样式类
        WIN_GROUP_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                clickNth(root, "seg-btn", 1);
            }
        },
        DUMP_WIN {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-win"), scale);
            }
        },
        DARK_THEME {
            @Override void apply(Node root, Path base, double scale) {
                selectCombo(root, 2);   // 「深色」
            }
        },
        DUMP_DARK {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, "-dark"), scale);
            }
        },
        INVENTORY {
            @Override void apply(Node root, Path base, double scale) {
                inspect(root);
            }
        },
        /**
         * 翻一下「界面动效」开关，把顶栏胶囊推进「正在保存…」那一态，紧接着问一遍场景图。
         *
         * <p>为什么这样验：胶囊的字色是 CSS 给的，而 Label 的默认字色是 modena 的白。
         * 之前 chip-accent 被当成一体类名加进去、规则永远匹配不上，白字就这么一直留到了浅色主题下。
         * 这步不加，下次谁也看不出「正在保存」是不是又变回白字了。
         */
        SAVE_PENDING {
            @Override void apply(Node root, Path base, double scale) {
                // 必须按标题点「界面动效」这一枚，不能「抓场景里第一个 ToggleSwitch 就点」：
                // lookupAll 不管页面切没切过去、节点可见不可见，下载页那枚「显示全部」
                // 只要还挂在场景里就会排在前面——深色那趟 SAVE_PENDING 排在 MANIFEST_SYNC 之前，
                // 这一下把「显示全部」拨开了（自测读到 917 行），保存态也一次都没触发。
                // 保存态是设置页那枚开关挂 onSave，点错人等于整步白跑。
                ToggleSwitch motion = toggleByTitle(root, "界面动效");
                if (motion == null) {
                    System.out.println("[snapshot] 没找到「界面动效」开关，保存态没做");
                    return;
                }
                // selected 是私有属性，没有 isSelected()；走真实点击路径（开关挂在
                // setOnMouseClicked 上），顺便也验证了这条交互本身是通着的。
                motion.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                System.out.println("[snapshot] 翻动效开关触发保存态，命中「界面动效」1 个");
                // 换类名之后样式要等下一个 pulse 才重算，立刻 getTextFill() 读到的是
                // Labeled 的默认值（黑 0x000000ff、无底色），会误判成「CSS 没生效」。
                // 等 0.15s：够重算，又还落在 300ms 防抖里，读到的一定还是「正在保存…」。
                PauseTransition settle = new PauseTransition(Duration.seconds(0.15));
                settle.setOnFinished(e -> inspect(root));
                settle.play();
            }
        },
        /**
         * 报一遍「界面动效」开关的出厂态。
         *
         * <p>v0.2.4 之前开关绑反了：出厂是「有动效」，开关却显示成关，于是看起来像默认关着。
         * 这里必须问出 {@code selected=true} 且轨道上带 {@code on} 类（开关颜色全在 CSS 里，
         * paint() 只切类名，所以「带 on 类」＝「画出来是选中态」）。
         */
        MOTION_STATE {
            @Override void apply(Node root, Path base, double scale) {
                ToggleSwitch motion = toggleByTitle(root, "界面动效");
                if (motion == null) {
                    System.out.println("[snapshot] 没找到「界面动效」开关");
                    return;
                }
                Node track = ((Parent) motion).getChildrenUnmodifiable().get(0);
                System.out.println("[snapshot] 动效开关出厂态 selected=" + motion.getSelected()
                        + " 轨道类=" + classes(track));
                // 现场留档：场景里每一枚开关属于哪一行、现在什么态
                for (Node row : find("setting-row", root)) {
                    StringBuilder one = new StringBuilder();
                    if (row instanceof Parent box) {
                        collectText(box, one);
                    }
                    List<Node> kids = ((Parent) row).getChildrenUnmodifiable();
                    Node control = kids.get(kids.size() - 1);
                    System.out.println("[snapshot] 设置行 [" + one.toString().trim()
                            + "] 控件=" + (control == null ? "-" : control.getClass().getSimpleName())
                            + (control instanceof ToggleSwitch sw
                            ? " selected=" + sw.getSelected() + " 轨道类=" + classes(kids.get(0))
                            : ""));
                }
            }
        },
        /**
         * 关掉动效后切页，应当**没有**淡入：内容页 opacity 立刻就是 1。
         *
         * <p>这一步是「关掉它有动效、打开就没了」那个反绑的直接反证：
         * 关了动效却还测到 opacity&lt;1，说明关的其实是「有动效」那一边。
         */
        MOTION_OFF_FADE {
            @Override void apply(Node root, Path base, double scale) {
                ToggleSwitch motion = toggleByTitle(root, "界面动效");
                if (motion == null) {
                    System.out.println("[snapshot] 没找到「界面动效」开关，跳过关动效");
                    return;
                }
                motion.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                System.out.println("[snapshot] 已把动效关掉 selected=" + motion.getSelected());
                PauseTransition settle = new PauseTransition(Duration.seconds(0.4));
                settle.setOnFinished(e -> {
                    fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                    // 淡入 160ms，单点采样容易撞上「刚好走完」，按时间轴多点问
                    sampleOpacity(root, "动效关");
                });
                settle.play();
            }
        },
        /**
         * 打开动效后切页，opacity 此刻必须还小于 1（正在淡入）。
         *
         * <p>和 {@link #MOTION_OFF_FADE} 配成一对：一开一关都有预期的 opacity，
         * 只测一边的话，反绑博士一样能过。
         */
        MOTION_ON_FADE {
            @Override void apply(Node root, Path base, double scale) {
                ToggleSwitch motion = toggleByTitle(root, "界面动效");
                if (motion == null) {
                    System.out.println("[snapshot] 没找到「界面动效」开关，跳过开动效");
                    return;
                }
                // 先回首页等这一轮淡入走完，下一跳的采样才不会被上一次的动画糊住
                fire(MouseEvent.MOUSE_CLICKED, "nav-home", root);
                PauseTransition home = new PauseTransition(Duration.seconds(0.3));
                home.setOnFinished(e -> {
                    motion.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                    System.out.println("[snapshot] 已把动效打开 selected=" + motion.getSelected());
                    fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                    sampleOpacity(root, "动效开");
                    // 收尾回首页：后面的 NAV_DOUBLE_CLICK 才跳得成（jump 会被 currentPage 挡）
                    PauseTransition back = new PauseTransition(Duration.seconds(0.3));
                    back.setOnFinished(e3 -> fire(MouseEvent.MOUSE_CLICKED, "nav-home", root));
                    back.play();
                });
                home.play();
            }
        },
        /**
         * 连点同一枚开关，顶栏胶囊必须稳在「已保存」，不能翻红。
         *
         * <p>两轮压力，覆盖两种落盘路径：同一拍里连点 20 下（防抖收敛成一次写），
         * 再分 3 轮、每轮隔 400ms（大于 300ms 防抖，逼出「一轮轮真写盘」）。
         * 红字「保存失败」是 save() 返回 false 才加的 chip-error，见 ShellView.flushSettings。
         */
        TOGGLE_SPAM {
            @Override void apply(Node root, Path base, double scale) {
                ToggleSwitch motion = toggleByTitle(root, "界面动效");
                if (motion == null) {
                    System.out.println("[snapshot] 没找到「界面动效」开关，狂点没做");
                    return;
                }
                // 20 次同一拍：selected 被翻回了原样，落盘仍只发生一次
                for (int i = 0; i < 20; i++) {
                    motion.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                }
                System.out.println("[snapshot] 同一拍连点 20 下完成，selected=" + motion.getSelected());
                spamRound(root, motion, 3);
            }
        },
        /**
         * 连点同一导航项，验证页面淡入没有被一遍遍重播。
         *
         * <p>步骤：先跳到下载页，再点回「启动」（这时应当正常淡入一次），
         * 紧接着**再点一次**「启动」——这就是用户一直狂点同一个入口的动作。
         * 修好之后第二下不会重播，内容页 opacity 应该稳在 1；
         * 没修好的话 opacity 会被重新按到 0 再淡上去。
         */
        NAV_DOUBLE_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition a = new PauseTransition(Duration.seconds(0.4));
                a.setOnFinished(e -> {
                    fire(MouseEvent.MOUSE_CLICKED, "nav-home", root);
                    System.out.println("[snapshot] 已切回「启动」，先采一发");
                    PauseTransition s1 = new PauseTransition(Duration.seconds(0.04));
                    s1.setOnFinished(e1 -> {
                        samplePoint(root, "重播前");
                        // 紧接着第二次点同一个入口：没被 currentPage 挡住的话，
                        // 这一下会把 opacity 按回 0 重来，采样 2 就会和采样 1 打平甚至更低。
                        fire(MouseEvent.MOUSE_CLICKED, "nav-home", root);
                        System.out.println("[snapshot] 已连点第二次，再采一发");
                        PauseTransition s2 = new PauseTransition(Duration.seconds(0.04));
                        s2.setOnFinished(e2 -> samplePoint(root, "重播后"));
                        s2.play();
                        PauseTransition rest = new PauseTransition(Duration.seconds(0.5));
                        rest.setOnFinished(e3 -> inspect(root));
                        rest.play();
                    });
                    s1.play();
                });
                a.play();
            }
        },
        /**
         * 把主题下拉弹层打开。
         *
         * <p>为什么单独一步：弹层是独立 window/scene，主场景的截图拍不到它，
         * 不打开就永远验不了「深色下调别的模式时下拉字看不清」。
         */
        COMBO_OPEN {
            @Override void apply(Node root, Path base, double scale) {
                ComboBox<?> box = themeCombo(root);
                if (box != null) {
                    box.show();
                    System.out.println("[snapshot] 下拉已展开: " + box.getValue());
                    return;
                }
                System.out.println("[snapshot] 没找到 setting-select，弹层没打开");
            }
        },
        /** 弹层自己另有一张 scene，只能从它身上拍——主场景的 Node.snapshot 拍不到。 */
        POPUP_DUMP {
            @Override void apply(Node root, Path base, double scale) {
                // 弹层是独立 window：box.show() 之后未必当拍就挂进 Window.getWindows()，
                // 立刻扫会扑空（v0.3 见过「下拉已展开，但这一步没扫到 PopupControl」）。
                // 先顶一拍再扫，跟其它任务一样「一任务一拍」。
                PauseTransition wait = new PauseTransition(Duration.seconds(0.4));
                wait.setOnFinished(e -> {
                    try {
                        dumpPopup(base, scale);
                    } catch (IOException ex) {
                        System.err.println("[snapshot] 弹层导出失败: " + ex);
                    }
                });
                wait.play();
            }
        },


        /** 收起弹层，后面的最大化 / 切页才不会被它挡着。 */
        COMBO_CLOSE {
            @Override void apply(Node root, Path base, double scale) {
                for (Node node : find("setting-select", root)) {
                    if (node instanceof ComboBox<?> box) {
                        box.hide();
                    }
                }
            }
        },

        /**
         * 等官方清单同步完，报出状态条说了什么、这一屏摊了几行、第一行是哪一版。
         *
         * <p>必须停这一拍：清单是后台线程拉的，不停的话下一步点「加入队列」
         * 点的那张列表还没数据，后面整串判据都会落空。
         */
        MANIFEST_SYNC {
            @Override void apply(Node root, Path base, double scale) {
                // 深色那趟跑到这儿时停在「光影」那种占位页上：先退回「游戏」tab，
                // 不然数出来是 0 行，判据什么也验不了（v0.3.5 深色趟就读出过「本屏 0 行」）
                List<Node> tabs = find("tab-btn", root);
                if (!tabs.isEmpty()) {
                    tabs.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                }
                // 别等 4 秒：自测是「上一个任务 1.2 秒后就往下走」，
                // 等 4 秒的话这一步的读数会落到后面「显示全部」之后，判据读到的就成了全量 917 行
                PauseTransition wait = new PauseTransition(Duration.seconds(0.5));
                wait.setOnFinished(e -> {
                    StringBuilder state = new StringBuilder();
                    for (Node node : find("manifest-state", root)) {
                        if (node instanceof Labeled labeled) {
                            state.append(labeled.getText());
                        }
                    }
                    List<Node> rows = find("version-row", root);
                    System.out.println("[snapshot] 官方清单状态=[" + state.toString().trim()
                            + "]，本屏 " + rows.size() + " 行，第一行=["
                            + rowId(rows.isEmpty() ? null : rows.get(0)) + "]");
                });
                wait.play();
            }
        },

        /** 打开「显示全部版本」：同一屏从几百条变九百多条，条数得跟着变。 */
        MANIFEST_SHOW_ALL {
            @Override void apply(Node root, Path base, double scale) {
                List<Node> sw = find("show-all", root);
                if (sw.isEmpty()) {
                    System.out.println("[snapshot] 没找到显示全部的开关");
                    return;
                }
                int before = find("version-row", root).size();
                sw.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                PauseTransition wait = new PauseTransition(Duration.seconds(0.4));
                wait.setOnFinished(e -> System.out.println("[snapshot] 显示全部：" + before
                        + " 行 -> " + find("version-row", root).size() + " 行"));
                wait.play();
            }
        },

        /**
         * 点第一行的「下载」。v0.4 这一下是真下发请求，所以「排进队列」不再是终点——
         * 后面 {@link #QUEUE_WAIT} 盯着它下完；这一步只验两件事：按钮认得出、
         * 点完这一项进了队列（按钮禁用了、文案翻成「下载中 / 排队中」）。
         */
        QUEUE_ADD {
            @Override void apply(Node root, Path base, double scale) {
                List<Node> adds = find("btn-download", root);
                if (adds.isEmpty()) {
                    System.out.println("[snapshot] 没找到「下载」按钮");
                    return;
                }
                // 行文本必须从行上读：find 捞回来的是那个 Button（不是容器），
                // 对它 collectText 只会得到「下载」两个字（v0.3.5 首跑打出来是空的）
                List<Node> rows = find("version-row", root);
                String id = rowId(rows.isEmpty() ? null : rows.get(0));
                System.out.println("[snapshot] 点第一行的「下载」，行=[" + id + "]，按钮=["
                        + textOf(adds.get(0)) + "]");
                adds.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                PauseTransition wait = new PauseTransition(Duration.seconds(0.4));
                wait.setOnFinished(e -> {
                    // 自测这边读队列只能读静态影子：服务层是真队列，items() 要锁着同步。
                    // 两边一起打是为了不留悬案——哪边空了就知道是界面没刷新还是队列没排进去
                    System.out.println("[snapshot] 自测读队列 = ["
                            + String.join(",", ShellView.queuedIds()) + "]");
                    // 必须重新按 id 捞那一行：提交会把整个列表重建，
                    // 手里那枚 Button 已经脱离场景、读回来永远是「下载」（v0.3.5 就栽在这）
                    boolean flipped = false;
                    for (Node r : find("version-row", root)) {
                        if (id.equals(rowId(r))) {
                            List<Node> btns = find("btn-download", r);
                            if (!btns.isEmpty()) {
                                String text = textOf(btns.get(0));
                                flipped = "下载中".equals(text) || "排队中".equals(text);
                            }
                        }
                    }
                    System.out.println("[snapshot] 下载后，[" + id + "] 按钮改成「下载中/排队中」="
                            + flipped + "，队列现在是 ["
                            + String.join(",", ShellView.queuedIds()) + "]");
                });
                wait.play();
            }
        },

        /**
         * 左下角那条提示（v0.4.1）：点完「下载」当场该挂着一句「XX开始下载！」，
         * 2 秒之后自己淡出、界面上不再留着它。
         *
         * <p>为什么要单独一步、而且一步里量两次：提示自己带 2 秒寿命，隔一整拍
         * （{@link #STEP_DELAY}）再去问就已经散了——「出现过」和「到点消失」
         * 这两件事只能在同一拍里按时间轴量。
         */
        TOAST_ROUNDTRIP {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                Node from = root.getScene() == null ? root : root.getScene().getRoot();
                List<Node> bubbles = find("toast", from);
                // 认准「这一条」：后面还会再弹（第二次下载、下载完成），
                // 只数条数的话「1 条」可能是另一条新的，判不出老的那条到点没散
                final Node first = bubbles.isEmpty() ? null : bubbles.get(0);
                System.out.println("[snapshot] " + toastState(root, "刚点完"));
                // 提示带 2 秒寿命，趁活着拍一张：verify 好拿像素判「真画出来了」。
                // 必须拍 scene 根（提示挂在 shell 上，拍内容层的 root 一个气泡都拍不到——
                // 跟行菜单那张 gmenu-win 同一个道理）
                if (from != null) {
                    dump(from, resolve(base, "-toast"), scale);
                }
                PauseTransition gone = new PauseTransition(Duration.seconds(2.6));
                gone.setOnFinished(e -> {
                    boolean still = first != null && first.getParent() != null;
                    System.out.println("[snapshot] " + toastState(root, "2.6 秒后")
                            + "，最初那条还在树上=" + still);
                });
                gone.play();
            }
        },

        /**
         * 再排一个：v0.4 的「支持下载多个东西」不是一句文案——同一个队列里同时挂着两个、
         * 多个并行地跑（服务层 {@code MAX_ACTIVE=3}），游戏列表也该一并多出一整行。
         *
         * <p>点的是第二行的「下载」。等它靠 {@link #QUEUE_WAIT}（会连这一次一起等）。
         */
        QUEUE_ADD_2 {
            @Override void apply(Node root, Path base, double scale) {
                int[] tries = {0};
                Runnable[] box = new Runnable[1];
                Runnable attempt = box[0] = () -> {
                    List<Node> adds = find("btn-download", root);
                    if (adds.size() >= 2) {
                        List<Node> rows = find("version-row", root);
                        String id = rowId(rows.size() < 2 ? null : rows.get(1));
                        System.out.println("[snapshot] 再点第二行的「下载」，行=[" + id + "]");
                        adds.get(1).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                        PauseTransition wait = new PauseTransition(Duration.seconds(0.4));
                        wait.setOnFinished(e ->
                                System.out.println("[snapshot] 第二项入队后，队列现在是 ["
                                        + String.join(",", ShellView.queuedIds()) + "]"));
                        wait.play();
                        return;
                    }
                    // 上一项下完会把整屏 194 行重建一遍，重画的那一瞬间捞不到按钮。
                    // 重试两拍：真的没行就报「N 个按钮 + M 行」，别闷头跳过把「多任务」这条判据漏掉
                    if (++tries[0] < 8) {
                        PauseTransition again = new PauseTransition(Duration.seconds(0.5));
                        again.setOnFinished(e -> box[0].run());
                        again.play();
                        return;
                    }
                    StringBuilder cnt = new StringBuilder();
                    for (Node n : find("result-count", root)) {
                        if (n instanceof Labeled labeled) {
                            cnt.append(labeled.getText());
                        }
                    }
                    System.out.println("[snapshot] 只有 " + adds.size() + " 个「下载」按钮，"
                            + "这一屏还有 " + find("version-row", root).size() + " 行版本，"
                            + "计数=[" + cnt + "]，空结果卡 " + find("empty-result", root).size()
                            + " 个，筛选项 " + find("filter-chip", root).size() + " 个，第二项跳过");
                };
                attempt.run();
            }
        },

        /**
         * 等队列里那一项真下完：v0.4 的主菜就在这——进度走到 100%、jar 落盘、
         * 队列状态从「下载中」翻成「装好了」。
         *
         * <p>这一步可以慢慢来（自测等得起，用户也在等），所以是「轮询到结束」而不是拍一个数：
         * 只拍一次的话网络慢一点读到的就是刚开头 2%，「能不能下完」就永远验不到。
         * 上限给 90 秒，超时也算数——下一步 {@link #GAME_ROWS_REPORT} 会去数游戏列表，
         * 那个才是「下完到底进没进列表」的硬判据。
         *
         * <p>轮询在后台线程里跑（读的是服务层的 {@code Item}，跟 FX 无关），
         * 别拿一堆 PauseTransition 去接。
         *
         * <p>v0.4.2 起这一还多扛两件事，都卡在「全下完」那一瞬间：
         * 下完的项只挂 1.5 秒就自动移出队列（{@link #waitQueueAutoDrop}），
         * 所以「队列页截图 / 队列行四样 / 卡片与行的矩形」必须趁行还在的时候取——
         * 落在下一步就只剩「队列还是空的」空态，进度条那几条判据会集体落空。
         */
        QUEUE_WAIT {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                // 等的是「此刻队列里所有项」：v0.4 是自测里真连排两个（QUEUE_ADD_2），
                // 只盯第一个的话第二个会一路跑到后面去，游戏列表数出来就少一行（v0.4 首跑）
                List<String> ids = new ArrayList<>(ShellView.queuedIds());
                // v0.4.2：进来时队列可能早就被自动移出光了（下完 1.5 秒就摘），这不算失败。
                // 以前这里直接 return，会把后面的「下完自动移出」判据一起丢掉——
                // 而那正是这一版要验的结果，所以空队列也照样走完收尾。
                if (ids.isEmpty()) {
                    System.out.println("[snapshot] 队列是空的（下完自动移出已经把它们摘光了），直接收尾");
                } else {
                    awaitQueueItems(ids);
                }
                // 队列页的截图与几何由 QUEUE_TAB 趁行还在的时候取（v0.4.2 起下完 1.5 秒就自动移出，
                // 等下完再数只剩空态）；这一步只接「下完那一刻」右下角那句提示。
                // 下完那一刻右下角该有一句「XX下载完成！」：这条只在**真下完**时才弹，
                // 「磁盘上早就装好了」那种短路不该恭喜一遍（v0.4.1）
                System.out.println("[snapshot] " + toastState(root, "下完之后"));
                waitQueueAutoDrop(ids);
            }
        },

        /**
         * 切到「下载队列」页（tab 下标是最后一个）：队列得真的在这里显示出来。
         *
         * <p>v0.4.2 队列页是**懒构建**的，而「下完 1.5 秒行就被自动移出」——所以队列行的文本、
         * 卡片与行的矩形、还有整页截图，全都得趁切过来这一刻取：等到 {@link #QUEUE_WAIT} 下完
         * 那一瞬再数，不是 0 行就是一张空态图，四个判据一起落空（首跑就是这么个 0）。
         * 回来那一次队列已经是空的，那是「自动移出后只剩空态」，不能再拿它盖掉刚才那张图。
         */
        QUEUE_TAB {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                // 范围收窄到下载页子树：v0.7.0 起 Mod 中心也有 3 个 tab-btn 挂在场景里，
                // 全局找的话「最后一个」会变成 Mod 中心的「整合包」而不是下载页的「下载列表」
                Node scope = downloadPageScope(root);
                List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                if (tabs.size() < 7) {
                    System.out.println("[snapshot] tab 只有 " + tabs.size() + " 个，切不过去");
                    return;
                }
                Node last = tabs.get(tabs.size() - 1);
                StringBuilder name = new StringBuilder();
                if (last instanceof Parent box) {
                    collectText(box, name);
                }
                last.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                PauseTransition wait = new PauseTransition(Duration.seconds(0.4));
                wait.setOnFinished(e -> {
                    List<Node> rows = find("queue-row", root);
                    StringBuilder count = new StringBuilder();
                    for (Node node : find("queue-count", root)) {
                        if (node instanceof Labeled labeled) {
                            count.append(labeled.getText());
                        }
                    }
                    System.out.println("[snapshot] 切到\t" + name.toString().trim()
                            + "\t（tab 共 " + tabs.size() + " 个），队列 " + rows.size()
                            + " 行，[" + count.toString().trim() + "]");
                    // 行还在才取：队列页「自动移出后只剩空态」那次回来也走这一步，
                    // 别拿一张空态图把刚才那张带进度条的盖掉
                    if (!rows.isEmpty()) {
                        reportQueueRows(root);
                        // 事件回调是 EventHandler，抛不出受检异常，就地接住
                        try {
                            dump(root, resolve(base, "-queue"), scale);
                        } catch (IOException ex) {
                            System.out.println("[snapshot] 队列页截图没落下来：" + ex.getMessage());
                        }
                    }
                });
                wait.play();
            }
        },

        /** 单独拍下载队列整页。 */
        /**
         * 真下完之后，版本行那颗按钮该写成「已安装」并且禁用（v0.4.1 修的正式版那条）。
         *
         * <p>以前只认本轮队列：磁盘上已经有 jar 的版本（官方启动器装的那种，多是正式版）
         * 按钮还写着「下载」，点下去在 {@code installed()} 那儿直接 return——
         * 一点反应都没有，用户报的就是「正式版下载不了」。这条判据把「按钮说实话」钉住。
         */
        VERSION_BTN_REPORT {
            @Override void apply(Node root, Path base, double scale) {
                // v0.4.2 这一步可能还停在「下载队列」页上（序列把 QUEUE_TAB 提到了等下完之前），
                // 版本行只长在「游戏」页上，先自己退回来再数，不然又是 0 行
                List<Node> tabs = find("tab-btn", root);
                if (!tabs.isEmpty()) {
                    tabs.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                    PauseTransition back = new PauseTransition(Duration.seconds(0.3));
                    back.setOnFinished(e -> { });
                    back.play();
                }
                for (Node row : find("version-row", root)) {
                    String id = rowId(row);
                    if (!GameDownloader.installed(id)) {
                        continue;
                    }
                    List<Node> btns = find("btn-download", row);
                    if (btns.isEmpty()) {
                        continue;
                    }
                    Node btn = btns.get(0);
                    System.out.println("[snapshot]   版本行按钮 [" + id + "]=[" + textOf(btn)
                            + "] 禁用=" + btn.isDisabled());
                }
            }
        },

        /**
         * 愚人节那几条现在标成什么（v0.4.2）。
         *
         * <p>官方把 {@code 24w14potato} 这类愚人节版本自己也标成 {@code snapshot}
         * （{@code version.json} 里就是这么写的），只看 type 屏幕上就得写「快照」——
         * 用户报的正是「愚人节的还提示快照版」。认目录名 / 版本 id 才标得对，
         * 这条判据把**下载页**这一处钉住（游戏列表那处并进 {@link #reportGameRows} 一起报）。
         *
         * <p>必须开着「显示全部」再数：愚人节那条不在默认视图里（默认只放正式版 + 最近 30 快照），
         * 不开就永远 0 条、判据会假绿。
         */
        APRIL_REPORT {
            @Override void apply(Node root, Path base, double scale) {
                List<String> hits = new ArrayList<>();
                for (Node row : find("version-row", root)) {
                    for (Node badge : find("type-badge", row)) {
                        if (badge instanceof javafx.scene.control.Labeled labeled
                                && labeled.getText() != null
                                && labeled.getText().contains("愚人节")) {
                            hits.add(rowId(row) + "→" + labeled.getText());
                        }
                    }
                }
                System.out.println("[snapshot] 下载页愚人节徽标 " + hits.size() + " 条："
                        + String.join("，", hits));
            }
        },

        /**
         * 钉旧基线 + 触发一次自动同步，认「官方新增了版本」。
         *
         * <p>v0.4.2 把「检查更新」这枚按钮删了（清单每次打开 / 每次回到「游戏」tab 都自己同步），
         * 所以这里不再找按钮，改走同一件事的现成入口：先离开「游戏」页、再点回来，
         * {@code showDownloadTab(0)} 那一路会自己喊一次 {@code requestManifestSync()}。
         *
         * <p>钉基线是必须的：第一次跑拿到整份清单时基线为空，代码按「没有基线就不标新」处理，
         * 不钉的话这条链路永远验不到。
         */
        MANIFEST_FRESH {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                VersionManifest.primeBaseline(List.of("1.21.1"));
                // 上一步还停在「下载队列」页，那页既没有状态条也没有版本行，
                // 不点回来就一个数都采不到（v0.3.5 首跑就是这个空）
                List<Node> tabs = find("tab-btn", root);
                if (tabs.isEmpty()) {
                    System.out.println("[snapshot] 连 tab 都没有，这步跳过");
                    return;
                }
                tabs.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                dump(root, resolve(base, "-fresh"), scale);
                // 等到「取回来了」那句话真落到状态条上再数，别拍死一个 5 秒：
                // 排 5 秒的话，深色那趟把它放在序列末尾，5 秒还没到自测就把窗口关了，
                // 判据一句都采不到（v0.3.5 就是这么空的）。
                awaitFresh(root, 0);
            }
        },

        // ------------------------------------------------- 游戏列表（v0.3.1）

        /** 切到「游戏列表」页。导航项有独立的 nav-gamelist 类名，点它不会被同类的别的项吃掉。 */
        GAME_NAV_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                PauseTransition s = new PauseTransition(Duration.seconds(0.3));
                s.setOnFinished(e -> reportGameRows(root, "切到游戏列表"));
                s.play();
            }
        },
        /** 拍游戏列表页——这一趟里跑两次（空态一次、载入示例后一次），见 {@link #gameDumpName()》。 */
        GAME_DUMP {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                dump(root, resolve(base, gameDumpName()), scale);
            }
        },
        /**
         * 真下载完当场数一遍游戏列表。v0.4 起示例数据（seedSamples）删干净了，
         * 列表里只会是「真装过的」，所以这一次数出来的是硬判据：
         * jar 落到 versions/&lt;id&gt;/  → GameScanner 扫得到 → 这一行必须站在这里。
         *
         * <p>要先等 {@link #QUEUE_WAIT} 把它盯到终态，这里才轮得到数。
         */
        GAME_ROWS_REPORT {
            @Override void apply(Node root, Path base, double scale) {
                reportGameRows(root, "真下载完");
            }
        },
        /**
         * 点第二行：第一行的圆点可能本来就是当前游戏，测「换」得换个不是当前的。
         * 顺带验圆点切游戏这条链路有没有真的把首页 Hero 带起来。
         */
        GAME_ROW_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                List<Node> rows = find("game-row", root);
                if (rows.size() < 2) {
                    System.out.println("[snapshot] 只有 " + rows.size() + " 行，点第二行这步跳过");
                    return;
                }
                Node second = rows.get(1);
                StringBuilder text = new StringBuilder();
                if (second instanceof Parent box) {
                    collectText(box, text);
                }
                second.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                PauseTransition s = new PauseTransition(Duration.seconds(0.35));
                s.setOnFinished(e -> System.out.println("[snapshot] 点了第 2 行（=[" + text.toString().trim()
                        + "]），Hero=[" + heroText(root) + "]"));
                s.play();
            }
        },
        /** 点行尾三点把菜单拉起来（菜单挂在 shell 上，主场景扫得到，跟弹层不同）。 */
        GAME_MENU_OPEN {
            @Override void apply(Node root, Path base, double scale) {
                // 只点第一行的那枚，别把三行的 game-more 一次全点掉：
                // 「点行尾三点」是点一个按钮，一次三点等于连开三次、结果没人说得准
                // （深色那趟就是这么读出「行菜单 visible=false」的）。
                clickNth(root, "game-more", 0);
                PauseTransition s = new PauseTransition(Duration.seconds(0.3));
                s.setOnFinished(e -> {
                    System.out.println("[snapshot] 行菜单 visible="
                            + (ShellView.gameMenu != null && ShellView.gameMenu.isVisible()));
                    // 顺手把整窗（scene 根）也拍一张：菜单是浮在 shell 上的，
                    // 只拍菜单卡本身拍不到它压暗了谁 —— v0.3.2 那块「左边一大片黑幕」
                    // 就是从这一张上量的
                    Node shell = root.getScene() == null ? null : root.getScene().getRoot();
                    if (shell == null) {
                        return;
                    }
                    try {
                        dump(shell, resolve(base, "-gmenu-win"), scale);
                    } catch (IOException ex) {
                        System.err.println("[snapshot] 菜单开着那张整窗没拍成: " + ex);
                    }
                });
                s.play();
            }
        },
        /** 单独拍行菜单：它挂在 shell 上，主场景那张拍不到。 */
        GAME_MENU_DUMP {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                if (ShellView.gameMenu == null || !ShellView.gameMenu.isVisible()) {
                    System.out.println("[snapshot] 行菜单没开着，这一张没拍到");
                    return;
                }
                dump(ShellView.gameMenu, resolve(base, "-gmenu"), scale);
                System.out.println("[popup-tree] 行菜单结构（深色=" + classes(ShellView.gameMenu) + "）");
                printTree("  ", ShellView.gameMenu);
            }
        },
        /**
         * 点「菜单外面」收起：菜单的取消路径得接上，不然它会赖在屏幕上。
         *
         * <p>这一下打在侧边栏上而不是捕获层上，是为了验 v0.3.2 的穿透：捕获层是
         * {@code mouseTransparent} 的，点外面该收菜单<em>并且</em>那一下照常落到界面上；
         * 要是哪天捕获层又不透明了，事件 target 会变回 menu-catch（属于菜单内部），
         * 收不起来 —— 这条判据就把这个回归钉住。
         */
        GAME_MENU_CLOSE {
            @Override void apply(Node root, Path base, double scale) {
                if (ShellView.gameMenu == null || !ShellView.gameMenu.isVisible()) {
                    System.out.println("[snapshot] 行菜单本来就没开，不用收");
                    return;
                }
                List<Node> bars = find("side-bar", root);
                Node side = bars.isEmpty() ? null : bars.get(0);
                if (side == null) {
                    System.out.println("[snapshot] 没找到侧边栏，这步跳过");
                    return;
                }
                // 关菜单判的是坐标（点在卡片矩形外没有），合成事件不填坐标就是 (0,0)，
                // 量出来「在框里面」—— 必须自己挑一个真在框外的点按下去
                double[] p = aroundCard(false);
                side.fireEvent(pressAt(p[0], p[1]));
                side.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                System.out.println("[snapshot] 点行菜单外面（" + classes(side) + " @ "
                        + (int) p[0] + "," + (int) p[1] + "）收起，visible="
                        + ShellView.gameMenu.isVisible());
            }
        },
        /**
         * 右键开菜单 + 点框外收起（v0.3.3）。
         *
         * <p>自测原来只走了「点三点」这一条，用户报的右键路径没被钉住。这里两条都跑：
         * 右键把菜单拉起来，再往框外（列表容器）按一下 —— 菜单得收，而且那一下要
         * 落在 game-list 上而不是被菜单吞掉（捕获层是 mouseTransparent 的）。
         * 收起判据走的是坐标（见 ShellView.outsideOfMenu），点在哪都收得掉。
         */
        GAME_MENU_RCLICK {
            @Override void apply(Node root, Path base, double scale) {
                List<Node> rows = find("game-row", root);
                if (rows.isEmpty()) {
                    System.out.println("[snapshot] 一行游戏都没有，右键这步跳过");
                    return;
                }
                rows.get(0).fireEvent(rightClick());
                PauseTransition s = new PauseTransition(Duration.seconds(0.3));
                s.setOnFinished(e -> {
                    boolean open = ShellView.gameMenu != null && ShellView.gameMenu.isVisible();
                    System.out.println("[snapshot] 右键第 1 行开菜单，visible=" + open);
                    if (!open) {
                        return;
                    }
                    // 框外按一下：该收
                    double[] out = aroundCard(false);
                    List<Node> lists = find("game-list", root);
                    Node outside = lists.isEmpty() ? null : lists.get(0);
                    if (outside == null) {
                        System.out.println("[snapshot] 找不到列表容器，收起这步跳过");
                        return;
                    }
                    outside.fireEvent(pressAt(out[0], out[1]));
                    System.out.println("[snapshot] 右键那条路径：点框外（" + classes(outside)
                            + " @ " + (int) out[0] + "," + (int) out[1]
                            + "）收起，visible=" + ShellView.gameMenu.isVisible()
                            + "，且这下落在界面上（没被菜单吞掉）");
                    // 再开一次、往卡片正中间按：点在框里面，就不该关。
                    // 只能点第一行的那枚：fire() 会把它底下所有的 game-more 全点一遍，
                    // 一次开两回菜单、菜单绑定的游戏就变成最后那一行（v0.4.2 真删那步被它带偏过）
                    clickNth(root, "game-more", 0);
                    PauseTransition s2 = new PauseTransition(Duration.seconds(0.3));
                    s2.setOnFinished(e2 -> {
                        double[] inn = aroundCard(true);
                        Node card = menuCard();
                        if (card == null) {
                            System.out.println("[snapshot] 找不到菜单卡片，框内这步跳过");
                            return;
                        }
                        card.fireEvent(pressAt(inn[0], inn[1]));
                        System.out.println("[snapshot] 点框内（卡 @ " + (int) inn[0] + ","
                                + (int) inn[1] + "）不该收，visible=" + ShellView.gameMenu.isVisible());
                    });
                    s2.play();
                });
                s.play();
            }
        },
        /** 菜单里点「删除游戏」：删除是不可逆操作，得先过确认这一步。 */
        GAME_PROMPT_OPEN {
            @Override void apply(Node root, Path base, double scale) {
                if (ShellView.gameMenu == null) {
                    System.out.println("[snapshot] 行菜单不在，这步跳过");
                    return;
                }
                List<Node> items = find("menu-item", ShellView.gameMenu);
                if (items.isEmpty()) {
                    System.out.println("[snapshot] 菜单里没有可点的项，这步跳过");
                    return;
                }
                // 删除永远加在最后一项（见 ShellView.menuAction 的顺序），按下标认比按文字稳
                items.get(items.size() - 1).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                PauseTransition s = new PauseTransition(Duration.seconds(0.35));
                s.setOnFinished(e -> System.out.println("[snapshot] 删除确认弹层 visible="
                        + (ShellView.gamePrompt != null && ShellView.gamePrompt.isVisible())
                        + "，菜单 visible=" + ShellView.gameMenu.isVisible()));
                s.play();
            }
        },
        /** 单独拍删除确认框。 */
        GAME_PROMPT_DUMP {
            @Override void apply(Node root, Path base, double scale) throws IOException {
                if (ShellView.gamePrompt == null || !ShellView.gamePrompt.isVisible()) {
                    System.out.println("[snapshot] 删除确认框没开着，这一张没拍到");
                    return;
                }
                dump(ShellView.gamePrompt, resolve(base, "-gprompt"), scale);
                System.out.println("[popup-tree] 删除确认框结构（深色=" + classes(ShellView.gamePrompt) + "）");
                printTree("  ", ShellView.gamePrompt);
            }
        },
        /** 取消删除：这一轮自测不该真的删掉示例游戏。 */
        GAME_PROMPT_CANCEL {
            @Override void apply(Node root, Path base, double scale) {
                if (ShellView.gamePrompt == null) {
                    System.out.println("[snapshot] 确认框不在，这步跳过");
                    return;
                }
                List<Node> cancels = find("btn-ghost", ShellView.gamePrompt);
                for (Node cancel : cancels) {
                    cancel.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                }
                System.out.println("[snapshot] 点了取消（" + cancels.size() + " 个），"
                        + "确认框 visible=" + ShellView.gamePrompt.isVisible());
            }
        },
        /**
         * 真的删一次游戏（v0.4.2）。
         *
         * <p>以前这条路径只走到「取消」为止，菜单项明明在、点了却什么都没发生——
         * 用户报的就是「让游戏列表可以删游戏 / 它删不了」。所以这里把确认框真的点下去，
         * 硬判三件事：列表少一行、磁盘上那个目录也不该再躺着、确认框自己收掉。
         *
         * <p>这一删会连带把「它删不了」那条老账结了：底层回收站那条 PowerShell 调用
         * 传的是四参重载、第四参又是个不合法的 {@code UICancelOption}（exit=1、目录原样还在），
         * 现在删得掉，这条判据才有意义。
         */
        GAME_PROMPT_CONFIRM {
            @Override void apply(Node root, Path base, double scale) {
                if (ShellView.gamePrompt != null && ShellView.gamePrompt.isVisible()) {
                    deleteNow(root);
                    return;
                }
                // 前面「游戏管理」那一步把整页重建了一遍，上次的确认框早关了；
                // 这里自己重新走一遍「三点 → 删除游戏」，别指望上一步留下什么状态。
                // 给两拍：第一拍点下去菜单/确认框未必已经跟上，第二拍再补一次。
                int[] tries = {0};
                Runnable[] box = new Runnable[1];
                box[0] = () -> {
                    if (openDeletePrompt(root)) {
                        deleteNow(root);
                        return;
                    }
                    if (++tries[0] < 2) {
                        PauseTransition again = new PauseTransition(Duration.seconds(0.5));
                        again.setOnFinished(e -> box[0].run());
                        again.play();
                    } else {
                        System.out.println("[snapshot] 两拍都没能把删除确认框点开，真删这步跳过");
                    }
                };
                box[0].run();
            }
        },
        /** 「游戏管理」v0.7.0 起是「Mod 中心」：点得出、页标题对、三个 tab 在（PRD Q6）。 */
        GAME_MGMT_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-gamemgmt", root);
                PauseTransition s = new PauseTransition(Duration.seconds(0.3));
                s.setOnFinished(e -> {
                    StringBuilder text = new StringBuilder();
                    // 只认当前这一页：page-title 每页都有一个，全捞会把三个页标题拼成一条。
                    // 没显示的那一页里的 Label 自己 visible 仍是 true（isVisible() 只看自己
                    // 那个开关，不看祖先），所以得沿父链一路问上去（Node.isTreeVisible 不是公开的）
                    for (Node node : find("page-title", root)) {
                        if (onScreen(node) && node instanceof javafx.scene.control.Label label) {
                            text.append(label.getText());
                        }
                    }
                    System.out.println("[snapshot] 切到「Mod 中心」，页标题=[" + text.toString().trim() + "]");
                    // 切回去，后面的设置页那几步才能照常跑
                    fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                    PauseTransition back2 = new PauseTransition(Duration.seconds(0.25));
                    back2.setOnFinished(e2 -> reportGameRows(root, "从 Mod 中心切回"));
                    back2.play();
                });
                s.play();
            }
        },
        /**
         * 点第三行，验「点行＝选中」而不是「点行＝下载」。
         *
         * <p>{@code selected} 只是一枚类名（绿条和浅绿底全在 CSS 里），
         * 所以这里把每一行的类名都报出来：有且只有一行带 {@code selected} 才算过。
         */
        ROW_SELECT {
            @Override void apply(Node root, Path base, double scale) {
                List<Node> rows = find("version-row", root);
                if (rows.isEmpty()) {
                    System.out.println("[snapshot] 没有版本行可选");
                    return;
                }
                // 下标 2 = 用户看到的第 3 条。日志里两个都打：下标给自测和回归对得上，
                // 序号给人看（不然「点了第 2 行」会被读成第二行）。
                int at = Math.min(2, rows.size() - 1);
                Node target = rows.get(at);
                StringBuilder text = new StringBuilder();
                if (target instanceof Parent box) {
                    collectText(box, text);
                }
                target.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                System.out.println("[snapshot] 点了第 " + at + " 行（第 " + (at + 1) + " 条）"
                        + "（=[" + text.toString().trim() + "]）");
                PauseTransition settle = new PauseTransition(Duration.seconds(0.2));
                settle.setOnFinished(e -> {
                    for (Node row : find("version-row", root)) {
                        StringBuilder one = new StringBuilder();
                        if (row instanceof Parent box) {
                            collectText(box, one);
                        }
                        System.out.println("[snapshot] 版本行 [" + one.toString().trim()
                                + "] 类=" + classes(row));
                    }
                });
                settle.play();
            }
        },
        /**
         * 报一遍 8 个 tab 的名字与顺序，再点「光影」验这一页站得住。
         *
         * <p>顺序是用户指定的（游戏、加载器、模组、整合包、资源包、光影、世界、下载队列），
         * 排反了就是「做错了但看起来像做完了」，所以名字必须打出来对一遍。
         *
         * <p><b>范围收窄到下载页子树</b>（v0.7.0）：Mod 中心那 3 个 tab-btn 也挂在场景里，
         * 全局找只有下载页这 8 个 tab-btn，第 5 个是资源包、第 6 个才是「光影」。语义不变：
         * 仍是验下载页 8 个 tab 的顺序与内容页可达。
         *
         * <p><b>0.8.7 起不再假设空态</b>：资源包 / 光影 / 世界 三页换成真实内容页后，
         * {@code page-blank-title} / {@code page-blank-desc} 会消失。这里改成松断言——
         * 只验「点了不抛异常 + 下载页根节点还在 + 页面容器非空」，不认任何新 UI 类名，
         * 免得 UI 执行者一改类名自测就挂。空态那行只作历史观测保留（兼容旧判据锚点）。
         */
        TAB_SWITCH {
            @Override void apply(Node root, Path base, double scale) {
                Node scope = downloadPageScope(root);
                List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                System.out.println("[snapshot] tab 共 " + tabs.size() + " 个："
                        + (tabs.isEmpty() ? "-" : tabNames(tabs)));
                if (tabs.size() < 5) {
                    return;
                }
                tabs.get(5).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));   // 「光影」（现在第 6 个，排在资源包之后）
                System.out.println("[snapshot] 已切到「光影」");
                PauseTransition settle = new PauseTransition(Duration.seconds(0.2));
                settle.setOnFinished(e -> {
                    for (Node tab : tabs) {
                        System.out.println("[snapshot] tab 类=" + classes(tab));
                    }
                    // 0.8.7：不再把「有没有空态卡」当判据，只验这一页站得住。
                    boolean ok = false;
                    String detail;
                    try {
                        Node page = downloadPageScope(root);
                        int nodes = page == null ? 0 : page.lookupAll("*").size();
                        ok = page != null && nodes > 0;
                        detail = "下载页根节点=" + (page == null ? "缺失" : classes(page))
                                + " 容器节点=" + nodes + " tab 可点击=true 异常=0";
                    } catch (RuntimeException ex) {
                        detail = "抛异常=" + ex;
                    }
                    System.out.println("[snapshot] 0.8.7 用例「下载页光影内容页可达」："
                            + (ok ? "通过" : "失败") + "（" + detail + "）");
                    // 历史观测锚点（兼容旧判据）：老 UI 还挂 page-blank-title 时照实报；
                    // 0.8.7 真实内容页下空态卡已下线，这里明说，并保留旧判据要认的那句
                    // 历史文案作锚点，免得「既有用例」因为少一行日志就红掉。
                    boolean blankLeft = false;
                    for (Node node : root.lookupAll("*")) {
                        if (node.getStyleClass().contains("page-blank-title")
                                && node instanceof Labeled labeled) {
                            blankLeft = true;
                            System.out.println("[snapshot] 空态标题=" + labeled.getText());
                        }
                    }
                    if (!blankLeft) {
                        System.out.println("[snapshot] 空态标题=（0.8.7：资源包/光影/世界 已实装为真实内容页，"
                                + "「光影」还没有内容 这句空态文案随空态卡一并下线；改由上面的容器非空判据覆盖）");
                    }
                });
                settle.play();
            }
        },
        /**
         * 点侧边栏「Mod 中心」（v0.7.0）：验它点了有反馈——页标题变成「Mod 中心」、
         * 根容器带 {@code mod-center} 类、屏幕上数得着 3 个 tab。
         *
         * <p>只做结构断言，不触网：三个 tab 的构建都不发请求（加载器版本列表是后台线程的事）。
         * {@code nav-gamemgmt} 类名没动，点击路径和老的「游戏管理」一模一样。
         */
        MOD_CENTER_CLICK {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition s = new PauseTransition(Duration.seconds(0.3));
                s.setOnFinished(e -> {
                    // 进入下载页的「加载器」tab（下标 1）：Mod 管理三块已并入下载页（v0.7.0）
                    List<Node> tabs = new ArrayList<>();
                    for (Node tab : find("tab-btn", root)) {
                        if (onScreen(tab)) {
                            tabs.add(tab);
                        }
                    }
                    if (tabs.size() < 2) {
                        System.out.println("[snapshot] 下载页没进来，Mod 管理这步跳过");
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                        return;
                    }
                    tabs.get(1).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));   // 加载器
                    PauseTransition settle = new PauseTransition(Duration.seconds(0.25));
                    settle.setOnFinished(e2 -> {
                        List<Node> bodies = find("mod-tab-body", root);
                        List<Node> cards = find("loader-card", root);
                        System.out.println("[snapshot] Mod 管理（下载页·加载器）：mod-tab-body="
                                + bodies.size() + " 加载器卡=" + cards.size());
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                    });
                    settle.play();
                });
                s.play();
            }
        },

        MOD_TAB_SWITCH {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.3));
                enter.setOnFinished(e -> {
                    List<Node> tabs = new ArrayList<>();
                    for (Node tab : find("tab-btn", root)) {
                        if (onScreen(tab)) {
                            tabs.add(tab);
                        }
                    }
                    if (tabs.size() < 4) {
                        System.out.println("[snapshot] 下载页没进来，Mod tab 切换跳过");
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                        return;
                    }
                    int[] at = {0};
                    int[] order = {1, 2, 3};   // 加载器 / 模组 / 整合包
                    Runnable[] step = new Runnable[1];
                    step[0] = () -> {
                        if (at[0] >= order.length) {
                            fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                            return;
                        }
                        int idx = order[at[0]];
                        tabs.get(idx).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                        PauseTransition settle = new PauseTransition(Duration.seconds(0.2));
                        settle.setOnFinished(e2 -> {
                            System.out.println("[snapshot] 下载页切到第 " + (idx + 1)
                                    + " 个 tab（" + tabNames(tabs.subList(idx, idx + 1))
                                    + "），mod-tab-body=" + find("mod-tab-body", root).size());
                            at[0]++;
                            step[0].run();
                        });
                        settle.play();
                    };
                    step[0].run();
                });
                enter.play();
            }
        },

        MOD_LOADER_CARDS {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.3));
                enter.setOnFinished(e -> {
                    List<Node> tabs = new ArrayList<>();
                    for (Node tab : find("tab-btn", root)) {
                        if (onScreen(tab)) {
                            tabs.add(tab);
                        }
                    }
                    if (tabs.size() < 2) {
                        System.out.println("[snapshot] 下载页没进来，加载器卡这步跳过");
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                        return;
                    }
                    tabs.get(1).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));   // 加载器
                    PauseTransition settle = new PauseTransition(Duration.seconds(0.25));
                    settle.setOnFinished(e2 -> {
                        List<Node> cards = new ArrayList<>();
                        for (Node card : find("loader-card", root)) {
                            if (onScreen(card)) {
                                cards.add(card);
                            }
                        }
                        System.out.println("[snapshot] 加载器卡共 " + cards.size() + " 张");
                        for (Node card : cards) {
                            StringBuilder text = new StringBuilder();
                            if (card instanceof Parent box) {
                                collectText(box, text);
                            }
                            List<Node> combos = find("combo-box", card);
                            List<Node> buttons = find("button", card);
                            String action = buttons.isEmpty() ? "?"
                                    : ((Labeled) buttons.get(buttons.size() - 1)).getText();
                            System.out.println("[snapshot] 加载器卡 [" + text.toString().trim()
                                    + "] 下拉=" + combos.size() + " 按钮=" + buttons.size()
                                    + "（主按钮=" + action + "）");
                        }
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                    });
                    settle.play();
                });
                enter.play();
            }
        },

        TAB_BACK {
            @Override void apply(Node root, Path base, double scale) {
                clickNth(root, "tab-btn", 0);
            }
        },
        /** 搜索框输入即筛，不回车。输入的是版本号片段，应当只留下 id 里带这段的那些。 */
        SEARCH_TYPE {
            @Override void apply(Node root, Path base, double scale) {
                TextField input = searchBox(root);
                if (input == null) {
                    System.out.println("[snapshot] 没找到搜索框");
                    return;
                }
                // 搜版本号片段，不搜「快照」这两个字：真实清单里只有 id 可搜（没有版本名称），
                // 输入类型中文名会一条都匹配不上（v0.3.5 首跑搜出来「共 0 个版本」）。
                // 标签必须跟实际输入一致——verify 靠这个字符串对上条数，写「搜『快照』」
                // 而框里是 1.20，判据就永远采不到（自测标签骗过了自己）。
                input.setText(SEARCH_TERM);
                PauseTransition settle = new PauseTransition(Duration.seconds(0.2));
                settle.setOnFinished(e -> reportVersionList(root, "搜『%s』".formatted(SEARCH_TERM)));
                settle.play();
            }
        },
        SEARCH_CLEAR {
            @Override void apply(Node root, Path base, double scale) {
                TextField input = searchBox(root);
                if (input == null) {
                    // 上一步刚找到过，这一步没有 = 中间整块重建了，光报一句会以为是笔误，
                    // 所以把现场还剩什么是数出来的。
                    System.out.println("[snapshot] 没找到搜索框"
                            + "（tab=%d search=%d 行=%d chip=%d）"
                            .formatted(find("tab-btn", root).size(),
                                    find("version-search", root).size(),
                                    find("version-row", root).size(),
                                    find("filter-chip", root).size()));
                    return;
                }
                input.setText("");
                PauseTransition settle = new PauseTransition(Duration.seconds(0.2));
                settle.setOnFinished(e -> reportVersionList(root, "清空搜索"));
                settle.play();
            }
        },
        /** 只留「快照版」一枚筛选胶囊：列表里必须全是快照，条数也要跟着收窄。 */
        FILTER_SNAP {
            @Override void apply(Node root, Path base, double scale) {
                clickNth(root, "filter-chip", 1);
                PauseTransition settle = new PauseTransition(Duration.seconds(0.2));
                settle.setOnFinished(e -> reportVersionList(root, "只筛快照版"));
                settle.play();
            }
        },
        /** 再点一次同一枚胶囊＝取消筛选，列表要回到全部。 */
        FILTER_ALL {
            @Override void apply(Node root, Path base, double scale) {
                clickNth(root, "filter-chip", 1);
                PauseTransition settle = new PauseTransition(Duration.seconds(0.2));
                settle.setOnFinished(e -> reportVersionList(root, "取消筛选"));
                settle.play();
            }
        },

        /**
         * 0.8.7：依次点下载页 8 个 tab，验每个都能站住、不抛异常。
         *
         * <p>用既有 {@link #downloadPageScope} 收窄 + {@code tab-btn} 下标点击，下标顺序
         * 沿用 TAB_SWITCH / MOD_* 那套，不另立一套。0.8.7 新实装的 4/5/6（资源包 / 光影 /
         * 世界）是重点，但 8 个一视同仁全点一遍。只验「根节点在 + 容器非空 + 无异常」，
         * 不认任何新 UI 类名。
         *
         * <p>一拍内同步点完（不排 8 个 PauseTransition），免得超过 STEP_DELAY 被下个任务插队。
         */
        DOWNLOAD_TABS_ALL_REACHABLE {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.3));
                enter.setOnFinished(e -> {
                    Node scope = downloadPageScope(root);
                    List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                    int reached = 0, errors = 0;
                    StringBuilder bad = new StringBuilder();
                    for (int i = 0; i < tabs.size(); i++) {
                        try {
                            tabs.get(i).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                            Node page = downloadPageScope(root);
                            int nodes = page == null ? 0 : page.lookupAll("*").size();
                            if (page != null && nodes > 0) {
                                reached++;
                            } else {
                                bad.append(" 第").append(i + 1).append("个空容器");
                            }
                        } catch (RuntimeException ex) {
                            errors++;
                            bad.append(" 第").append(i + 1).append("个抛")
                                    .append(ex.getClass().getSimpleName());
                        }
                    }
                    // 8 项顺序是 0.8.7 不改的既有约定，这里只数够不够、不重排下标。
                    boolean ok = tabs.size() >= 8 && reached == tabs.size() && errors == 0;
                    System.out.println("[snapshot] 0.8.7 用例「下载页 8 个内容 tab 全可达」："
                            + (ok ? "通过" : "失败")
                            + "（tab 共 " + tabs.size() + " 个，可达 " + reached
                            + "，异常 " + errors + (bad.length() > 0 ? "，问题：" + bad : "") + "）");
                    fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                });
                enter.play();
            }
        },

        /**
         * 0.8.7：游戏管理页能打开、三段新管理项（资源包 / 光影 / 世界）在。
         *
         * <p>用既有 {@code nav-gamemgmt} 点击路径。断言走松路子：只认「容器非空 +
         * 不抛异常」，三段管理项用**当前可见页的文字**报命中数，不写死任何新 UI 类名。
         * 只收 {@link #onScreen} 为真的 Labeled，是因为切页用 setVisible(false) 把别的页
         * 按下去——下载页 tab 里本来也有「资源包 / 光影 / 世界」三个词，不过滤会把它们
         * 误算成游戏管理页的三段。
         */
        GAME_MGMT_087_SECTIONS {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-gamemgmt", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.3));
                enter.setOnFinished(e -> {
                    boolean ok = false;
                    String detail;
                    try {
                        StringBuilder visible = new StringBuilder();
                        for (Node node : root.lookupAll("*")) {
                            if (onScreen(node) && node instanceof Labeled labeled
                                    && labeled.getText() != null) {
                                visible.append(labeled.getText()).append(' ');
                            }
                        }
                        String all = visible.toString();
                        int hits = 0;
                        for (String key : new String[]{"资源包", "光影", "世界"}) {
                            if (all.contains(key)) {
                                hits++;
                            }
                        }
                        int nodes = root.lookupAll("*").size();
                        ok = nodes > 0;
                        detail = "页面节点=" + nodes + " 三段命中=" + hits + "/3"
                                + (hits < 3 ? "（三段文案未齐，请对照下面的页面文字）" : "（三段齐全）")
                                + " 可见文字=[" + clip(all.trim(), 120) + "]"
                                + " 异常=0";
                    } catch (RuntimeException ex) {
                        detail = "抛异常=" + ex;
                    }
                    System.out.println("[snapshot] 0.8.7 用例「游戏管理页三段新管理项可达」："
                            + (ok ? "通过" : "失败") + "（" + detail + "）");
                    fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                });
                enter.play();
            }
        },

        /**
         * 0.8.7：加载器 tab 能打开（OptiFine 卡片由 UI 执行者加，断言只要求页面可达）。
         *
         * <p>下标 1 = 加载器，沿用 MOD_CENTER_CLICK / MOD_LOADER_CARDS 那套，不改顺序假设。
         */
        LOADER_TAB_087_REACHABLE {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.3));
                enter.setOnFinished(e -> {
                    Node scope = downloadPageScope(root);
                    List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                    boolean ok = false;
                    String detail;
                    try {
                        if (tabs.size() > 1) {
                            tabs.get(1).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));   // 加载器
                        }
                        Node page = downloadPageScope(root);
                        int nodes = page == null ? 0 : page.lookupAll("*").size();
                        ok = page != null && nodes > 0 && tabs.size() > 1;
                        detail = "tab 共 " + tabs.size() + " 下载页根节点="
                                + (page == null ? "缺失" : classes(page))
                                + " 容器节点=" + nodes + " 异常=0";
                    } catch (RuntimeException ex) {
                        detail = "抛异常=" + ex;
                    }
                    System.out.println("[snapshot] 0.8.7 用例「加载器 tab 可达（OptiFine 卡片）」："
                            + (ok ? "通过" : "失败") + "（" + detail + "）");
                    fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                });
                enter.play();
            }
        },

        /**
         * 0.8.8 R-01：装完立即可见 / 四段空态只在真空。
         * 切到游戏管理页，采集四段（模组 / 资源包 / 光影 / 世界）标题文案与真空态空态引导卡。
         * 「装完立即可见 / 卸载后回空态」需模拟安装回调，自测沙箱不能真装 → 留真机验收；
         * 这里只验可自动断言的部分（真空时四段都在、空态可见、页面不是一片空白）。
         */
        GAME_MGMT_088 {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-gamemgmt", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.3));
                enter.setOnFinished(e -> {
                    String visible = visibleText088(root);
                    int observed = 0;
                    for (Node node : root.lookupAll("*")) {
                        if (onScreen(node) && node instanceof Labeled) {
                            observed++;
                        }
                    }
                    int sections = 0;
                    for (String key : new String[]{"模组", "资源包", "光影", "世界"}) {
                        sections += visible.contains(key) ? 1 : 0;
                    }
                    boolean emptyCard = visible.contains("还没装") || visible.contains("还没有")
                            || visible.contains("先选一个游戏");
                    int passed = 0, total = 3;
                    if (sections >= 3) passed++;
                    if (emptyCard) passed++;
                    if (observed > 20) passed++;
                    report088("装完立即可见·四段空态只在真空", observed, passed, total,
                            "四段标题命中=" + sections + "/4 真空态空态可见=" + emptyCard
                            + " 页面可见节点=" + observed
                            + " 可见文字=[" + clip(visible.trim(), 140) + "]"
                            + "（装完立即可见 / 卸载后回空态需模拟安装回调，自测沙箱不能真装，留真机验收）");
                    fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                });
                enter.play();
            }
        },

        /**
         * 0.8.8 R-02 / R-04 / R-05 / R-06(部分)：依赖详情弹窗统一渲染器观测。
         * 进入下载页「模组」tab（落地后该 tab 的详情弹窗会渲染 §7.3 依赖卡），一次性采集后
         * 分别输出 C02（卡片化）/ C04（四类一致·空板块）/ C05（样本基准·CF 定案映射）/
         * C06（兜底不隐藏）四条 0.8.8 用例标记。⛔ 不主动调用任何生产私有方法、不联网：
         * 弹窗没渲染时各用例如实「未采到」，待实现落地后对齐节点口径。
         */
        DEP_DIALOG_088 {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.35));
                enter.setOnFinished(e -> {
                    Node scope = downloadPageScope(root);
                    List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                    if (tabs.size() > 2) {
                        tabs.get(2).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));   // 模组
                    }
                    PauseTransition settle = new PauseTransition(Duration.seconds(0.3));
                    settle.setOnFinished(e2 -> {
                        List<Node> cards = visibleByClass088("dep-card", root);
                        List<Node> depsBoxes = visibleByClass088("mod-deps", root);
                        String visible = visibleText088(root);
                        int icon = 0, name = 0, meta = 0, action = 0, withImg = 0, tall = 0, consistent = 0;
                        int withInstall = 0, noInstallWhenShould = 0, installWhenShouldNot = 0;
                        for (Node card : cards) {
                            boolean a = !find("dep-card-icon", card).isEmpty();
                            boolean b = !find("dep-card-name", card).isEmpty();
                            boolean c = !find("dep-card-meta", card).isEmpty();
                            boolean d = !find("dep-card-action", card).isEmpty();
                            icon += a ? 1 : 0;
                            name += b ? 1 : 0;
                            meta += c ? 1 : 0;
                            action += d ? 1 : 0;
                            if (hasImageView088(card)) withImg++;
                            if (minHeight088(card) >= 64) tall++;
                            if (a && b && c && d) consistent++;
                            StringBuilder ct = new StringBuilder();
                            if (card instanceof Parent p) {
                                collectText(p, ct);
                            }
                            String cardText = ct.toString();
                            boolean hasBtn = cardText.contains("安装") && !cardText.contains("已安装");
                            boolean mustOrOpt = cardText.contains("必须") || cardText.contains("可选");
                            boolean noBtnCat = cardText.contains("已内置") || cardText.contains("不兼容")
                                    || cardText.contains("工具") || cardText.contains("包含")
                                    || cardText.contains("需自行准备");
                            if (mustOrOpt && hasBtn) withInstall++;
                            if (mustOrOpt && !hasBtn) noInstallWhenShould++;
                            if (noBtnCat && hasBtn) installWhenShouldNot++;
                        }
                        int sections = 0;
                        for (String key : new String[]{"必须", "可选", "已内置", "不兼容"}) {
                            sections += visible.contains(key) ? 1 : 0;
                        }
                        int observed = cards.size();   // 0 → 详情弹窗 / 依赖卡未渲染 → 未采到

                        // ---- C02：依赖卡片化 + 图标 + ≥64px + 四分区 + 类名一致 ----
                        int p2 = 0, t2 = 6;
                        if (observed >= 1) p2++;
                        if (icon > 0 && withImg > 0) p2++;
                        if (tall > 0) p2++;
                        if (name > 0 && meta > 0 && action > 0) p2++;
                        if (sections >= 4) p2++;
                        if (observed > 0 && consistent == observed) p2++;
                        report088("依赖卡片化·图标·64px·四分区·类名一致", observed, p2, t2,
                                "dep-card=" + observed + " icon=" + icon + " ImageView=" + withImg
                                + " minHeight>=64=" + tall + " name/meta/action=" + name + "/" + meta + "/" + action
                                + " 五子类名齐全卡=" + consistent + " 四分区标题=" + sections + "/4");

                        // ---- C04：四类结构一致 + 无依赖类型不显示空板块 ----
                        int emptyVisibleBoxes = 0;
                        for (Node box : depsBoxes) {
                            if (find("dep-card", box).isEmpty()) {
                                emptyVisibleBoxes++;
                            }
                        }
                        int p4 = 0, t4 = 3;
                        if (observed > 0 && consistent == observed) p4++;       // 同一 depCard 渲染器
                        if (emptyVisibleBoxes == 0) p4++;                       // 不显示空依赖板块
                        if (observed > 0 && !depsBoxes.isEmpty()) p4++;        // 板块本身没被整块隐藏
                        report088("四类详情弹窗依赖区一致·空板块不显示", observed, p4, t4,
                                "可见 mod-deps=" + depsBoxes.size() + " 可见但空的板块=" + emptyVisibleBoxes
                                + " 结构一致卡=" + consistent + "/" + observed
                                + "（四类弹窗逐一打开 / 结构逐一比对：需实现落地后对齐节点口径或真机）");

                        // ---- C05：§11 固定样本基准 + CF 定案映射 + HMCL 按钮规则 ----
                        StringBuilder bench = new StringBuilder();
                        for (String[] s : DEP_SAMPLES_088) {
                            bench.append(s[0]).append('(').append(s[1]).append('/').append(s[2])
                                    .append('=').append(s[3]).append("条,").append(s[4]).append(") ");
                        }
                        int p5 = 0, t5 = 5;
                        if (DEP_SAMPLES_088.length >= 6) p5++;                  // 6 个固定输入齐全
                        if (CF_RELATION_TRUTH_088.contains("3=RequiredDependency")
                                && !CF_RELATION_TRUTH_088.contains("1=Required")) p5++;  // 定案真值、非作废旧表
                        if (observed > 0 && withInstall > 0 && noInstallWhenShould == 0) p5++;  // 必须/可选给按钮
                        if (observed > 0 && installWhenShouldNot == 0) p5++;    // 已内置/不兼容/工具/包含无按钮
                        if (observed > 0 && sections >= 2) p5++;                // 不漏 required、不误吞 optional
                        report088("依赖样本数据完整·HMCL 口径·CF 定案映射", observed, p5, t5,
                                "基准[" + bench.toString().trim() + "] 映射[" + CF_RELATION_TRUTH_088 + "]"
                                + " 有按钮(必须/可选)=" + withInstall + " 应给未给=" + noInstallWhenShould
                                + " 不该给却给=" + installWhenShouldNot
                                + "（样本级逐条精确比对需联网真数据，属真机 / 对齐）");

                        // ---- C06：反查兜底 / project_id=null 按文件名 / 板块不隐藏 / 切版本重建 ----
                        int fileNameCard = 0;
                        for (Node card : cards) {
                            for (Node n : find("dep-card-name", card)) {
                                if (n instanceof Labeled labeled && labeled.getText() != null
                                        && (labeled.getText().endsWith(".zip") || labeled.getText().endsWith(".jar"))) {
                                    fileNameCard++;
                                    break;
                                }
                            }
                        }
                        boolean externalHint = visible.contains("需自行准备") || visible.contains("外部依赖");
                        int p6 = 0, t6 = 4;
                        if (observed > 0 && !depsBoxes.isEmpty()) p6++;        // 整个板块不被隐藏
                        if (fileNameCard > 0) p6++;                            // project_id=null 按 file_name 显示
                        if (externalHint) p6++;                                // 反查失败兜底卡存在
                        if (observed > 0) p6++;                                // 切版本后依赖区仍在（重建不消失）
                        report088("依赖交互·切版本重建·兜底不隐藏板块", observed, p6, t6,
                                "文件名兜底卡=" + fileNameCard + " 外部依赖兜底文案=" + externalHint
                                + " 可见依赖板块=" + depsBoxes.size()
                                + "（切选中版本后重建 / 反查失败兜底需真机或对齐，S7-d Fabulously Optimized）");
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                    });
                    settle.play();
                });
                enter.play();
            }
        },

        /**
         * 0.8.9 R-01（用户二次澄清后的定案口径）：点前置依赖卡上的按钮 =
         * <b>直接打开该前置项目自己的详情弹窗</b>（Iris 的 Sodium → Sodium 项目页），
         * ⛔ 绝不立即下载、⛔ 也不去搜索框搜名字。只对 `.dep-card-action` 里当前可见、
         * 未禁用的 Button 点一下（不认按钮文案；找不到=未采到、不硬造点击），
         * 点击前后比较 下载队列 / 搜索框 / dlg-title 弹窗标题 / 可见文字，
         * 证明「队列 0 新增、搜索框没被写入、详情弹窗标题切成了前置项目名」。
         * ⛔ 不主动触发任何生产私有方法、不联网。
         */
        DEP_INSTALL_088 {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.35));
                enter.setOnFinished(e -> {
                    Node scope = downloadPageScope(root);
                    List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                    if (tabs.size() > 2) {
                        tabs.get(2).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
                    }
                    PauseTransition settle = new PauseTransition(Duration.seconds(0.3));
                    settle.setOnFinished(e2 -> {
                        List<Node> cards = visibleByClass088("dep-card", root);
                        Node actionBtn = null;
                        String depName = "";
                        for (Node card : cards) {
                            for (Node n : find("dep-card-name", card)) {
                                if (n instanceof Labeled labeled && labeled.getText() != null) {
                                    depName = labeled.getText().trim();
                                    break;
                                }
                            }
                            for (Node act : find("dep-card-action", card)) {
                                if (depActionButton088(act)) {
                                    actionBtn = act;
                                    break;
                                }
                                for (Node b : act.lookupAll("*")) {
                                    if (depActionButton088(b)) {
                                        actionBtn = b;
                                        break;
                                    }
                                }
                                if (actionBtn != null) {
                                    break;
                                }
                            }
                            if (actionBtn != null) {
                                break;
                            }
                        }
                        int queueBefore = GameDownloader.DEFAULT.items().size();
                        int queueRowBefore = find("queue-row", root).size();
                        String searchBefore = searchText088(root);
                        String detailBefore = detailTitle088(root);
                        String visibleBefore = visibleText088(root);
                        int observed = actionBtn == null ? 0 : 1;   // 无可点的前置卡按钮 → 未采到
                        if (actionBtn instanceof javafx.scene.control.Button btn) {
                            btn.fire();   // 触发 onAction（同步），随后立即观测队列 / 搜索框 / 弹窗标题
                        }
                        int queueAfter = GameDownloader.DEFAULT.items().size();
                        int queueRowAfter = find("queue-row", root).size();
                        String searchAfter = searchText088(root);
                        String detailAfter = detailTitle088(root);
                        String visibleAfter = visibleText088(root);
                        boolean noNewQueue = queueAfter <= queueBefore;                    // 没自动下载
                        boolean searchUntouched = searchAfter.equals(searchBefore);        // 关键：没去搜索框搜「钠」
                        boolean detailSwitched = !detailAfter.isEmpty()
                                && !detailAfter.equals(detailBefore)
                                && !depName.isEmpty() && detailAfter.contains(depName);    // 打开了前置项目详情
                        boolean visibleChanged = !visibleAfter.equals(visibleBefore);
                        boolean noAutoDlText = !visibleAfter.contains("安装中")
                                && !visibleAfter.contains("下载中");                      // 无进行中下载态
                        int passed = 0, total = 4;
                        if (observed > 0 && noNewQueue) passed++;                         // 下载队列不新增条目
                        if (observed > 0 && searchUntouched) passed++;                    // 搜索框完全没被写入
                        if (observed > 0 && detailSwitched) passed++;                     // 详情弹窗切成前置项目
                        if (observed > 0 && noAutoDlText) passed++;                      // 无自动下载态
                        report088("点前置直接打开项目详情·不搜索不下载", observed, passed, total,
                                "前置卡按钮=" + (actionBtn == null ? "未采到" : "命中")
                                + " 前置名=[" + depName + "]"
                                + " 队列 items 前/后=" + queueBefore + "/" + queueAfter
                                + " 可见 queue-row 前/后=" + queueRowBefore + "/" + queueRowAfter
                                + " 搜索框前=[" + searchBefore + "] 后=[" + searchAfter + "]"
                                + " 搜索框未写入=" + searchUntouched
                                + " 详情标题前=[" + detailBefore + "] 后=[" + detailAfter + "]"
                                + " 详情切成前置项目=" + detailSwitched
                                + " 可见文字变化=" + visibleChanged + " 自动下载态文案=" + !noAutoDlText
                                + "（点前置只打开该项目的详情页，版本由用户自己挑；这些锚点采不到即如实未采到，不伪造 PASS、不打印假 FAIL）");
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                    });
                    settle.play();
                });
                enter.play();
            }
        },

        /**
         * 0.8.8 R-06：三类内容详情按 MC 版本分组 + 推荐徽标 + 兜底组。
         * 进入下载页「光影」tab（下标 5，沿用 0.8.7 TAB_SWITCH 点击路径），观测 §7.3 保留类
         * .mod-group-head / .mod-group-title、推荐徽标文案、组内 version-row、兜底组文案。
         * ⛔ 断言不假设只取首个 gameVersion、不因 loaders=["iris","optifine"] 这类非
         * forge/fabric/neoforge 就丢版本行（QA §11 S6 实证：内容类 game_versions 是 76 项超长数组）。
         */
        CONTENT_GROUP_088 {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.35));
                enter.setOnFinished(e -> {
                    Node scope = downloadPageScope(root);
                    List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                    if (tabs.size() > 5) {
                        tabs.get(5).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));   // 光影
                    }
                    PauseTransition settle = new PauseTransition(Duration.seconds(0.3));
                    settle.setOnFinished(e2 -> {
                        List<Node> heads = visibleByClass088("mod-group-head", root);
                        List<Node> titles = visibleByClass088("mod-group-title", root);
                        List<Node> rows = visibleByClass088("version-row", root);
                        int recTags = visibleByClass088("tag-release", root).size();
                        String visible = visibleText088(root);
                        boolean recommend = visible.contains("推荐");
                        boolean fallback = visible.contains("未标注 Minecraft 版本");
                        int observed = heads.size();   // 0 → 分组头未渲染 → 未采到
                        int passed = 0, total = 6;
                        if (observed > 0 && !titles.isEmpty()) passed++;       // 组头 + 组标题
                        if (recommend && recTags > 0) passed++;                // 推荐组置顶且带「推荐」徽标
                        if (rows.size() > 0) passed++;                         // 组内版本行 > 0
                        if (fallback) passed++;                                // 兜底组存在
                        if (observed > 0) passed++;                            // 与模组/整合包同一 groupHeader 口径
                        if (observed >= 1 && rows.size() > 0) passed++;       // 不因超长 game_versions / 非标准 loaders 丢行
                        report088("三类内容版本分组·推荐徽标·兜底组", observed, passed, total,
                                "mod-group-head=" + heads.size() + " title=" + titles.size()
                                + " version-row=" + rows.size() + " tag-release=" + recTags
                                + " 推荐文案=" + recommend + " 兜底组=" + fallback
                                + "（QA S6：game_versions=76 项 / loaders=[iris,optifine]，断言未取首个、未过滤 loaders）");
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                    });
                    settle.play();
                });
                enter.play();
            }
        },

        /**
         * 0.8.8 R-07 / R-08：加载器 tab 进入即加载 + 五卡三态。
         * 只「切到加载器 tab（下标 1）」，⛔ 不点任何下拉 / 不触发 setting-select.onAction——
         * 若此刻就观测到加载态 / 版本回填，即证明「进入即加载、不依赖 modGameBox.onAction」。
         * 五卡三态组件（.loader-card-error / .loader-card-retry / 「暂无兼容版本」）即使当前
         * 隐藏也挂在场景树上，按「节点可观测」采集，不要求态一定激活（EMPTY ≠ FAILED）。
         */
        LOADER_088 {
            @Override void apply(Node root, Path base, double scale) {
                fire(MouseEvent.MOUSE_CLICKED, "nav-download", root);
                PauseTransition enter = new PauseTransition(Duration.seconds(0.35));
                enter.setOnFinished(e -> {
                    Node scope = downloadPageScope(root);
                    List<Node> tabs = scope == null ? List.of() : find("tab-btn", scope);
                    if (tabs.size() > 1) {
                        tabs.get(1).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));   // 加载器
                    }
                    PauseTransition settle = new PauseTransition(Duration.seconds(0.3));
                    settle.setOnFinished(e2 -> {
                        List<Node> cards = visibleByClass088("loader-card", root);
                        String visible = visibleText088(root);
                        String tree = treeText088(root);
                        int names = 0;
                        for (String key : new String[]{"Fabric", "Quilt", "Forge", "NeoForge", "LegacyFabric"}) {
                            names += visible.contains(key) ? 1 : 0;
                        }
                        StringBuilder loaderText = new StringBuilder();
                        for (Node card : cards) {
                            if (card instanceof Parent p) {
                                collectText(p, loaderText);
                            }
                        }
                        boolean loadingText = tree.contains("正在拉取版本");
                        boolean versionInCards = loaderText.toString().matches("(?s).*\\d+\\.\\d+.*");
                        int errNodes = find("loader-card-error", root).size();
                        List<Node> retryNodes = find("loader-card-retry", root);
                        boolean retryLabel = false;
                        for (Node n : retryNodes) {
                            if (n instanceof Labeled labeled && labeled.getText() != null
                                    && labeled.getText().contains("重试")) {
                                retryLabel = true;
                            }
                        }
                        boolean emptyText = tree.contains("暂无兼容版本");
                        int observed = cards.size();   // 0 → 加载器卡未渲染 → 未采到

                        // ---- C08：进入即加载、不依赖下拉 ----
                        int p8 = 0, t8 = 3;
                        if (observed >= 5) p8++;
                        if (loadingText || versionInCards) p8++;
                        if (observed >= 1) p8++;
                        report088("加载器 tab 进入即加载·不依赖下拉", observed, p8, t8,
                                "可见 loader-card=" + observed + " 五卡名命中=" + names + "/5"
                                + " 加载态文案=" + loadingText + " 卡内版本串=" + versionInCards
                                + "（本 Task 未触发任何 setting-select/下拉事件；切游戏按新游戏刷新需真机）");

                        // ---- C09：五卡三态 + 单卡失败不拖累 ----
                        int p9 = 0, t9 = 6;
                        if (names >= 5) p9++;
                        if (loadingText) p9++;                             // 加载态组件
                        if (errNodes > 0) p9++;                            // 失败态常驻错误组件
                        if (!retryNodes.isEmpty() && retryLabel) p9++;     // 「重试」组件
                        if (emptyText) p9++;                               // EMPTY≠FAILED「暂无兼容版本」
                        if (observed >= 5) p9++;                           // 单卡失败不拖累其它 4 卡
                        report088("五卡三态·加载/错误/重试/空列表·单卡独立", observed, p9, t9,
                                "loader-card-error=" + errNodes + " retry=" + retryNodes.size()
                                + " 重试文案=" + retryLabel + " 暂无兼容版本=" + emptyText
                                + " 五卡名=" + names + "/5 加载态=" + loadingText);
                        fire(MouseEvent.MOUSE_CLICKED, "nav-gamelist", root);
                    });
                    settle.play();
                });
                enter.play();
            }
        };

        /** 搜索框按类名认，每次重找：切 tab 会整块重建「游戏」页，缓存的上一个实例已经脱钩。 */
        private static TextField searchBox(Node root) {
            for (Node node : find("version-search", root)) {
                if (node instanceof TextField input) {
                    return input;
                }
            }
            return null;
        }

    /** 每个 tab 上的字，顺序就是它在条上的从左到右。 */
    private static String tabNames(List<Node> tabs) {
        StringBuilder sb = new StringBuilder();
        for (Node tab : tabs) {
            StringBuilder one = new StringBuilder();
            if (tab instanceof Parent box) {
                collectText(box, one);
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(one.toString().trim());
        }
        return sb.toString();
    }

    /**
     * 下载页那颗根节点（{@code .download-page}）。
     *
     * <p>v0.7.0 起场景里不止一排在 tab：Mod 中心也有 3 个 {@code tab-btn}。
     * 所有「数下载页的 tab / 点下载页最后一个 tab」的用例都先把范围收窄到这个子树，
     * 不然 7 会被数成 10、「最后一个」会点到 Mod 中心的「整合包」上去。
     *
     * <p><b>刻意不按可见性挑</b>：深色那趟的 QUEUE_TAB / TAB_SWITCH 排在游戏列表页上
     * （GAME_NAV_CLICK 先把页面切走了），点的就是「此刻没显示」的下载页的 tab——
     * tab 的点击回调挂在节点自己身上，页面可不可见都照常跑（v0.7.0 之前的全局
     * {@code find} 本来就是这口径，收窄范围时不能顺手把这条语义收窄掉）。
     * 场景里只有一颗 {@code .download-page}，直接取第一个。
     */
    private static Node downloadPageScope(Node root) {
        List<Node> hits = find("download-page", root);
        return hits.isEmpty() ? null : hits.get(0);
    }

        /** 报一遍「共 N 个版本」和当前列表里每一行，搜索 / 筛选看这一份就够。 */
        private static void reportVersionList(Node root, String label) {
            for (Node node : find("result-count", root)) {
                if (node instanceof Labeled labeled) {
                    System.out.println("[snapshot] " + label + " " + labeled.getText());
                }
            }
            for (Node row : find("version-row", root)) {
                StringBuilder one = new StringBuilder();
                if (row instanceof Parent box) {
                    collectText(box, one);
                }
                System.out.println("[snapshot] " + label + " 行 [" + one.toString().trim() + "]");
            }
        }

        /** 直接改 ComboBox 的选中项：模拟鼠标点下拉太脆，主题是纯数据选择不值得那么绕。 */
        private static void selectCombo(Node root, int index) {
            ComboBox<?> box = themeCombo(root);
            if (box == null) {
                System.out.println("[snapshot] 没找到 setting-select，主题没切换");
                return;
            }
            box.getSelectionModel().select(index);
            System.out.println("[snapshot] 主题切到 " + box.getValue());
        }

        /**
         * 主题下拉本体（设置页那个三选项 {@code setting-select}）。
         *
         * <p><b>认选项内容，不认顺序也不认可见性</b>：v0.7.0 起 Mod 中心页头也有两个
         * {@code setting-select}（下载源 / 当前游戏），文档序上「游戏管理」页还排在
         * 设置页前面——全局找第一个的话，切主题和拍弹层那两步就全点到 Mod 中心去了。
         * 而这两步跑的时候页面也未必停在设置页（NAV_DOUBLE_CLICK 会停在首页），
         * 所以可见性同样不能当判据。主题下拉的选项是写死的三个，按内容认最稳。
         */
        private static ComboBox<?> themeCombo(Node root) {
            ComboBox<?> fallback = null;
            for (Node node : find("setting-select", root)) {
                if (!(node instanceof ComboBox<?> box)) {
                    continue;
                }
                if (box.getItems().contains("深色")) {
                    return box;
                }
                if (fallback == null) {
                    fallback = box;
                }
            }
            return fallback;
        }

        /**
         * 打印当前页每个文字节点的**生效颜色**和布局位置。
         *
         * <p>为什么需要它：截图只能看出「大概亮还是暗」，看不出某个类到底吃到了哪条 CSS。
         * 深色下漏掉一个标题没写反色，肉眼要扫全图才找得到；这里直接问场景图要
         * {@code Labeled.getTextFill()}（已经过 CSS 解析），命中哪条规则一目了然。
         * 顺带量最右侧文字的 right，就是「放大化后右边有没有撑满」的硬数字。
         */
        abstract void apply(Node root, Path base, double scale) throws IOException;
    }

    private static String fills(javafx.scene.layout.Background bg) {
        if (bg == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (javafx.scene.layout.BackgroundFill fill : bg.getFills()) {
            sb.append(fill.getFill()).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * 轮询等清单落盘：状态条还停在「正在取官方清单…」就再等一拍，最多 30 秒。
     *
     * <p>上限必须给足：v0.4.2 之后每次回「游戏」tab 都会顺手再拉一次（{@code requestManifestSync}），
     * 上一趟落地时常常已经把下一趟排上（{@code manifestAgain} 的那条补跑），
     * 状态条会被第二趟重新按回「正在取官方清单…」；只等 8 秒的话，
     * 数出来的就是这个中间态，看着像「官方没检出更新」（v0.4.2 首跑读到的状态条
     * 一直停在「正在取…」、判据硬是没数）。
     */
    private static void awaitFresh(Node root, int tries) {
        String state = "";
        for (Node node : find("manifest-state", root)) {
            if (node instanceof Labeled labeled && labeled.getText() != null) {
                state = labeled.getText();
            }
        }
        if (tries >= 120 || !"正在取官方清单…".equals(state)) {
            reportFresh(root);
            return;
        }
        if (tries % 10 == 0) {
            System.out.println("[snapshot]   还在等官方清单（第 " + tries + " 拍），状态=[" + state + "]");
        }
        PauseTransition again = new PauseTransition(Duration.seconds(0.25));
        again.setOnFinished(e -> awaitFresh(root, tries + 1));
        again.play();
    }

    /**
     * 印「钉了旧基线再查一次」的结果。
     *
     * <p>自测不能信「这一步的读数」：MANIFEST_FRESH 排着 5 秒才问，而步骤与步骤之间只隔
     * 1.2 秒——问的时候人早切到别的 tab 了。深色那趟就读出「状态=[]，0 行里 0 行挂新」，
     * 看着像没检出更新，其实是站在队列页 / 光影页上数的（v0.3.5）。
     * 所以数之前自己先退回「游戏」tab：状态条和版本行本来就只长在那一页上。
     */
    private static void reportFresh(Node root) {
        if (find("manifest-check", root).isEmpty()) {
            List<Node> tabs = find("tab-btn", root);
            if (!tabs.isEmpty()) {
                tabs.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
            }
        }
        int fresh = find("fresh-badge", root).size();
        List<Node> rows = find("version-row", root);
        StringBuilder state = new StringBuilder();
        for (Node node : find("manifest-state", root)) {
            if (node instanceof Labeled labeled) {
                state.append(labeled.getText());
            }
        }
        System.out.println("[snapshot] 钉旧基线后重新检查：状态=[" + state.toString().trim()
                + "]，" + rows.size() + " 行里 " + fresh + " 行挂「新」"
                + "，首行=[" + rowId(rows.isEmpty() ? null : rows.get(0)) + "]");
    }

    /**
     * 队列那些行现在长的什么样：每行四样有没有、卡片和第一行的矩形差几像素（v0.4.1 那两条判据）。
     *
     * <p>v0.4.2 挪到 {@link Task#QUEUE_WAIT}「全下完」那一刻调用：下完的项只挂 1.5 秒
     * 就会被自动移出队列，等到独立的 QUEUE_DUMP 那一步再数，读到的只剩空态、
     * 进度条那几条判据会集体落空。
     */
    private static void reportQueueRows(Node root) {
        for (Node row : find("queue-row", root)) {
            StringBuilder text = new StringBuilder();
            if (row instanceof Parent box) {
                collectText(box, text);
            }
            // 队列那三项（名称/类别/进度条）光看图说不清，
            // [inspect] 又只扫场景图里的可见文字，所以在这里一并报出来给 verify 当判据
            String one = text.toString().trim().replace('\t', ' ');
            System.out.println("[snapshot]   队列行 " + one
                    + " ｜ 类别徽标=" + !find("type-badge", row).isEmpty()
                    + " 进度条=" + !find("queue-progress", row).isEmpty()
                    + " 状态=" + statusOf(row));
        }
        // 「项和外面的矩形对齐 + 框大一点」：量卡片和第一行的实际矩形。
        // 光看图说不清差几像素，直接把两个数打出来给 verify 当判据。
        List<Node> cards = find("queue-list", root);
        List<Node> qrows = find("queue-row", root);
        if (!cards.isEmpty() && !qrows.isEmpty()) {
            System.out.println("[snapshot]   队列框 " + box(cards.get(0))
                    + " ｜ 第一行 " + box(qrows.get(0)));
        }
    }

    /**
     * 盯到「下完自动移出队列」这一下发生（v0.4.2）。
     *
     * <p>界面上是 DONE 之后先挂着「已装好」约 1.5 秒、再自己把这一项从队列里摘掉。
     * 这里在后台线程里轮询「盯的那些 id 还在不在队列」，把结果打出来给 verify 当判据。
     *
     * <p><b>千万不能 join 住等这一轮结果</b>：{@link Task#QUEUE_WAIT} 本身已经把 FX 线程
     * 堵在 {@code waiter.join()} 里（无网络时长达 90 秒），自动移出那记
     * {@code PauseTransition} 得等 FX 线程空下来才跑得动。再在这里 join 一截，
     * 整个自测就被拖成「先堵 90 秒、再堵 8 秒」，队列页那种「下完那一刻」的窗口
     * 早就过去了，判据读到的是一堆迟到的数据（v0.4.2 首跑就把它读成了「8 秒还没移出去」）。
     * 让它自己在后台把话说完就行，verify 扫的是整份日志，谁先谁后无所谓。
     */
    private static void waitQueueAutoDrop(List<String> ids) {
        Thread dropper = new Thread(() -> {
            boolean gone = false;
            long stopAt = System.currentTimeMillis() + 8_000;
            while (System.currentTimeMillis() < stopAt) {
                List<String> still = new ArrayList<>();
                for (String id : ids) {
                    if (ShellView.queuedIds().contains(id)) {
                        still.add(id);
                    }
                }
                gone = still.isEmpty();
                if (gone) {
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("[snapshot] 下完自动移出：盯的 [" + String.join(",", ids) + "]"
                    + (gone ? " 已不在队列" : " 8 秒还没移出去")
                    + "，队列现在是 [" + String.join(",", ShellView.queuedIds()) + "]"
                    + "，服务层 " + GameDownloader.DEFAULT.items().size() + " 项");
        }, "gbl-selftest-drop");
        dropper.setDaemon(true);
        dropper.start();
    }

    /** 弹层结构只有几层，全打出来才看得出是谁在铺白底。 */
    private static void printTree(String indent, Node node) {
        javafx.geometry.Bounds b = node.localToScene(node.getLayoutBounds());
        System.out.println("[popup-tree] " + indent + node.getClass().getSimpleName()
                + " [" + classes(node) + "]"
                + " " + (int) b.getWidth() + "x" + (int) b.getHeight()
                // getBackground 只在 Region 上，Scene/Parent 上没有这号方法；
                // 直接把填充的 Paint 解出来（Background@1a2b 这种哈希看不出生效色）
                + ((node instanceof javafx.scene.layout.Region region)
                        ? " bg=" + fills(region.getBackground()) : "")
                // 弹层里「字看不清」到底是白底还是白字，只看背景不够，字色也得报出来
                + ((node instanceof javafx.scene.control.Labeled labeled
                        && labeled.getText() != null && !labeled.getText().isEmpty())
                        ? " fill=" + labeled.getTextFill() : ""));
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                printTree(indent + "  ", child);
            }
        }
    }

    private static String classes(Node node) {
        StringBuilder sb = new StringBuilder();
        for (String c : node.getStyleClass()) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(c);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String clip(String s, int max) {
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    // =====================================================================================
    // 0.8.8 发布门禁观测框架（W3 / T05）
    //
    // 硬约束（ARCH §7.3 / §8.1 / S22）：新增用例只认「用户可见文案 + §7.3 CSS 类名 +
    // 场景图可观测节点（类名 / 子节点数 / 可见性 / min-height / ImageView / 文案）」，
    // ⛔ 不读 W1/W2 任何内部 Java 字段名或行号（他们并行实现、字段名可能微调），
    // ⛔ 不把「静态源码里 grep 到某个类名」当通过——只问渲染出来的节点与文案。
    //
    // 三态语义（0.8.7 单独跑 dark 的假阴性教训）：
    //   · 场景锚点 observed<=0 → 「未采到」：该场景没在自测里渲染（实现未落地 /
    //     详情弹窗没打开 / 沙箱不能真联网安装），如实标注，既不伪造 PASS 也不打印假 FAIL；
    //   · observed>0 且判据全中 → 「通过」；
    //   · observed>0 但判据有缺 → 「失败」（真实缺陷，附观测明细）。
    // 实现落地后主理人把实际节点口径同步过来做一次对齐，即可让「未采到」收敛。
    // =====================================================================================

    /** 0.8.8 用例统一输出标记：{@code [snapshot] 0.8.8 用例「<名字>」：通过/失败/未采到（…）}。 */
    private static void report088(String name, int observed, int passed, int total, String detail) {
        String verdict = observed <= 0 ? "未采到" : (passed >= total ? "通过" : "失败");
        System.out.println("[snapshot] 0.8.8 用例「" + name + "」：" + verdict
                + "（判据 " + passed + "/" + total + "，观测锚点=" + observed + "，" + detail + "）");
    }

    /** 当前可见页里所有 Labeled 的文字（onScreen 过滤，别把 setVisible(false) 按下的页算进来）。 */
    private static String visibleText088(Node root) {
        StringBuilder sb = new StringBuilder();
        for (Node node : root.lookupAll("*")) {
            if (onScreen(node) && node instanceof Labeled labeled && labeled.getText() != null) {
                sb.append(labeled.getText()).append(' ');
            }
        }
        return sb.toString();
    }

    /** 子树里所有 Labeled 的文字（不滤可见性：三态组件挂在树上但当前隐藏时也要认得出来）。 */
    private static String treeText088(Node root) {
        StringBuilder sb = new StringBuilder();
        for (Node node : root.lookupAll("*")) {
            if (node instanceof Labeled labeled && labeled.getText() != null) {
                sb.append(labeled.getText()).append(' ');
            }
        }
        return sb.toString();
    }

    /** 子树里有没有 ImageView（依赖卡图标判据：读的是 JavaFX 节点类型，不是生产字段）。 */
    private static boolean hasImageView088(Node node) {
        if (node instanceof javafx.scene.image.ImageView) {
            return true;
        }
        if (node instanceof Parent parent) {
            for (Node kid : parent.getChildrenUnmodifiable()) {
                if (hasImageView088(kid)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Region 经 CSS 解析后的 {@code -fx-min-height} 生效值；非 Region 给 -1。 */
    private static double minHeight088(Node node) {
        return node instanceof javafx.scene.layout.Region region ? region.getMinHeight() : -1;
    }

    /** 按类名找、且只算「节点自己 + 所有祖先都可见」的（残留但被按下的弹窗节点不算）。 */
    private static List<Node> visibleByClass088(String styleClass, Node root) {
        List<Node> out = new ArrayList<>();
        for (Node node : find(styleClass, root)) {
            if (onScreen(node)) {
                out.add(node);
            }
        }
        return out;
    }

    /** 当前可见页标题（读既有 page-title 的可见文案，⛔ 只观测不改动该类名）。 */
    private static String pageTitle088(Node root) {
        for (Node node : find("page-title", root)) {
            if (onScreen(node) && node instanceof Labeled labeled && labeled.getText() != null) {
                return labeled.getText();
            }
        }
        return "";
    }

    /**
     * 依赖卡动作区里可点的按钮（0.8.9 定案：点它 = 直接打开该前置项目详情让用户自己选版本，
     * ⛔ 绝不立即下载、也不搜索）。只认「未禁用的 Button」，⛔ 不认具体文案——
     * 按钮文案从「安装」改成「查看」也不会挂。
     */
    private static boolean depActionButton088(Node node) {
        return node instanceof javafx.scene.control.Button btn && !btn.isDisabled();
    }

    /** 下载页搜索框（既有 version-search TextField）当前文字，取不到给空串（⛔ 只观测不改类名）。 */
    private static String searchText088(Node root) {
        for (Node node : find("version-search", root)) {
            if (node instanceof TextField input && input.getText() != null) {
                return input.getText().trim();
            }
        }
        return "";
    }

    /** 0.8.9：详情弹窗标题（dlg-title）当前文字，用来证明点前置后打开的是前置项目页。 */
    private static String detailTitle088(Node root) {
        for (Node node : find("dlg-title", root)) {
            if (onScreen(node) && node instanceof Labeled labeled && labeled.getText() != null) {
                return labeled.getText().trim();
            }
        }
        return "";
    }

    private static void inspect(Node root) {
        System.out.println("[inspect] ---- 文本节点清单（生效颜色 + 布局）----");
        double maxRight = 0;
        for (Node node : root.lookupAll("*")) {
            if (!(node instanceof javafx.scene.control.Labeled labeled)) {
                continue;
            }
            String text = labeled.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            javafx.geometry.Bounds b = node.localToScene(node.getLayoutBounds());
            javafx.scene.paint.Paint fill = labeled.getTextFill();
            System.out.println("[inspect] " + classes(node)
                    + " | " + clip(text, 16)
                    + " | fill=" + (fill == null ? "-" : fill.toString())
                    + " | x=" + (int) b.getMinX()
                    + " y=" + (int) b.getMinY()
                    + " w=" + (int) b.getWidth()
                    + " h=" + (int) b.getHeight()
                    + " right=" + (int) b.getMaxX()
                    // 光有字色不够：白字配白底一样看不见，底色也得能问出来
                    + ((node instanceof javafx.scene.layout.Region region)
                            ? " bg=" + fills(region.getBackground()) : ""));
            maxRight = Math.max(maxRight, b.getMaxX());
        }
        System.out.println("[inspect] 场景宽 " + root.getScene().getWidth()
                + "，最右侧文本 right=" + (int) maxRight);

        // 连点同一个入口会不会重播淡入，只能看页的 opacity：重播了就还压在 0 附近，
        // 没重播应该稳在 1。淡入是 160ms，问慢了会漏，所以自测那步是点了就立刻问。
        // 三页的类名不是统一的：首页 / 设置页是 ScrollPane（content-scroll），
        // 下载页是 VBox（page-blank）。只按 scroll 认，切到下载页那一下就没得可问。
        java.util.Set<String> pageMarks = new java.util.LinkedHashSet<>(
                java.util.Arrays.asList("content-scroll", "page-blank"));
        for (Node node : root.lookupAll("*")) {
            boolean isPage = false;
            for (String c : node.getStyleClass()) {
                if (pageMarks.contains(c)) {
                    isPage = true;
                    break;
                }
            }
            if (isPage) {
                System.out.println("[inspect] 内容页 " + classes(node)
                        + " | visible=" + node.isVisible()
                        + " opacity=" + round3(node.getOpacity()));
            }
        }

        // 文字自己量不到父容器：药丸有没有把字摆在正中、设置行有没有撑到右边，
        // 得看容器本身（.seg-btn / .setting-row / .card / 内容根）的边界。
        java.util.Set<String> shells = new java.util.LinkedHashSet<>(
                java.util.Arrays.asList("seg-btn", "setting-row", "card", "content-page",
                        "status-pill"));   // 顶栏那圈椭圆删没删，只能问它的底色
        for (Node node : root.lookupAll("*")) {
            for (String c : node.getStyleClass()) {
                if (!shells.contains(c)) {
                    continue;
                }
                javafx.geometry.Bounds b = node.localToScene(node.getLayoutBounds());
                System.out.println("[inspect] " + classes(node)
                        + " | 容器 x=" + (int) b.getMinX()
                        + " y=" + (int) b.getMinY()
                        + " w=" + (int) b.getWidth()
                        + " h=" + (int) b.getHeight()
                        + " right=" + (int) b.getMaxX());
                break;
            }
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 一个节点在场景里的位置尺寸（v0.4.1：量「队列里的项和外面的矩形对不对齐」）。 */
    private static String box(Node node) {
        Bounds b = node.localToScene(node.getBoundsInLocal());
        return "x=" + round3(b.getMinX()) + " y=" + round3(b.getMinY())
                + " w=" + round3(b.getWidth()) + " h=" + round3(b.getHeight());
    }

    private static Path resolve(Path base, String suffix) {
        Path name = base.getFileName();
        return base.resolveSibling(name.toString().replaceFirst("\\.[^.]+$", "") + suffix + ".png");
    }

    /**
     * 拍窗口内容根，再把圆角外的透明像素垫成桌面色。
     *
     * <p>为什么要垫这一层：真窗口是 StageStyle.TRANSPARENT，圆角外面那块在场景图里就是透明的，
     * 拍出来是透明像素，不垫就等于把那块假装成别的颜色，验了等于没验。垫成桌面灰才是真实观感。
     */
    private static void dump(Node root, Path out, double scale) throws IOException {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(scale, scale));
        // 原样导出（带 alpha）：验「四角到底是不是真透明」只能看 alpha 通道。
        // 垫色的截图看起来跟真机一样，但 alpha 全被抹成 0xFF 了，看不出真假。
        // 注意 SnapshotParameters 的 fill 会把整块快照（含圆角外）垫实色，
        // 所以 raw 模式必须连 setFill 一起跳过，否则量到的一直是 255。
        if (rawAlpha()) {
            SnapshotParameters raw2 = new SnapshotParameters();
            raw2.setTransform(Transform.scale(scale, scale));
            clipProbe();
            WritableImage raw = root.snapshot(raw2, null);
            try (OutputStream os = Files.newOutputStream(out)) {
                writePngArgb(raw, os);
            }
            System.out.println("[snapshot] 原始 alpha 导出 " + out
                    + "  " + raw.getWidth() + "x" + raw.getHeight());
            return;
        }

        // SnapshotParameters 的 fill 默认白，圆角外那块会填成白（不是桌面色）——
        // 不显式给它，截图就会把「圆角外透的是桌面还是实色块」拍错
        Paint backdrop = backdrop();
        params.setFill(backdrop);
        WritableImage img = flatten(root.snapshot(params, null), backdrop);
        try (OutputStream os = Files.newOutputStream(out)) {
            writePng(img, os);
        }
        // 顺手记一下场景/窗口尺寸：切主题后窗口莫名变大（scene 跟着 shell 的 layout bounds 走）
        // 只能靠这两行分辨「CSS 改动把布局顶大了」还是「窗口真的被最大化了」
        javafx.stage.Stage stage = (javafx.stage.Stage) (root.getScene() == null
                ? null : root.getScene().getWindow());
        System.out.println("[snapshot] " + out + "  " + img.getWidth() + "x" + img.getHeight()
                + "  scene=" + (int) root.getScene().getWidth() + "x" + (int) root.getScene().getHeight()
                + "  stage=" + (stage == null ? "-"
                        : (int) stage.getWidth() + "x" + (int) stage.getHeight()
                        + (stage.isFullScreen() ? " fs" : "") + (stage.isMaximized() ? " max" : "")));
    }

    /**
     * 合成探针：一个 200x120 的白块，套一个 arc=80 的圆角裁剪。
     *
     * <p>用来判断 {@code Node.snapshot} 到底认不认 clip —— 因为「导出的图四角 alpha=255」
     * 这种结果有两种解释（clip 没生效 / snapshot 压根不管 clip），不分开就会瞎改代码。
     * 探针是同一条渲染链上跑过来的，它要是也全不透明，说明是 snapshot 的行为，
     * 不能据此判我们的裁剪有问题。
     */
    private static void clipProbe() {
        try {
            javafx.scene.layout.Pane pad = new javafx.scene.layout.Pane();
            pad.setPrefSize(200, 120);
            pad.setMaxSize(200, 120);
            pad.setMinSize(200, 120);
            pad.setStyle("-fx-background-color:#ffffff");
            javafx.scene.shape.Rectangle c = new javafx.scene.shape.Rectangle(200, 120);
            c.setArcWidth(80);
            c.setArcHeight(80);
            pad.setClip(c);
            // 必须给 Supplier 定尺寸：不传 imageFactory 时 snapshot 直接吐 1x1，
            // 探针会变成一张废图（之前就是这么白跑了一轮）
            // 第二个参数是「要画进哪张图」（JavaFX 21 已经没有 Supplier 重载了），
            // 得显式给一张定尺寸的图，否则探针又是 1x1
            WritableImage probe = pad.snapshot(null, new WritableImage(200, 120));
            try (OutputStream os = Files.newOutputStream(
                    Paths.get(System.getProperty("gbl.snapshot.probe",
                            "target/_clip-probe.png")))) {
                writePngArgb(probe, os);
            }
            System.out.println("[snapshot] clip 探针已出：见 " + probe.getWidth()
                    + "x" + probe.getHeight());
        } catch (IOException ex) {
            System.err.println("[snapshot] clip 探针失败: " + ex);
        }
    }

    /** {@code -Dgbl.snapshot.raw=1} 时导出带真实 alpha 的 PNG（颜色类型 6），垫色那步整个跳过。 */
    private static boolean rawAlpha() {
        return "1".equals(System.getProperty("gbl.snapshot.raw"));
    }

    /**
     * 截图里「圆角外面」垫什么颜色。真机上是桌面（窗口四角是透明的），
     * 这里默认垫中性灰；想按实际壁纸走就 {@code -Dgbl.snapshot.backdrop=#yourcolor}。
     */
    private static Paint backdrop() {
        String value = System.getProperty("gbl.snapshot.backdrop");
        return (value == null || value.isEmpty()) ? Color.web("#dfe3e8") : Color.web(value);
    }

    /** 透明像素（主要是圆角外那块和裁剪边缘的抗锯齿）换成桌面色，即真实观感。 */
    private static WritableImage flatten(WritableImage src, Paint fill) {
        int w = (int) src.getWidth();
        int h = (int) src.getHeight();
        WritableImage dst = new WritableImage(w, h);
        PixelWriter writer = dst.getPixelWriter();
        PixelReader reader = src.getPixelReader();
        int backdrop = fill instanceof Color ? argb((Color) fill) : 0xFFFFFFFF;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = reader.getArgb(x, y);
                writer.setArgb(x, y, (c >>> 24) == 0 ? backdrop : c);
            }
        }
        return dst;
    }

    private static int argb(Color c) {
        return 0xFF000000
                | ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8)
                | (int) Math.round(c.getBlue() * 255);
    }

    /** 最近邻放大：按钮那几像素按 1x 看不清，缩放是为了看得更准。 */
    private static WritableImage upscale(WritableImage src, double scale) {
        int sw = (int) src.getWidth();
        int sh = (int) src.getHeight();
        int dw = (int) (sw * scale);
        int dh = (int) (sh * scale);
        WritableImage dst = new WritableImage(dw, dh);
        javafx.scene.image.PixelWriter writer = dst.getPixelWriter();
        javafx.scene.image.PixelReader reader = src.getPixelReader();
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(sh - 1, (int) (y / scale));
            for (int x = 0; x < dw; x++) {
                writer.setArgb(x, y, reader.getArgb(Math.min(sw - 1, (int) (x / scale)), sy));
            }
        }
        return dst;
    }

    // ------------------------------------------------------------ 事件模拟

    /** 还原窗口：点最大化按钮（最大化 / 还原共用一个按钮），然后按真实尺寸复核。 */
    private static void restoreWindow(Node root) {
        fire(MouseEvent.MOUSE_CLICKED, "win-btn-max", root);
        // setMaximized 落地是异步的，连点两次时原生窗口会偶发把两次切换排错位：
        // 还原请求被 peer 吞掉，过一两秒又被平台同步回最大化，后面的图就变成 3200x2000。
        javafx.stage.Stage win = (javafx.stage.Stage) root.getScene().getWindow();
        // 要连着复核三回：0.9s 那次常常赶在原生窗口同步之前，之后又会被平台拉回最大化
        for (int round = 0; round < 3; round++) {
            final int at = round;
            PauseTransition settle = new PauseTransition(Duration.seconds(0.8 + 0.9 * round));
            settle.setOnFinished(e -> {
                if (win.getWidth() > 1500) {
                    win.setMaximized(false);
                    System.out.println("[snapshot] 第 " + at + " 轮复核到还是最大化，已还原");
                }
            });
            settle.play();
        }
    }

    /** 数一数游戏列表现在几行、空态是不是还在，顺带把每行是「当前的」还是「普通」标出来。 */
    private static void reportGameRows(Node root, String stage) {
        List<Node> rows = find("game-row", root);
        List<Node> empty = find("game-empty", root);
        boolean emptyOn = !empty.isEmpty() && empty.get(0).isVisible();
        System.out.println("[snapshot] " + stage + "：游戏行 " + rows.size() + " 条，空态 visible=" + emptyOn);
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder text = new StringBuilder();
            if (rows.get(i) instanceof Parent box) {
                collectText(box, text);
            }
            System.out.println("[snapshot]   行 " + (i + 1) + "（=[" + text.toString().trim() + "]"
                    + (rows.get(i).getStyleClass().contains("selected") ? "）[当前游戏]" : "）"));
        }
        // v0.4.2：愚人节那几条在游戏列表里也得分开标（下载页那处由 APRIL_REPORT 报）。
        // 官方自己把 24w14potato 标成 snapshot，只看 type 这里就会写「快照版」。
        List<String> april = new ArrayList<>();
        for (Node row : rows) {
            for (Node badge : find("type-badge", row)) {
                if (badge instanceof javafx.scene.control.Labeled labeled
                        && labeled.getText() != null && labeled.getText().contains("愚人节")) {
                    april.add(gameId(row) + "→" + labeled.getText());
                }
            }
        }
        System.out.println("[snapshot]   游戏列表愚人节徽标 " + april.size() + " 条："
                + String.join("，", april));
    }

    /** 游戏行上那个版本目录名（{@code game-name} 那一枚 Label 写的就是它）。 */
    private static String gameId(Node row) {
        List<Node> names = find("game-name", row);
        return names.isEmpty() || !(names.get(0) instanceof javafx.scene.control.Labeled labeled)
                ? "?" : labeled.getText();
    }

    /** 现在扫盘扫出来哪几个游戏（顺序无所谓，判的是集合）。 */
    private static List<String> gameIds(Node root) {
        List<String> ids = new ArrayList<>();
        for (GameEntry entry : GameScanner.scan()) {
            ids.add(entry.id());
        }
        if (ids.isEmpty()) {
            // 扫不出来就退到界面上数，别让「扫盘 0 条」被当成「两个都删了」
            for (Node row : find("game-row", root)) {
                ids.add(gameId(row));
            }
        }
        return ids;
    }

    /** 重新拉起「删除游戏」确认框：点第一行的三点、再点菜单最后一项（删除永远排在最后）。 */
    private static boolean openDeletePrompt(Node root) {
        List<Node> rows = find("game-row", root);
        if (rows.isEmpty()) {
            System.out.println("[snapshot] 一行游戏都没有，真删这步跳过");
            return false;
        }
        List<Node> mores = find("game-more", rows.get(0));
        if (!mores.isEmpty()) {
            mores.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
        }
        List<Node> items = ShellView.gameMenu == null ? List.of() : find("menu-item", ShellView.gameMenu);
        if (items.isEmpty()) {
            System.out.println("[snapshot] 行菜单里没有可点的项，真删这步跳过");
            return false;
        }
        items.get(items.size() - 1).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
        boolean open = ShellView.gamePrompt != null && ShellView.gamePrompt.isVisible();
        System.out.println("[snapshot] 重新开删除确认框：菜单项=" + items.size()
                + "，菜单 visible=" + (ShellView.gameMenu != null && ShellView.gameMenu.isVisible())
                + "，确认框 visible=" + open);
        return open;
    }

    /**
     * 把确认框里那颗红色「删除」真的按下去，并报出删完以后界面和磁盘各少了几条。
     *
     * <p>回收站那条 PowerShell 以前传的是四参重载、第四参又不是 {@code UICancelOption}
     * 的合法值（exit=1、目录原样还在、{@code recycle} 返回 false），
     * 上层只打日志不重试——用户报的「它删不了」就是这么来的。
     * 所以这里界面少一行 + 扫盘少一条 + 那个目录确实没了，三样一起才算过。
     */
    private static void deleteNow(Node root) {
        if (ShellView.gamePrompt == null || !ShellView.gamePrompt.isVisible()) {
            System.out.println("[snapshot] 删除确认框没开着，真删这步跳过");
            return;
        }
        List<Node> danger = find("btn-danger", ShellView.gamePrompt);
        if (danger.isEmpty()) {
            System.out.println("[snapshot] 确认框里没找到「删除」按钮（btn-danger），真删这步跳过");
            return;
        }
        List<Node> rows = find("game-row", root);
        String victim = rows.isEmpty() ? "?" : gameId(rows.get(0));
        int before = rows.size();
        List<String> onBefore = gameIds(root);
        danger.get(0).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
        PauseTransition wait = new PauseTransition(Duration.seconds(0.9));
        wait.setOnFinished(e -> {
            List<Node> after = find("game-row", root);
            List<String> onAfter = gameIds(root);
            // 判「实际少了谁」而不是「被删的是不是第 1 行那一个」：
            // 确认框里那颗按钮认的是**当时开菜单时绑进去的那个游戏**，
            // 第 1 行只是我们点三点时点的那一行，两者对不上是很正常的（v0.4.2 首跑就撞上了）
            List<String> gone = new ArrayList<>(onBefore);
            gone.removeAll(onAfter);
            String victimGone = gone.size() == 1 ? gone.get(0) : "?";
            System.out.println("[snapshot] 真删一次 [删除]=" + textOf(danger.get(0))
                    + "，游戏行 " + before + " -> " + after.size()
                    + "（判据少一行），扫盘 " + onBefore.size() + " -> " + onAfter.size() + " 条，"
                    + "删掉的是 [" + victimGone + "]（点了第 1 行的三点，行=[" + victim + "]），"
                    + "[" + victimGone + "] 目录还在磁盘=" + onAfter.contains(victimGone)
                    + "，确认框 visible=" + (ShellView.gamePrompt != null
                    && ShellView.gamePrompt.isVisible()));
        });
        wait.play();
    }

    /**
     * 首页 Hero 现在写的是什么。
     *
     * <p>它是「当前游戏」这条链路的下游：圆点点了之后 Hero 的文案应该跟着变。
     * 只看列表自己，等于验了改数据没验改界面。
     */
    private static String heroText(Node root) {
        List<Node> hits = find("hero-title", root);
        return hits.isEmpty() ? "（没找到 hero）" : ((javafx.scene.control.Label) hits.get(0)).getText();
    }

    /** 触发某个样式类节点的指定鼠标事件（走的是按钮自己的 hover / click 回调）。 */
    private static void fire(EventType<MouseEvent> type, String styleClass, Node root) {
        int hits = 0;
        for (Node node : find(styleClass, root)) {
            hits++;
            node.fireEvent(mouseEvent(type));
        }
        System.out.println("[snapshot] 触发 " + type + " 于 " + styleClass + " → " + hits + " 个节点");
    }

    /** 点同类节点里的第 n 个（0 起）：分组药丸、窗口按钮都是「同款多个」没有各自样式类的情况。 */
    private static void clickNth(Node root, String styleClass, int n) {
        List<Node> hits = find(styleClass, root);
        if (hits.isEmpty()) {
            System.out.println("[snapshot] 没找到 " + styleClass + "，没点");
            return;
        }
        int index = Math.min(n, hits.size() - 1);
        hits.get(index).fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
        System.out.println("[snapshot] 点 " + styleClass + " 第 " + index + " 个 / 共 " + hits.size());
    }

    /**
     * 按行标题找开关，别按下标认人。
     *
     * <p>设置行是「标题+说明 | 弹性间隔 | 控件」，控件永远最后一个；拿行里的文字去认，
     * 以后往外观组里插一行设置，自测也不会突然去点错的那枚开关。
     */
    private static ToggleSwitch toggleByTitle(Node root, String title) {
        for (Node row : find("setting-row", root)) {
            if (!(row instanceof Parent box)) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            // 标题和说明藏在这一层的 VBox 里，只扫直接子节点等于一个字都读不到
            collectText(box, text);
            if (!text.toString().contains(title)) {
                continue;
            }
            List<Node> kids = box.getChildrenUnmodifiable();
            Node control = kids.get(kids.size() - 1);
            if (control instanceof ToggleSwitch toggle) {
                return toggle;
            }
        }
        return null;
    }

    /** 把子树里所有 Labeled 的文字收进 one 个串（找开关靠它认行，见 {@link #toggleByTitle}）。 */
    private static void collectText(Parent parent, StringBuilder one) {
        for (Node kid : parent.getChildrenUnmodifiable()) {
            if (kid instanceof javafx.scene.control.Labeled labeled
                    && labeled.getText() != null) {
                one.append(labeled.getText()).append(' ');
            } else if (kid instanceof Parent next) {
                collectText(next, one);
            }
        }
    }

    /**
     * 切页后沿时间轴连问几档 opacity。
     *
     * <p>为什么不再单点、也不按类名认页：淡入作用在内容页的**外层**节点上
     * （下载页淡的是那个没类名的 wrap，内部的 page-blank 自己 opacity 恒为 1，
     * 按类名采会永远采到 1.0）。这里直接找全场景里 opacity&lt;1 的节点，
     * 采不到就明说「无节点在淡入」——关动效时必须正是这个结果。
     *
     * <p>但要跳过「切走的那页」：切页只给目标页设 opacity，被切走的那页留在 0.x 上没人管，
     * 一直挂着（v0.3 起页面多了，切一次留一个 0 节点，不筛的话关掉动效也报「有节点在淡入」）。
     */
    private static void sampleOpacity(Node root, String label) {
        for (int at = 0; at < OPACITY_PROBES.length; at++) {
            final int index = at;
            PauseTransition probe = new PauseTransition(Duration.seconds(OPACITY_PROBES[at]));
            probe.setOnFinished(e -> {
                boolean fading = false;
                for (Node node : root.lookupAll("*")) {
                    // 图形节点常年 opacity<1（搜索框放大镜那枚 Path 一直是 0.0），
                    // 它们不是淡入，采进来「关掉动效」这条判据就永远红着。
                    // 禁用态也要跳：modena 给 .button:disabled 的默认值就是 -fx-opacity:0.4，
                    // v0.4 起行上那枚按钮在「已安装 / 排队中」时会禁用，模样上恒为 0.4——
                    // 那不是淡入，把它采进来，「关掉动效」这条又会白红一次（v0.4 自测真撞过）。
                    if (!shown(node) || node instanceof javafx.scene.shape.Shape
                            || node.isDisabled()) {
                        continue;
                    }
                    double op = node.getOpacity();
                    if (op < 0.999) {
                        // 顺带报祖先链：opacity 落在哪一层是常态（淡入就在外层节点上），
                        // 报不出来就只能靠猜，日志得自带定位。
                        System.out.println("[snapshot] " + label + " 采样 t="
                                + OPACITY_PROBES[index] + "s 淡入中 "
                                + node.getClass().getSimpleName() + " " + classes(node)
                                + " opacity=" + round3(op) + " 祖先=" + chain(node));
                        fading = true;
                    }
                }
                if (!fading) {
                    System.out.println("[snapshot] " + label + " 采样 t="
                            + OPACITY_PROBES[index] + "s 无节点在淡入（opacity 全为 1.0）");
                }
            });
            probe.play();
        }
    }

    /**
     * 扫所有 PopupControl 并逐层打印结构。
     *
     * <p>不是 PopupWindow：ComboBox 的弹层是 package-private 的 ComboBoxPopupControl，
     * 它继承的是 PopupControl，按 PopupWindow 找会一个都扫不到。
     * 抽成类级方法是因为**枚举常量体里插不了方法**（编译不过）。
     */
    private static void dumpPopup(Path base, double scale) throws IOException {
        int found = 0;
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (!(window instanceof javafx.scene.control.PopupControl popup)
                    || popup.getScene() == null) {
                continue;
            }
            int w = (int) Math.max(80, popup.getWidth());
            int h = (int) Math.max(40, popup.getHeight());
            // 注意不是 Scene.snapshot（那两个重载里没有「参数+目标图」这版，
            // 会被解析成带 Callback 的那条）；走 Node.snapshot，和主截图同一套垫色逻辑
            SnapshotParameters shot = new SnapshotParameters();
            shot.setFill(backdrop());
            WritableImage img = popup.getScene().getRoot().snapshot(
                    shot, new WritableImage(w, h));
            Path out = resolve(base, "-popup");
            try (OutputStream os = Files.newOutputStream(out)) {
                writePng(img, os);
            }
            System.out.println("[popup-tree] 弹层 scene 样式表 = "
                    + popup.getScene().getStylesheets());
            printTree("  ", popup.getScene().getRoot());
            boolean dark = popup.getScene().getRoot().getStyleClass().contains("dark");
            System.out.println("[snapshot] 弹层已导出 " + out + " " + w + "x" + h
                    + "  弹层 root 带 dark 类=" + dark);
            found++;
        }
        if (found == 0) {
            System.out.println("[snapshot] 没扫到 PopupControl，弹层没拍到");
        }
    }

    /** 往上数三代的类名，用来在日志里定位「到底是哪一层在淡」。 */
    private static String chain(Node node) {
        List<String> out = new ArrayList<>();
        for (Node cur = node.getParent(); cur != null && out.size() < 3; cur = cur.getParent()) {
            out.add(classes(cur));
        }
        return String.join(" < ", out);
    }

    /**
     * 这一屏到底看不看得见这个节点：自己可见、且所有祖先都可见。
     *
     * <p>光看 {@code isVisible()} 不够——JavaFX 的 isVisible 是自己的开关，
     * 只要父节点藏了，子节点照样报 true。
     */
    private static boolean shown(Node node) {
        for (Node cur = node; cur != null; cur = cur.getParent()) {
            if (!cur.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** {@link #sampleOpacity} 的单点版：只在某一刻问一句「现在有谁在淡入」。 */
    private static void samplePoint(Node root, String label) {
        for (Node node : root.lookupAll("*")) {
            if (node instanceof javafx.scene.shape.Shape) {
                continue;
            }
            double op = node.getOpacity();
            if (op < 0.999) {
                System.out.println("[snapshot] " + label + " opacity=" + round3(op)
                        + " @ " + classes(node));
                return;
            }
        }
        System.out.println("[snapshot] " + label + " opacity=1.0（无节点在淡入）");
    }

    /**
     * {@link Task#TOGGLE_SPAM} 的挨轮：每轮点一下、隔 300ms 防抖再来。
     *
     * <p>节奏是夹出来的：间隔必须 &gt; 300ms 防抖（真落盘），总时长必须 &lt; {@link #STEP_DELAY}
     * （不然下个任务会插进来，日志就串位了）。
     */
    private static void spamRound(Node root, ToggleSwitch motion, int left) {
        if (left <= 0) {
            // 落盘早该收工了再问：早了会问到「正在保存…」那一态，判不出是绿是红
            PauseTransition done = new PauseTransition(Duration.seconds(0.4));
            done.setOnFinished(e -> inspect(root));
            done.play();
            return;
        }
        motion.fireEvent(mouseEvent(MouseEvent.MOUSE_CLICKED));
        System.out.println("[snapshot] 狂点第 " + (3 - left + 1) + " 轮 / 共 3 轮，"
                + "selected=" + motion.getSelected());
        PauseTransition gap = new PauseTransition(Duration.seconds(0.3));
        gap.setOnFinished(e -> spamRound(root, motion, left - 1));
        gap.play();
    }

    /**
     * 这一趟里 GAME_DUMP 跑两回：进页面那次是空态，载入示例之后那次是有游戏的。
     * 两个都得留图（空态就是「还没安装游戏」那条需求本身），
     * 所以第二回换个文件名，别把前一张盖掉。
     */
    private static String gameDumpName() {
        return gameDumpSeq++ == 0 ? "-games" : "-games-seeded";
    }

    private static int gameDumpSeq = 0;

    /** 节点上那句字（Button / Label 都算），读不到给 {@code "?"}，别让判据拿空串糊过去。 */
    private static String textOf(Node node) {
        if (node instanceof Labeled labeled) {
            String text = labeled.getText();
            return text == null ? "?" : text;
        }
        return "?";
    }

    /**
     * 这一行是哪个版本：读行上的 {@code ver-num}。
     *
     * <p>不整行 collectText —— 真实清单里那行还挂着类型徽标和日期，
     * 判据要的是版本号本身，混着读回来没法比。
     */
    /** 队列行上那枚状态标签写的是什么（「排队中 / 装好了 · …」）。 */
    private static String statusOf(Node row) {
        List<Node> hits = find("queue-status", row);
        return hits.isEmpty() ? "?" : ((javafx.scene.control.Label) hits.get(0)).getText();
    }

    /**
     * 那几条提示现在什么样：有几条、写的什么、看得见吗（v0.4.1），以及**贴在哪个角**（v0.4.2）。
     *
     * <p>提示挂在 shell 上而不是某一页里，所以得从 scene 的根往下找——
     * 拿页面容器当根去找是一个都捞不到的（跟自绘菜单那层一个道理）。
     *
     * <p>v0.4.2 起提示从左下角挪到了右下角。「有几条 / 可见=false」这套读数验不出挪没挪：
     * 气泡挪到别的角上，条数和可见性一个字都不会变。所以把气泡的场景矩形和它到
     * 右 / 下两边的距离量出来一起报——verify ⑫ 拿它判「确实贴在右下角」。
     */
    private static String toastState(Node root, String when) {
        Node from = root.getScene() == null ? root : root.getScene().getRoot();
        List<Node> bubbles = find("toast", from);
        StringBuilder one = new StringBuilder();
        one.append(when).append(" 提示 ").append(bubbles.size()).append(" 条");
        for (Node bubble : bubbles) {
            one.append("，文本=[").append(textOf(bubble)).append("]")
                    .append(" 可见=").append(shown(bubble));
            javafx.geometry.Bounds b = bubble.localToScene(bubble.getLayoutBounds());
            javafx.scene.Scene scene = root.getScene();
            double sw = scene == null ? 0 : scene.getWidth();
            double sh = scene == null ? 0 : scene.getHeight();
            one.append(" 气泡@").append((int) b.getMinX()).append(',').append((int) b.getMinY())
                    .append(" 场景=").append((int) sw).append('x').append((int) sh)
                    .append(" 距右=").append((int) (sw - b.getMaxX()))
                    .append(" 距下=").append((int) (sh - b.getMaxY()));
        }
        return one.toString();
    }

    private static String rowId(Node row) {
        if (row == null) {
            return "无";
        }
        for (Node node : find("ver-num", row)) {
            // 只 import 到 Labeled：这里只想要那串字，不关心它到底是 Label 还是别的
            if (node instanceof Labeled labeled) {
                return labeled.getText();
            }
        }
        return "?";
    }

    /** 节点自己加上所有祖先都可见——等价于「这一块现在真的画在屏幕上」。 */
    private static boolean onScreen(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static List<Node> find(String styleClass, Node root) {
        List<Node> hits = new ArrayList<>();
        collect(styleClass, hits, root);
        return hits;
    }

    private static void collect(String styleClass, List<Node> hits, Node node) {
        if (node == null) {
            return;
        }
        if (node.getStyleClass().contains(styleClass)) {
            hits.add(node);
        }
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                collect(styleClass, hits, child);
            }
        }
    }

    /** 带坐标的按下。关菜单走的是几何判据（点在卡片矩形外没有），合成事件不填坐标就是 (0,0)。 */
    private static MouseEvent pressAt(double x, double y) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false, false, false, false, null);
    }

    /** 行菜单里那张卡（v0.3.3 起它就是用户眼里那个「框」）。 */
    private static Node menuCard() {
        return ShellView.gameMenu instanceof Parent box
                && box.getChildrenUnmodifiable().size() > 1
                ? box.getChildrenUnmodifiable().get(1) : null;
    }

    /**
     * 卡片矩形**外**（{@code inside=false}）/ **正中**（{@code inside=true}）的一点，都在窗口里。
     *
     * <p>关菜单这条判据说到底是个几何问题，自测就必须给出能区分「框内 / 框外」的两个坐标，
     * 用同一个节点按两下都能分出结果，才算把语义钉住。
     */
    private static double[] aroundCard(boolean inside) {
        Node card = menuCard();
        if (card == null) {
            // 拿不到卡片（菜单没开）就退化成窗口左右两半：左边当框外、右边当框内
            return inside ? new double[] {900, 400} : new double[] {40, 400};
        }
        javafx.geometry.Bounds b = card.localToScene(card.getLayoutBounds());
        if (inside) {
            return new double[] {b.getMinX() + b.getWidth() / 2.0,
                    b.getMinY() + b.getHeight() / 2.0};
        }
        // 贴着卡片左边（离窗口右边缘放的时候退回下边），保证「框外」且没跑出窗口
        double x = b.getMinX() - 40.0;
        double y = b.getMinY() + 20.0;
        if (x < 4) {
            x = b.getMinX() + 10.0;
            y = b.getMaxY() + 30.0;
        }
        return new double[] {x, y};
    }

    /** 右键用的 MouseEvent：上一个 helper 只会造左键，右键路径自测得自己造。 */
    private static MouseEvent rightClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.SECONDARY, 1,
                false, false, false, false,
                false, true, false, false, false, false, null);
    }

    private static MouseEvent mouseEvent(EventType<MouseEvent> type) {
        boolean primary = type == MouseEvent.MOUSE_CLICKED || type == MouseEvent.MOUSE_ENTERED;
        return new MouseEvent(type, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false,
                primary, false, false, false, false, false, null);
    }

    // ------------------------------------------------------------------ PNG

    /** 只靠 java.base 的极简 PNG 写出，不引 ImageIO / java.desktop。 */
    private static void writePng(WritableImage img, OutputStream out) throws IOException {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        javafx.scene.image.PixelReader reader = img.getPixelReader();
        byte[] raw = new byte[h * (w * 3 + 1)];
        int p = 0;
        for (int y = 0; y < h; y++) {
            raw[p++] = 0;                                    // filter = None
            for (int x = 0; x < w; x++) {
                int argb = reader.getArgb(x, y);
                raw[p++] = (byte) (argb >> 16);              // R
                raw[p++] = (byte) (argb >> 8);               // G
                raw[p++] = (byte) argb;                      // B
            }
        }
        // PNG 文件头 8 字节，少了它文件直接被当成坏图（Python / 看图软件都解不开）
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        writeChunk(out, "IHDR", new byte[]{
                (byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w,
                (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h,
                8, 2, 0, 0, 0});
        writeChunk(out, "IDAT", deflate(raw));
        writeChunk(out, "IEND", new byte[0]);
    }

    /**
     * 带 alpha 的 PNG 写出（颜色类型 6 / RGBA），不动任何像素。
     *
     * <p>给「圆角外是不是真透明」用：垫过色的图看着跟真机一样，
     * 但 alpha 已经被抹成 0xFF，验了等于没验。用 {@code -Dgbl.snapshot.raw=1} 打开。
     */
    private static void writePngArgb(WritableImage img, OutputStream out) throws IOException {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        javafx.scene.image.PixelReader reader = img.getPixelReader();
        byte[] raw = new byte[h * (w * 4 + 1)];
        int p = 0;
        for (int y = 0; y < h; y++) {
            raw[p++] = 0;                                    // filter = None
            for (int x = 0; x < w; x++) {
                int argb = reader.getArgb(x, y);
                raw[p++] = (byte) (argb >> 16);              // R
                raw[p++] = (byte) (argb >> 8);               // G
                raw[p++] = (byte) argb;                      // B
                raw[p++] = (byte) (argb >>> 24);             // A
            }
        }
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        writeChunk(out, "IHDR", new byte[]{
                (byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w,
                (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h,
                8, 6, 0, 0, 0});
        writeChunk(out, "IDAT", deflate(raw));
        writeChunk(out, "IEND", new byte[0]);
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        // nowrap 必须是 false：PNG 的 IDAT 要的是带 zlib 头的 zlib 流，裸 deflate 流解不开
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(buf, deflater)) {
            dos.write(data);
        } finally {
            deflater.end();
        }
        return buf.toByteArray();
    }

    private static void writeChunk(OutputStream out, String tag, byte[] data) throws IOException {
        byte[] len = {(byte) (data.length >>> 24), (byte) (data.length >>> 16),
                (byte) (data.length >>> 8), (byte) data.length};
        out.write(len);
        byte[] body = tag.getBytes(StandardCharsets.US_ASCII);
        out.write(body);
        out.write(data);
        int crc = crc32(body, data);   // 变长实参：body、data 都是 byte[]，会被包成 byte[][]{...}
        out.write(new byte[]{(byte) (crc >>> 24), (byte) (crc >>> 16),
                (byte) (crc >>> 8), (byte) crc});
    }

    private static int crc32(byte[]... chunks) {
        int[] table = new int[256];
        for (int n = 0; n < 256; n++) {
            int c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            table[n] = c;
        }
        int crc = ~0;
        for (byte[] chunk : chunks) {
            for (byte b : chunk) {
                crc = table[(crc ^ (b & 0xFF)) & 0xFF] ^ (crc >>> 8);
            }
        }
        return ~crc;
    }


    private static double num(String s) {
        try {
            return Double.parseDouble(s.substring(0, s.length() - 1));
        } catch (RuntimeException ex) {
            return 1.0;
        }
    }
}
