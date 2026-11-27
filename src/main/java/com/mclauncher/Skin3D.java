package com.mclauncher;

import javafx.event.EventHandler;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.ParallelCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;

/**
 * 用 JavaFX 3D 预览 Minecraft 皮肤（64×64，或老式 64×32）。
 *
 * <p><b>0.9.8 r5：几何 / UV / 外层壳整套移植 HMCL</b>，不再自己手写面坐标：
 * <ul>
 *   <li>{@link SkinCube} —— HMCL {@code SkinCube}：按「盒子展开图」算贴图坐标，面索引写死；</li>
 *   <li>{@link SkinMultiCubes} —— HMCL {@code SkinMultipleCubes}：第二图层按不透明像素摆小方块；</li>
 *   <li>六个部位（头/身/左右臂/左右腿）的贴图区起点、尺寸、缩放系数逐字照抄 HMCL {@code SkinCanvas}。</li>
 * </ul>
 * 旧实现（r1~r4）用「手工 R 坐标表 + 逐面 U 镜像」补左右，副作用是侧面与四肢会串贴图区
 * （表现就是「头发色长到肩膀上」）；逐面镜像只对正面/背面成立，本来就不该用来修整体左右。
 *
 * <p><b>拖拽同样逐行照抄</b> HMCL {@code SkinCanvas.enableRotation(0.5)}：水平拖转偏航，
 * 垂直拖按当前偏航角把位移拆给俯仰（kx）与翻滚（kz），Alt / Ctrl / Shift 单轴锁定，滚轮缩放。
 *
 * <p>三处与 HMCL 不同的地方，都是本机硬约束，不是取舍：
 * <ol>
 *   <li>相机只能是 {@link ParallelCamera}（本机 Intel Arc + JavaFX21 下 PerspectiveCamera 零渲染）；</li>
 *   <li>所以「偏航挂相机」改成「偏航挂模型」，并取等价符号：我们的相机相当于 HMCL 相机绕 Y 转 180°，
 *       换算关系是 {@code 偏航 = -yaw、俯仰 = -xRotate、翻滚 = -zRotate}（180° 旋转把 X/Z 旋转取反，
 *       Y 旋转不变），初始值也照 HMCL 的 180（正对镜头）；</li>
 *   <li>HMCL 的骨骼动画与披风不在预览范围内。</li>
 * </ol>
 *
 * <p>用法：{@code Skin3D v = new Skin3D(pngBytes, "classic");} 把 {@code v.subScene()} 丢进弹窗的
 * 一个 Pane 里即可；{@code v.setSkin(bytes, model)} 换皮；关弹窗时调 {@code v.stop()} 解绑监听。
 */
public final class Skin3D {

    /** 0.9.8 r2 指纹：皮肤预览按 HMCL 方式回到立体 3D。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_098_R2_SKIN = "0.9.8 r2 HMCL风格3D皮肤预览";
    static { System.err.println("[skin3d] " + FP_098_R2_SKIN); }

    /** 0.9.8 r3 指纹：照抄 HMCL 拖拽旋转。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_098_R3_SKIN = "0.9.8 r3 HMCL拖拽旋转3D皮肤预览";
    static { System.err.println("[skin3d] " + FP_098_R3_SKIN); }

    /** 0.9.8 r4 指纹：拖拽三轴（kx/kz 权重 + 翻滚 + 修饰键）。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_098_R4_SKIN = "0.9.8 r4 全抄HMCL拖拽三轴";
    static { System.err.println("[skin3d] " + FP_098_R4_SKIN); }

    /** 0.9.8 r5 指纹：几何 / UV / 外层壳整套移植 HMCL。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_098_R5_SKIN = "0.9.8 r5 皮肤几何与UV整套移植HMCL";
    static { System.err.println("[skin3d] " + FP_098_R5_SKIN); }

    /** 开场偏航：HMCL 的 yRotate 初值就是 180（正对镜头）。0 会看到后脑勺。 */
    private static final double INITIAL_SPIN = 180;

    /** 拖拽灵敏度：HMCL enableRotation(0.5)。 */
    private static final double SENSITIVITY = 0.5;
    private static final double SCALE_MIN = 0.1, SCALE_MAX = 10;

    /** 超采样倍数：SubScene 按 2 倍像素渲染再缩回显示，抗锯齿与高分屏都靠它。 */
    private static final double SS = 2;
    /** 视口：280×320 显示，内部 560×640 光栅化。 */
    private static final double VIEW_W = 280, VIEW_H = 320;
    /** 模型 32 单位高，屏幕约占 85%：560×0.85/32 ≈ 17（模型单位 → 超采像素）。 */
    private static final double MODEL_SCALE = 17;
    /** 模型几何中心：腿脚 y=+18 到头顶 y=-14，中心 y=+2（JavaFX +Y 朝下）。 */
    private static final double MODEL_CENTER_Y = 2;

    // ---- 变换层次：与 HMCL createSubScene() 一一对应（外 → 内）----
    //   缩放居中（≈ HMCL 相机上的 translate/scale）
    //     → 偏航 yawGroup（≈ HMCL 相机上的 yRotate）
    //       → 翻滚 rollGroup（≈ HMCL 外层 Group 的 zRotate）
    //         → 俯仰 pitchGroup（≈ HMCL root 的 xRotate）
    //           → 模型
    private final Group root = new Group();
    private final Group zoom = new Group();
    private final Group yawGroup = new Group();
    private final Group rollGroup = new Group();
    private final Group pitchGroup = new Group();
    /** 角色本体：六个部位都挂在这里（相当于 HMCL 的 root）。 */
    private final Group model = new Group();

    private final Rotate spin = new Rotate(INITIAL_SPIN, 0, MODEL_CENTER_Y, 0, Rotate.Y_AXIS);
    private final Rotate roll = new Rotate(0, 0, MODEL_CENTER_Y, 0, Rotate.Z_AXIS);
    private final Rotate pitch = new Rotate(0, 0, MODEL_CENTER_Y, 0, Rotate.X_AXIS);
    private final Scale userZoom = new Scale(1, 1, 1, VIEW_W * SS / 2, VIEW_H * SS / 2, 0);

    private final SubScene sub;
    private final EventHandler<MouseEvent> onPress, onDrag, onRelease;
    private final EventHandler<ScrollEvent> onScroll;

    private double lastX = -1, lastY = -1;

    private Image skin;
    private byte[] pngBytes;

    // ---- 六个部位（HMCL SkinCanvas 的字段名一一对应）----
    private Group head, body, larm, rarm, lleg, rleg;
    private SkinMultiCubes headOuter, bodyOuter, larmOuter, rarmOuter, llegOuter, rlegOuter;
    private SkinCube headInside, bodyInside, larmInside, rarmInside, llegInside, rlegInside;

    // 历代指纹（字面量逐字保留，构建门禁按字节扫常量池；几何已换 HMCL 版，这些只作历史标记）
    private static final String BUILD_FINGERPRINT = "0.9.3 修正Alex镜像翻转";
    private static final String R2_FINGERPRINT = "0.9.3 r2 皮肤立体光照初角";
    private static final String R4_FINGERPRINT = "0.9.6 r4 UV-U镜像补偿恢复";

    public Skin3D(byte[] png, String model) {
        this.pngBytes = png;
        this.skin = sharpTexture(png);
        buildModel(model);

        // 层次（外→内）：zoom → yaw → roll → pitch → model
        yawGroup.getTransforms().add(spin);
        rollGroup.getTransforms().add(roll);
        pitchGroup.getTransforms().add(pitch);
        pitchGroup.getChildren().add(this.model);
        rollGroup.getChildren().add(pitchGroup);
        yawGroup.getChildren().add(rollGroup);
        zoom.getChildren().add(yawGroup);
        root.getChildren().add(zoom);

        // 模型 → 视口：先平移到几何中心、再放大、最后挪到画布中心（z=-120 留在正交视锥里）。
        //
        // ★ z 轴缩放必须保持 1，不要写成 MODEL_SCALE。正交相机（ParallelCamera）只用 x/y 成像，
        //   放大 z 对画面没有任何影响，却会把模型厚度从 8 单位放大到 8×17=136、模型相对相机的
        //   z 摆动幅度放大到 ±272；模型中心只放在 z=-120，于是俯仰约 32° 以上时头和腿就会越过
        //   相机近裁剪面被「切开」，切面上露出的正是盒子内壁 —— 这就是用户说的「往上看/往下看
        //   能看到身体内部」。z 保持 1 后模型整个落在 z=-116..-124，任何角度都不会被切。
        zoom.getTransforms().addAll(
                userZoom,
                new javafx.scene.transform.Translate(VIEW_W * SS / 2, VIEW_H * SS / 2, -120),
                new javafx.scene.transform.Scale(MODEL_SCALE, MODEL_SCALE, 1),
                new javafx.scene.transform.Translate(0, -MODEL_CENTER_Y, 0));

        // 与 HMCL createSubScene() 一致：整场只挂一盏纯白环境光，颜色 100% 来自贴图。
        root.getChildren().add(new AmbientLight(Color.WHITE));

        // ★ 只能用正交相机：本机实测（Intel Arc 140T / D3D9Ex 与 sw、21.0.0 与 21.0.5）
        //   PerspectiveCamera 在 SubScene 里是零渲染，ParallelCamera 正常。
        ParallelCamera cam = new ParallelCamera();
        cam.setNearClip(0.1);
        cam.setFarClip(2000);

        sub = new SubScene(root, VIEW_W * SS, VIEW_H * SS, true, SceneAntialiasing.BALANCED);
        sub.setFill(Color.TRANSPARENT);
        sub.setCamera(cam);
        sub.setScaleX(1 / SS);
        sub.setScaleY(1 / SS);

        // 拖拽：逐行照抄 HMCL enableRotation(0.5)，坐标用 getSceneX/getSceneY（Scene 坐标即显示像素，
        // 与 HMCL 无缩放画布上的取值等价；r3 用 getX()/getY() 再手工 /SS 既绕远又差半像素）。
        onPress = e -> { lastX = -1; lastY = -1; };          // HMCL:235-238，首个 DRAGGED 不跳变
        onDrag = e -> {
            if (lastX != -1 && lastY != -1) {
                double dx = e.getSceneX() - lastX;
                double dy = e.getSceneY() - lastY;
                if (e.isAltDown() || e.isControlDown() || e.isShiftDown()) {
                    // HMCL:241-247 单轴锁定：Shift 只翻滚、Alt 只水平转、Ctrl 只俯仰。
                    // 换算见类注释：偏航 = -yaw、俯仰 = -xRotate、翻滚 = -zRotate。
                    if (e.isShiftDown()) {
                        roll.setAngle(roll.getAngle() + dy * SENSITIVITY);      // HMCL: zRotate -= dy
                    }
                    if (e.isAltDown()) {
                        spin.setAngle(spin.getAngle() - dx * SENSITIVITY);      // HMCL: yRotate += dx
                    }
                    if (e.isControlDown()) {
                        pitch.setAngle(pitch.getAngle() - dy * SENSITIVITY);    // HMCL: xRotate += dy
                    }
                } else {
                    // HMCL:249-260 的核心：垂直位移按当前偏航角拆给俯仰（kx）与翻滚（kz）。
                    // 正对（yaw=180）时 kx=-1、kz=0 → 纯俯仰；转到 90° 侧面时 kx=0、kz=±1 → 纯翻滚。
                    double yaw = normalize360(-spin.getAngle() + dx * SENSITIVITY);

                    int flagX = yaw < 90 || yaw > 270 ? 1 : -1;
                    int flagZ = yaw < 180 ? -1 : 1;
                    double kx = Math.abs(90 - yaw % 180) / 90 * flagX;
                    double kz = Math.abs(90 - (yaw + 90) % 180) / 90 * flagZ;

                    pitch.setAngle(pitch.getAngle() - dy * SENSITIVITY * kx);
                    roll.setAngle(roll.getAngle() - dy * SENSITIVITY * kz);
                    spin.setAngle(-yaw);                                    // 偏航 = -yaw
                }
            }
            lastX = e.getSceneX();
            lastY = e.getSceneY();                                          // HMCL:263-264
        };
        // 0.9.9 P3：拖出预览区再拖回时，DRAGGED 中断期间 lastX/lastY 停在旧坐标，
        // 回来第一帧 dx/dy 突增 → 瞬间甩转。抬起即复位；下一次按下本来也会复位，双保险。
        onRelease = e -> { lastX = -1; lastY = -1; };
        onScroll = e -> {                                                   // HMCL:266-269
            double delta = (e.getDeltaY() > 0 ? 1 : e.getDeltaY() == 0 ? 0 : -1) / 10D * SENSITIVITY;
            double s = Math.min(SCALE_MAX, Math.max(SCALE_MIN, userZoom.getX() - delta));
            userZoom.setX(s);
            userZoom.setY(s);
        };
        sub.addEventHandler(MouseEvent.MOUSE_PRESSED, onPress);
        sub.addEventHandler(MouseEvent.MOUSE_DRAGGED, onDrag);
        sub.addEventHandler(MouseEvent.MOUSE_RELEASED, onRelease);
        sub.addEventHandler(ScrollEvent.SCROLL, onScroll);
    }

    public SubScene subScene() {
        return sub;
    }

    /** 换皮：重建几何（细臂/宽臂会换手臂网格）+ 重绑贴图。 */
    public void setSkin(byte[] png, String model) {
        this.pngBytes = png;
        this.skin = sharpTexture(png);
        buildModel(model);
    }

    public void stop() {
        sub.removeEventHandler(MouseEvent.MOUSE_PRESSED, onPress);
        sub.removeEventHandler(MouseEvent.MOUSE_DRAGGED, onDrag);
        sub.removeEventHandler(MouseEvent.MOUSE_RELEASED, onRelease);
        sub.removeEventHandler(ScrollEvent.SCROLL, onScroll);
    }

    /** 把角度折回 [0,360)：HMCL 计算 kx/kz 前对 yaw 做的就是这两行。 */
    private static double normalize360(double angle) {
        double a = angle % 360;
        if (a < 0) {
            a += 360;
        }
        return a;
    }

    // ---------------------------------------------------------------- 建模（照抄 HMCL SkinCanvas）

    /**
     * 六个部位 + 内外两层，位置与贴图区起点逐字照抄 HMCL {@code SkinCanvas} 的字段定义。
     * HMCL 把「左臂放 -X」，这里保持一致；左右与镜头的关系靠偏航初值 180 对齐。
     */
    private void buildModel(String modelName) {
        boolean slim = "slim".equalsIgnoreCase(modelName);   // 1.3.1：与 Skin2D 口径统一
        model.getChildren().clear();

        // 贴图区（0..1 归一化）：startX/startY 是这块展开图的左上角，scaleX/scaleY 是整块展开图的宽高。
        headInside = new SkinCube(8, 8, 8, 32F / 64F, 16F / 64F, 0F, 0F, 0F, false);
        bodyInside = new SkinCube(8, 12, 4, 24F / 64F, 16F / 64F, 16F / 64F, 16F / 64F, 0.03F, false);
        larmInside = new SkinCube(slim ? 3 : 4, 12, 4, (slim ? 14F : 16F) / 64F, 16F / 64F,
                32F / 64F, 48F / 64F, 0F, slim);
        rarmInside = new SkinCube(slim ? 3 : 4, 12, 4, (slim ? 14F : 16F) / 64F, 16F / 64F,
                40F / 64F, 16F / 64F, 0F, slim);
        llegInside = new SkinCube(4, 12, 4, 16F / 64F, 16F / 64F, 16F / 64F, 48F / 64F, 0F, false);
        rlegInside = new SkinCube(4, 12, 4, 16F / 64F, 16F / 64F, 0F, 16F / 64F, 0F, false);

        headOuter = new SkinMultiCubes(8, 8, 8, 32F / 64F, 0F, 1.125, 0.2);
        bodyOuter = new SkinMultiCubes(8, 12, 4, 16F / 64F, 32F / 64F, 1, 0.2);
        larmOuter = new SkinMultiCubes(slim ? 3 : 4, 12, 4, 48F / 64F, 48F / 64F, 1.0625, 0.2);
        rarmOuter = new SkinMultiCubes(slim ? 3 : 4, 12, 4, 40F / 64F, 32F / 64F, 1.0625, 0.2);
        llegOuter = new SkinMultiCubes(4, 12, 4, 0F / 64F, 48F / 64F, 1.0625, 0.2);
        rlegOuter = new SkinMultiCubes(4, 12, 4, 0F / 64F, 32F / 64F, 1.0625, 0.2);

        PhongMaterial mat = new PhongMaterial();
        mat.setDiffuseMap(skin);            // 与 HMCL 一致：只设 diffuseMap，不要高光/自发光
        headInside.setMaterial(mat);
        bodyInside.setMaterial(mat);
        larmInside.setMaterial(mat);
        rarmInside.setMaterial(mat);
        llegInside.setMaterial(mat);
        rlegInside.setMaterial(mat);

        headOuter.updateSkin(skin);
        bodyOuter.updateSkin(skin);
        larmOuter.updateSkin(skin);
        rarmOuter.updateSkin(skin);
        llegOuter.updateSkin(skin);
        rlegOuter.updateSkin(skin);

        // 部位组装顺序与偏移量照抄 HMCL createPlayerModel()：+Y 朝下，所以头在负 Y、腿在正 Y。
        double bodyW = 8, bodyH = 12, bodyD = 4, armW = slim ? 3 : 4, legW = 4, legH = 12, headH = 8;

        head = new Group(headOuter, headInside);
        body = new Group(bodyOuter, bodyInside);
        larm = new Group(larmOuter, larmInside);
        rarm = new Group(rarmOuter, rarmInside);
        lleg = new Group(llegOuter, llegInside);
        rleg = new Group(rlegOuter, rlegInside);

        head.setTranslateY(-(bodyH + headH) / 2);
        larm.setTranslateX(-(bodyW + armW) / 2);
        rarm.setTranslateX((bodyW + armW) / 2);
        lleg.setTranslateX(-(bodyW - legW) / 2);
        rleg.setTranslateX((bodyW - legW) / 2);
        lleg.setTranslateY((bodyH + legH) / 2);
        rleg.setTranslateY((bodyH + legH) / 2);

        model.getChildren().addAll(head, body, larm, rarm, lleg, rleg);
    }

    // ---------------------------------------------------------------- 贴图

    /**
     * 最近邻硬边放大到 512×512：JavaFX 3D 对贴图做双线性过滤，直接喂 64×64 会糊成肉色块。
     * 高清皮肤（128×64 / 256×128）只取标准 64×64 区域，绝不越界写；失败给中性灰占位（非 null）。
     */
    private static WritableImage sharpTexture(byte[] png) {
        java.awt.image.BufferedImage bi;
        // 0.9.9 P3：ImageIO 解码超大 PNG 抛的是 OutOfMemoryError（Error，catch(Exception) 抓不到），
        // 会直接打爆 JVM。先读 PNG IHDR（字节 16..23 大端宽高）预检，任一维 > 4096 直接放弃解码，
        // 走与 catch 分支等价的灰底兜底，绝不尝试解码。
        if (png == null || isOversizedPng(png)) {
            bi = null;
        } else {
            try {
                bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            } catch (Exception ex) {
                bi = null;
            }
        }
        WritableImage out = new WritableImage(64 * 8, 64 * 8);
        PixelWriter pw = out.getPixelWriter();
        if (bi == null) {
            fillGray(pw, out);
            return out;
        }
        // 0.9.9 P2：64×32 老皮肤只有上半区有像素，sharpTexture 原先只把前 64×32 放大，
        // 而 buildModel 的 UV 按 64×64 取下半区（含左腿/左臂所在的 48..64 行）→ 全透明 → 左腿缺失。
        // 先把老皮肤补成 64×64 再放大；64×64 高清皮肤不进这条路径，零回归。
        if (bi.getHeight() < 64) {
            bi = x32ToX64(bi);
        }
        int w = Math.min(bi.getWidth(), 64);
        int h = Math.min(bi.getHeight(), 64);
        try {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = bi.getRGB(x, y);
                    int bx = x * 8, by = y * 8;
                    for (int dy = 0; dy < 8; dy++) {
                        for (int dx = 0; dx < 8; dx++) {
                            pw.setArgb(bx + dx, by + dy, argb);
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            fillGray(pw, out);   // 畸形图仍可能越界，退回灰占位，绝不留给渲染线程炸
        }
        return out;
    }

    /** PNG 签名；IHDR 宽高在字节 16..23（大端），任一维 > 4096 视为超大图拒解码。 */
    private static final byte[] PNG_SIG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int MAX_PNG_DIM = 4096;

    /** 只读 PNG 头 24 字节判断是否为超大图，避免解码时 OutOfMemoryError；非 PNG 不误判。 */
    private static boolean isOversizedPng(byte[] png) {
        if (png == null || png.length < 24) {
            return false;
        }
        for (int i = 0; i < PNG_SIG.length; i++) {
            if (png[i] != PNG_SIG[i]) {
                return false;
            }
        }
        int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16)
                | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16)
                | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        return w > MAX_PNG_DIM || h > MAX_PNG_DIM;
    }

    /**
     * 0.9.9 P2：把 64×32 老皮肤补成 64×64，思路照搬 {@link Skin2D} 的私有 x32ToX64：
     * 顶半区（0..32 行）原样复制，左腿/左臂由右腿/右臂镜像到下半区。
     * <p>与 2D 预览只画正面不同，3D 需要盒子六面都有贴图，所以额外把右腿/右臂整块
     * 16×16 展开区搬到左腿/左臂区（保住各面 unwrap 对齐），再用镜像正面覆盖一次。
     * 外层区（32..48 行）老皮肤本就不存在，保持透明，第二图层自然不叠。
     */
    private static java.awt.image.BufferedImage x32ToX64(java.awt.image.BufferedImage src) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(64, 64,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int maxX = Math.min(src.getWidth(), 64);
        int maxY = Math.min(src.getHeight(), 32);
        for (int y = 0; y < maxY; y++) {
            for (int x = 0; x < maxX; x++) {
                out.setRGB(x, y, src.getRGB(x, y));
            }
        }
        // 1.3.1：照抄 HMCL SkinHelper.x32Tox64(PixelCopier...) 的六面镜像。
        // 旧实现只整块搬 + 镜像正面，左臂/左腿的 TOP/BOTTOM/INS/OUTS/BACK 是空的，
        // 3D 盒子转起来缺面。左腿源 (0,16)→目标 (16,48)，左臂源 (40,16)→目标 (32,48)，
        // 宽 4 / 高 12 / 深 4（HMCL 的 width/height/depth 全是 64 基准像素值）。
        mirrorLimb(src, out, 0, 16, 16, 48, 4, 12, 4);    // LEFT-LEG
        mirrorLimb(src, out, 40, 16, 32, 48, 4, 12, 4);   // LEFT-ARM
        return out;
    }

    /** 1.3.1：HMCL SkinHelper.x32Tox64 六面镜像的像素版（copyRegion 自带水平镜像）。 */
    private static void mirrorLimb(java.awt.image.BufferedImage src, java.awt.image.BufferedImage out,
                                   int sx, int sy, int toX, int toY, int width, int height, int depth) {
        // TOP
        copyRegion(src, out, sx + depth, sy, width, depth * 2, toX + depth, toY, true);
        // BOTTOM
        copyRegion(src, out, sx + depth + width, sy, width, depth * 2, toX + depth + width, toY, true);
        // INS
        copyRegion(src, out, sx, sy + depth * 2, depth, height, toX + width + depth, toY + depth, true);
        // OUTS
        copyRegion(src, out, sx + width + depth, sy + depth * 2, depth, height, toX, toY + depth, true);
        // FRONT
        copyRegion(src, out, sx + depth, sy + depth * 2, width, height, toX + depth, toY + depth, true);
        // BACK
        copyRegion(src, out, sx + width + depth * 2, sy + depth * 2, width, height,
                toX + width + depth * 2, toY + depth, true);
    }

    /** 1.3.1 QA 钩子：暴露 64×32→64×64 六面镜像结果，供像素断言左臂/左腿各面非空。 */
    public static java.awt.image.BufferedImage x32ToX64ForTests(java.awt.image.BufferedImage src) {
        return x32ToX64(src);
    }

    /** 带边界防御的矩形拷贝；mirrorX=true 时在目标区内水平镜像，越界像素跳过。 */
    private static void copyRegion(java.awt.image.BufferedImage src, java.awt.image.BufferedImage out,
                                   int sx, int sy, int w, int h, int dx, int dy, boolean mirrorX) {
        int maxX = src.getWidth(), maxY = src.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = sx + (mirrorX ? w - x - 1 : x);
                int py = sy + y;
                int tx = dx + x, ty = dy + y;
                if (px < 0 || py < 0 || px >= maxX || py >= maxY
                        || tx < 0 || ty < 0 || tx >= 64 || ty >= 64) {
                    continue;
                }
                out.setRGB(tx, ty, src.getRGB(px, py));
            }
        }
    }

    private static void fillGray(PixelWriter pw, WritableImage out) {
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                pw.setArgb(x, y, 0xFF9AA3AD);
            }
        }
    }
}
