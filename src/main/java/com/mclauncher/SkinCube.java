package com.mclauncher;

import javafx.scene.shape.Mesh;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;

/**
 * 0.9.8 r5：逐行移植 HMCL {@code org.jackhuang.hmcl.ui.skin.SkinCube}。
 *
 * <p>为什么整套搬过来：自己手写「每个面取哪块贴图」的坐标表时，只要有一处 u/v 起点或行列方向
 * 写错（或靠「逐面 U 镜像」去补左右），侧面与四肢就会出现「头发色长到肩膀上」这类错位 ——
 * 因为逐面镜像只对正面/背面成立，对左右侧面等于把两块不同的贴图区对调。
 * HMCL 这份表的做法是「按盒子展开图算」：一个 (w,h,d) 的盒子展开后横向 (w+d)*2、纵向 h+d，
 * 所有面的起点都由 scaleX/scaleY/startX/startY 推出来，面索引也写死，不存在逐面补偿。
 *
 * <p>贴图坐标一律是 0..1 归一化，所以 64×64、放大到 512×512、以及从 128×64 裁出来的 64×64
 * 都能直接用，不需要再乘缩放。
 */
public class SkinCube extends MeshView {

    /** HMCL 的三角网格：8 个顶点 + 13 个贴图点 + 12 个三角形（正反各一遍）。 */
    public static class Model extends TriangleMesh {

        public Model(float width, float height, float depth, float scaleX, float scaleY,
                     float startX, float startY, boolean isSlim) {
            getPoints().addAll(createPoints(width, height, depth));
            getTexCoords().addAll(createTexCoords(width, height, depth, scaleX, scaleY, startX, startY, isSlim));
            getFaces().addAll(createFaces());
        }

        public static float[] createPoints(float width, float height, float depth) {
            width /= 2F;
            height /= 2F;
            depth /= 2F;
            return new float[]{
                    -width, -height, +depth, // P0
                    +width, -height, +depth, // P1
                    -width, +height, +depth, // P2
                    +width, +height, +depth, // P3
                    -width, -height, -depth, // P4
                    +width, -height, -depth, // P5
                    -width, +height, -depth, // P6
                    +width, +height, -depth  // P7
            };
        }

        public static float[] createTexCoords(float width, float height, float depth, float scaleX, float scaleY,
                                              float startX, float startY, boolean isSlim) {
            // 0.9.8 r6：四个侧面的下边缘 v = bottom_y = scaleY + top_y 精确落在整数 texel
            // （v*512 = 128/256/512），JavaFX 线性采样 s = t*512-0.5 会取相邻两 texel 各半，
            // 混进下一块贴图区第 1 行 → 1px 异色细线。只把 v 最大值内收半个 texel，
            // 采样中心落回本区最后一行。top_y 与 u 左右边界都是合法内容（顶面起始行），
            // 一并内收反而更差：探针 70 张图实测四边全收在俯仰视角残留更多杂色。
            // 只改这一个辅助量，几何 / 面表 / 正反两遍索引 / 13 个贴图点顺序一律不动。
            final float HALF_TEXEL = 0.5f / 512f;
            float x = (width + depth) * 2, y = height + depth,
                    half_width = width / x * scaleX, half_depth = depth / x * scaleX,
                    top_x = depth / x * scaleX + startX, top_y = startY, arm4 = half_width,
                    bottom_x = startX, middle_y = depth / y * scaleY + top_y,
                    bottom_y = scaleY + top_y - HALF_TEXEL;
            return new float[]{
                    top_x, top_y,                                    // T0  ---
                    top_x + half_width, top_y,                        // T1   |
                    top_x + half_width * 2, top_y,                    // T2  ---
                    bottom_x, middle_y,                               // T3  ---
                    bottom_x + half_depth, middle_y,                  // T4   |
                    bottom_x + half_depth + half_width, middle_y,     // T5   |
                    bottom_x + scaleX - arm4, middle_y,               // T6   |
                    bottom_x + scaleX, middle_y,                      // T7  ---
                    bottom_x, bottom_y,                               // T8  ---
                    bottom_x + half_depth, bottom_y,                  // T9   |
                    bottom_x + half_depth + half_width, bottom_y,     // T10  |
                    bottom_x + scaleX - arm4, bottom_y,               // T11  |
                    bottom_x + scaleX, bottom_y                       // T12 ---
            };
        }

        public static int[] createFaces() {
            int[] faces = {
                    // TOP
                    5, 0, 4, 1, 0, 5,    // P5,T0, P4,T1, P0,T5
                    5, 0, 0, 5, 1, 4,    // P5,T0, P0,T5, P1,T4
                    // RIGHT
                    5, 3, 1, 4, 3, 9,    // P5,T3, P1,T4, P3,T9
                    5, 3, 3, 9, 7, 8,    // P5,T3, P3,T9, P7,T8
                    // FRONT
                    1, 4, 0, 5, 2, 10,   // P1,T5, P0,T4, P2,T9
                    1, 4, 2, 10, 3, 9,   // P1,T5, P2,T9, P3,T10
                    // LEFT
                    0, 5, 4, 6, 6, 11,   // P0,T4, P4,T3, P6,T8
                    0, 5, 6, 11, 2, 10,  // P0,T4, P6,T8, P2,T9
                    // BACK
                    4, 6, 5, 7, 7, 12,   // P4,T6, P5,T7, P7,T12
                    4, 6, 7, 12, 6, 11,  // P4,T6, P7,T12, P6,T11
                    // BOTTOM
                    3, 5, 2, 6, 6, 2,    // P3,T2, P2,T1, P6,T5
                    3, 5, 6, 2, 7, 1     // P3,T2, P6,T5, P7,T6
            };

            // HMCL 的 copy 技巧：把整个面表倒序 + 两两交换，得到一份反向绕序的副本接在后面。
            // 这样 JavaFX 无论用哪套绕序判定 front，外侧总有一个三角形活着，永不「缺面 / 透视」。
            int[] copy = faces.clone();
            for (int i = 0, mid = copy.length >> 1, j = copy.length - 1; i < mid; i++, j--) {
                int tmp = copy[i];
                copy[i] = copy[j];
                copy[j] = tmp;
            }
            for (int i = 0; i < copy.length; i += 2) {
                int tmp = copy[i];
                copy[i] = copy[i + 1];
                copy[i + 1] = tmp;
            }

            int[] result = new int[faces.length + copy.length];
            System.arraycopy(faces, 0, result, 0, faces.length);
            System.arraycopy(copy, 0, result, faces.length, copy.length);
            return result;
        }
    }

    private final double width, height, depth;
    private Mesh model;

    public SkinCube(double width, double height, double depth, float scaleX, float scaleY,
                    float startX, float startY, double enlarge, boolean isSlim) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        setMesh(model = new Model((float) (width + enlarge), (float) (height + enlarge), (float) (depth + enlarge),
                scaleX, scaleY, startX, startY, isSlim));
        // 0.9.8 r5：双面渲染。本机 D3D9Ex 判 front 的绕序与 HMCL 假设不一定一致，
        // 只靠「正反两遍发射」在某些驱动上仍会把近侧的面全剔掉 —— 表现就是俯仰时
        // 「看见身体内部」。CullFace.NONE 连同深度测试一起，保证盒子永远是实心的。
        setCullFace(javafx.scene.shape.CullFace.NONE);
        // 深度测试显式打开：双面都画之后，靠深度缓冲把盒子的远侧（内壁）挡住，
        // 盒子因此永远是实心的 —— 上下俯仰都看不见内部。
        setDepthTest(javafx.scene.DepthTest.ENABLE);
        System.err.println("[skin3d] 双面渲染已开启（CullFace.NONE + DepthTest.ENABLE）");
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public double getDepth() {
        return depth;
    }

    public Mesh getModel() {
        return model;
    }
}
