package com.example.server.utils;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 在画面里找出投影幕布 / 白板 / 投屏所在的四边形区域，供抽帧时把这一块转成正对视角。
 *
 * 做法照 docs/前景过滤_投影区域锁定与透视矫正.md：
 * 第三节（手机文档扫描）：灰度 → 高斯模糊 → Canny → 膨胀 → 取最大连通域 → 凸包 → 四边形逼近；
 * 第 4.2 节（跨帧投票）：多帧各检一次，对角点取中值，剔除离群帧后再取一次中值；
 * 第二节（正对判定）：四边形几乎就是整帧说明是录屏，不做矫正。
 *
 * 只用 java.awt.image 与 java.util，不引入 OpenCV；透视重采样交给 FFmpeg 的 perspective 滤镜。
 */
public final class ProjectionScreenLocator {

    /** 探测帧：每 10 秒取一帧共 30 帧（文档 4.2 节第 1 步）。 */
    public static final int PROBE_FRAME_COUNT = 30;
    public static final long PROBE_INTERVAL_MS = 10_000L;

    /** 四边形面积至少占画面 20%，否则当成噪声（文档第三节 cv2.contourArea(approx) > 0.2 * w * h）。 */
    private static final double MIN_QUAD_AREA_RATIO = 0.20;
    /** 判定「已经是正对的录屏画面」的两个条件（文档第二节 is_already_frontal）。 */
    private static final double FRONTAL_AREA_RATIO = 0.90;
    private static final double FRONTAL_CORNER_GAP_RATIO = 0.02;
    /** 角点与中值距离超过画面短边 5% 判定为离群帧（文档 4.2 节第 4 步）。 */
    private static final double OUTLIER_GAP_RATIO = 0.05;
    /** Canny 双阈值，高阈值是低阈值的 3 倍（文档第三节 cv2.Canny(blur, 15, 45)）。 */
    private static final double CANNY_LOW = 15.0;
    private static final double CANNY_HIGH = 45.0;
    /** 多边形逼近精度，取周长的 5%（文档第三节 cv2.approxPolyDP(c, 0.05 * peri, True)）。 */
    private static final double APPROX_EPSILON_RATIO = 0.05;
    /** 逼近精度每放大一次没得到四边形的倍数，以及最多放大几次。 */
    private static final double APPROX_EPSILON_GROWTH = 1.4;
    private static final int APPROX_ATTEMPTS = 6;
    /** 检测前把帧缩放到这个宽度，算出角点后再按比例放大回原尺寸。探测帧按这个宽度抽取，可以省一次缩放。 */
    public static final int WORK_WIDTH = 480;
    /** 膨胀半径，让主要形状连成一片（文档 4.4 节第 b 步）。 */
    private static final int DILATE_RADIUS = 2;
    /** 最多检查面积最大的前几个连通域（文档第三节取前 3 个）。 */
    private static final int COMPONENT_CANDIDATES = 3;

    private ProjectionScreenLocator() {
    }

    /**
     * 锁定结果。quad 的四个点顺序固定为：左上、右上、右下、左下（文档第三节 order_points 的顺序）。
     * width / height 是这四个角点所在的坐标系尺寸，也就是传入探测帧的分辨率。
     */
    public record ScreenRegion(boolean detected, boolean alreadyFrontal, Vec[] quad, int width, int height) {

        private static final ScreenRegion NONE = new ScreenRegion(false, false, null, 0, 0);

        public static ScreenRegion none() {
            return NONE;
        }

        /** 需要矫正：找到了四边形，且它不是已经正对的整帧画面。 */
        public boolean needsCorrection() {
            return detected && !alreadyFrontal && quad != null && quad.length == 4;
        }

        /**
         * 探测帧通常比原视频小，抽帧时 FFmpeg 要的是原视频分辨率下的坐标，用这个方法换算过去。
         * 传进来的宽高与原宽高一致时返回自身。
         */
        public ScreenRegion scaledTo(int targetWidth, int targetHeight) {
            if (quad == null || width == 0 || height == 0
                    || (targetWidth == width && targetHeight == height)) {
                return this;
            }
            double scaleX = (double) targetWidth / width;
            double scaleY = (double) targetHeight / height;
            Vec[] scaled = new Vec[quad.length];
            for (int i = 0; i < quad.length; i++) {
                scaled[i] = new Vec(quad[i].x() * scaleX, quad[i].y() * scaleY);
            }
            return new ScreenRegion(detected, alreadyFrontal, scaled, targetWidth, targetHeight);
        }

        /**
         * 生成 FFmpeg perspective 滤镜的参数。
         * FFmpeg 要的顺序是 左上、右上、左下、右下，和 order_points 的 左上、右上、右下、左下 不一样，这里换序。
         */
        public String perspectiveFilter() {
            if (!needsCorrection()) {
                return null;
            }
            return String.format(Locale.ROOT,
                    "perspective=%.2f:%.2f:%.2f:%.2f:%.2f:%.2f:%.2f:%.2f",
                    quad[0].x(), quad[0].y(),
                    quad[1].x(), quad[1].y(),
                    quad[3].x(), quad[3].y(),
                    quad[2].x(), quad[2].y());
        }
    }

    public record Vec(double x, double y) {

        public double distance(Vec other) {
            return Math.hypot(x - other.x(), y - other.y());
        }
    }

    /**
     * 对多帧各跑一次四边形检测，再跨帧投票取中值。
     * 返回 detected=false 表示没找到（录屏以外的场景下也可能找不到，此时调用方应走原链路不做矫正）。
     */
    public static ScreenRegion locate(List<BufferedImage> probes) {
        if (probes == null || probes.isEmpty()) {
            return ScreenRegion.none();
        }
        int width = 0;
        int height = 0;
        List<Vec[]> candidates = new ArrayList<>();
        for (BufferedImage probe : probes) {
            if (probe == null) {
                continue;
            }
            width = Math.max(width, probe.getWidth());
            height = Math.max(height, probe.getHeight());
            Vec[] quad = detectQuad(probe);
            if (quad != null) {
                candidates.add(quad);
            }
        }
        if (candidates.isEmpty() || width == 0 || height == 0) {
            return ScreenRegion.none();
        }
        Vec[] quad = voteQuad(candidates, width, height);
        return new ScreenRegion(true, isFrontal(quad, width, height), quad, width, height);
    }

    /** 单帧检测：缩放 → 灰度 → 模糊 → Canny → 膨胀 → 最大连通域 → 凸包 → 四边形。 */
    private static Vec[] detectQuad(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 16 || height < 16) {
            return null;
        }
        int workWidth = Math.min(WORK_WIDTH, width);
        int workHeight = Math.max(1, (int) Math.round((double) height * workWidth / width));
        BufferedImage work = scale(image, workWidth, workHeight);

        double[] gray = toGray(work);
        double[] blurred = gaussianBlur(gray, workWidth, workHeight);
        boolean[] edges = canny(blurred, workWidth, workHeight);
        boolean[] thickened = dilate(edges, workWidth, workHeight, DILATE_RADIUS);

        double scaleX = (double) width / workWidth;
        double scaleY = (double) height / workHeight;
        for (int[] component : largestComponents(thickened, workWidth, workHeight, COMPONENT_CANDIDATES)) {
            List<Vec> hull = convexHull(boundaryPoints(component, workWidth, workHeight));
            if (hull.size() < 4) {
                continue;
            }
            Vec[] quad = approximateQuad(hull);
            if (quad == null) {
                continue;
            }
            if (polygonArea(quad) < MIN_QUAD_AREA_RATIO * workWidth * workHeight) {
                continue;
            }
            return scaleQuad(orderPoints(quad), scaleX, scaleY);
        }
        return null;
    }

    /** 跨帧投票：先取中值，剔除角点偏离中值超过短边 5% 的帧，再取一次中值（文档 4.2 节）。 */
    private static Vec[] voteQuad(List<Vec[]> candidates, int width, int height) {
        double threshold = OUTLIER_GAP_RATIO * Math.min(width, height);
        List<Vec[]> kept = new ArrayList<>(candidates);
        Vec[] median = medianQuad(kept);
        for (int round = 0; round < 2; round++) {
            List<Vec[]> inliers = new ArrayList<>();
            for (Vec[] quad : kept) {
                if (maxCornerGap(quad, median) <= threshold) {
                    inliers.add(quad);
                }
            }
            // 剔除后至少要留下三分之一，否则宁可不剔
            if (inliers.isEmpty() || inliers.size() < kept.size() / 3) {
                break;
            }
            kept = inliers;
            median = medianQuad(kept);
        }
        return median;
    }

    private static Vec[] medianQuad(List<Vec[]> quads) {
        Vec[] median = new Vec[4];
        for (int corner = 0; corner < 4; corner++) {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            for (Vec[] quad : quads) {
                xs.add(quad[corner].x());
                ys.add(quad[corner].y());
            }
            xs.sort(Double::compareTo);
            ys.sort(Double::compareTo);
            median[corner] = new Vec(middle(xs), middle(ys));
        }
        return median;
    }

    private static double middle(List<Double> sorted) {
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }

    private static double maxCornerGap(Vec[] quad, Vec[] reference) {
        double gap = 0;
        for (int i = 0; i < 4; i++) {
            gap = Math.max(gap, quad[i].distance(reference[i]));
        }
        return gap;
    }

    /** 已经正对：面积占画面 90% 以上，且四个角点都离画面四角不到短边的 2%（文档第二节）。 */
    private static boolean isFrontal(Vec[] quad, int width, int height) {
        if (polygonArea(quad) < FRONTAL_AREA_RATIO * width * height) {
            return false;
        }
        Vec[] frameCorners = {
                new Vec(0, 0),
                new Vec(width, 0),
                new Vec(width, height),
                new Vec(0, height)
        };
        double gap = 0;
        for (int i = 0; i < 4; i++) {
            gap = Math.max(gap, quad[i].distance(frameCorners[i]));
        }
        return gap < FRONTAL_CORNER_GAP_RATIO * Math.min(width, height);
    }

    /** 把角点按 左上、右上、右下、左下 排序，顺序错了透视变换会把图像拧变形（文档第三节 order_points）。 */
    private static Vec[] orderPoints(Vec[] quad) {
        Vec[] ordered = new Vec[4];
        int minSum = 0;
        int maxSum = 0;
        int minDiff = 0;
        int maxDiff = 0;
        for (int i = 1; i < 4; i++) {
            if (quad[i].x() + quad[i].y() < quad[minSum].x() + quad[minSum].y()) {
                minSum = i;
            }
            if (quad[i].x() + quad[i].y() > quad[maxSum].x() + quad[maxSum].y()) {
                maxSum = i;
            }
            if (quad[i].y() - quad[i].x() < quad[minDiff].y() - quad[minDiff].x()) {
                minDiff = i;
            }
            if (quad[i].y() - quad[i].x() > quad[maxDiff].y() - quad[maxDiff].x()) {
                maxDiff = i;
            }
        }
        ordered[0] = quad[minSum];
        ordered[1] = quad[minDiff];
        ordered[2] = quad[maxSum];
        ordered[3] = quad[maxDiff];
        return ordered;
    }

    /** 从 5% 周长起步逼近到 4 个顶点；得不到四边形就逐步放宽精度（文档第三节的 0.05 * peri）。 */
    private static Vec[] approximateQuad(List<Vec> hull) {
        double epsilon = APPROX_EPSILON_RATIO * perimeter(hull);
        for (int attempt = 0; attempt < APPROX_ATTEMPTS; attempt++) {
            List<Vec> approx = approximateClosedPolygon(hull, epsilon);
            if (approx.size() == 4) {
                return approx.toArray(new Vec[0]);
            }
            epsilon *= APPROX_EPSILON_GROWTH;
        }
        return null;
    }

    /**
     * 环要切成两段分别逼近，否则固定首尾会让多边形少一条边。
     *
     * 切点取凸包上距离最远的一对点（直径），不能取 x 最小的点：膨胀和缩放会让边缘出现锯齿，
     * x 最小的点往往落在某条边的中间而不是角上，拿它当固定端点会把真正的角点挤掉。
     */
    private static List<Vec> approximateClosedPolygon(List<Vec> hull, double epsilon) {
        int start = 0;
        int end = 0;
        double maxSpan = -1;
        for (int i = 0; i < hull.size(); i++) {
            for (int j = i + 1; j < hull.size(); j++) {
                double distance = hull.get(i).distance(hull.get(j));
                if (distance > maxSpan) {
                    maxSpan = distance;
                    start = i;
                    end = j;
                }
            }
        }
        List<Vec> head = new ArrayList<>();
        for (int i = start; ; i = (i + 1) % hull.size()) {
            head.add(hull.get(i));
            if (i == end) {
                break;
            }
        }
        List<Vec> tail = new ArrayList<>();
        for (int i = end; ; i = (i + 1) % hull.size()) {
            tail.add(hull.get(i));
            if (i == start) {
                break;
            }
        }
        List<Vec> merged = new ArrayList<>(simplify(head, epsilon));
        List<Vec> simplifiedTail = simplify(tail, epsilon);
        for (int i = 1; i < simplifiedTail.size(); i++) {
            merged.add(simplifiedTail.get(i));
        }
        // tail 的最后一个点就是 start，和开头重复，去掉
        if (merged.size() > 1 && merged.get(0).equals(merged.get(merged.size() - 1))) {
            merged.remove(merged.size() - 1);
        }
        return merged;
    }

    /** Douglas-Peucker 折线简化，用显式栈避免深递归。 */
    private static List<Vec> simplify(List<Vec> points, double epsilon) {
        int size = points.size();
        if (size < 3) {
            return new ArrayList<>(points);
        }
        boolean[] keep = new boolean[size];
        keep[0] = true;
        keep[size - 1] = true;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, size - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            double maxDistance = 0;
            int index = -1;
            for (int i = range[0] + 1; i < range[1]; i++) {
                double distance = distanceToSegment(points.get(i), points.get(range[0]), points.get(range[1]));
                if (distance > maxDistance) {
                    maxDistance = distance;
                    index = i;
                }
            }
            if (index > 0 && maxDistance > epsilon) {
                keep[index] = true;
                stack.push(new int[]{range[0], index});
                stack.push(new int[]{index, range[1]});
            }
        }
        List<Vec> simplified = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (keep[i]) {
                simplified.add(points.get(i));
            }
        }
        return simplified;
    }

    private static double distanceToSegment(Vec point, Vec start, Vec end) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return point.distance(start);
        }
        double t = ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return point.distance(new Vec(start.x() + t * dx, start.y() + t * dy));
    }

    private static double perimeter(List<Vec> polygon) {
        double total = 0;
        for (int i = 0; i < polygon.size(); i++) {
            total += polygon.get(i).distance(polygon.get((i + 1) % polygon.size()));
        }
        return total;
    }

    /** 鞋带公式，顶点按绕行顺序排列。 */
    private static double polygonArea(Vec[] polygon) {
        double sum = 0;
        for (int i = 0; i < polygon.length; i++) {
            Vec current = polygon[i];
            Vec next = polygon[(i + 1) % polygon.length];
            sum += current.x() * next.y() - next.x() * current.y();
        }
        return Math.abs(sum) / 2.0;
    }

    /** Andrew monotone chain，返回逆时针凸包，首尾不重复。 */
    private static List<Vec> convexHull(List<Vec> points) {
        if (points.size() < 4) {
            return new ArrayList<>(points);
        }
        List<Vec> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(Vec::x).thenComparingDouble(Vec::y));
        List<Vec> lower = new ArrayList<>();
        for (Vec point : sorted) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), point) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(point);
        }
        List<Vec> upper = new ArrayList<>();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            Vec point = sorted.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), point) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(point);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    private static double cross(Vec origin, Vec from, Vec to) {
        return (from.x() - origin.x()) * (to.y() - origin.y())
                - (from.y() - origin.y()) * (to.x() - origin.x());
    }

    /** 取连通域的边界像素，凸包只需要外轮廓，用边界点比用全部像素快得多。 */
    private static List<Vec> boundaryPoints(int[] component, int width, int height) {
        boolean[] member = new boolean[width * height];
        for (int pixel : component) {
            member[pixel] = true;
        }
        List<Vec> boundary = new ArrayList<>();
        for (int pixel : component) {
            int x = pixel % width;
            int y = pixel / width;
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1
                    || !member[pixel - 1] || !member[pixel + 1]
                    || !member[pixel - width] || !member[pixel + width]) {
                boundary.add(new Vec(x, y));
            }
        }
        return boundary;
    }

    /** 8 邻域连通域标记，返回面积最大的前几个。 */
    private static List<int[]> largestComponents(boolean[] mask, int width, int height, int limit) {
        boolean[] visited = new boolean[mask.length];
        List<int[]> components = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int seed = 0; seed < mask.length; seed++) {
            if (!mask[seed] || visited[seed]) {
                continue;
            }
            List<Integer> pixels = new ArrayList<>();
            visited[seed] = true;
            stack.push(seed);
            while (!stack.isEmpty()) {
                int current = stack.pop();
                pixels.add(current);
                int x = current % width;
                int y = current / width;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) {
                            continue;
                        }
                        int neighbor = ny * width + nx;
                        if (mask[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true;
                            stack.push(neighbor);
                        }
                    }
                }
            }
            int[] pixelsArray = new int[pixels.size()];
            for (int i = 0; i < pixels.size(); i++) {
                pixelsArray[i] = pixels.get(i);
            }
            components.add(pixelsArray);
        }
        components.sort(Comparator.comparingInt((int[] component) -> component.length).reversed());
        return components.subList(0, Math.min(limit, components.size()));
    }

    private static boolean[] dilate(boolean[] source, int width, int height, int radius) {
        boolean[] result = new boolean[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!source[y * width + x]) {
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        result[ny * width + nx] = true;
                    }
                }
            }
        }
        return result;
    }

    /** Sobel 求梯度 → 非极大抑制 → 双阈值 → 只保留与强边缘连通的弱边缘。 */
    private static boolean[] canny(double[] gray, int width, int height) {
        double[] magnitude = new double[gray.length];
        double[] direction = new double[gray.length];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                double gx = -gray[i - width - 1] - 2 * gray[i - 1] - gray[i + width - 1]
                        + gray[i - width + 1] + 2 * gray[i + 1] + gray[i + width + 1];
                double gy = -gray[i - width - 1] - 2 * gray[i - width] - gray[i - width + 1]
                        + gray[i + width - 1] + 2 * gray[i + width] + gray[i + width + 1];
                magnitude[i] = Math.hypot(gx, gy);
                direction[i] = Math.atan2(gy, gx);
            }
        }

        double[] suppressed = new double[gray.length];
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                double angle = direction[i];
                if (angle < 0) {
                    angle += Math.PI;
                }
                int stepX;
                int stepY;
                if (angle < Math.PI / 8 || angle >= 7 * Math.PI / 8) {
                    stepX = 1;
                    stepY = 0;
                } else if (angle < 3 * Math.PI / 8) {
                    stepX = 1;
                    stepY = 1;
                } else if (angle < 5 * Math.PI / 8) {
                    stepX = 0;
                    stepY = 1;
                } else {
                    stepX = -1;
                    stepY = 1;
                }
                double forward = magnitude[(y + stepY) * width + (x + stepX)];
                double backward = magnitude[(y - stepY) * width + (x - stepX)];
                if (magnitude[i] >= forward && magnitude[i] >= backward) {
                    suppressed[i] = magnitude[i];
                }
            }
        }

        boolean[] strong = new boolean[gray.length];
        boolean[] weak = new boolean[gray.length];
        for (int i = 0; i < suppressed.length; i++) {
            if (suppressed[i] >= CANNY_HIGH) {
                strong[i] = true;
            } else if (suppressed[i] >= CANNY_LOW) {
                weak[i] = true;
            }
        }

        boolean[] edges = new boolean[gray.length];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int seed = 0; seed < strong.length; seed++) {
            if (!strong[seed] || edges[seed]) {
                continue;
            }
            edges[seed] = true;
            stack.push(seed);
            while (!stack.isEmpty()) {
                int current = stack.pop();
                int x = current % width;
                int y = current / width;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        int neighbor = ny * width + nx;
                        if (!edges[neighbor] && (strong[neighbor] || weak[neighbor])) {
                            edges[neighbor] = true;
                            stack.push(neighbor);
                        }
                    }
                }
            }
        }
        return edges;
    }

    /** 5x5 高斯核，整数权重除以 256。 */
    private static double[] gaussianBlur(double[] source, int width, int height) {
        int[] kernel = {
                1, 4, 6, 4, 1,
                4, 16, 24, 16, 4,
                6, 24, 36, 24, 6,
                4, 16, 24, 16, 4,
                1, 4, 6, 4, 1
        };
        double[] result = new double[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0;
                int weight = 0;
                for (int ky = -2; ky <= 2; ky++) {
                    int ny = y + ky;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int kx = -2; kx <= 2; kx++) {
                        int nx = x + kx;
                        if (nx < 0 || nx >= width) {
                            continue;
                        }
                        int kernelValue = kernel[(ky + 2) * 5 + (kx + 2)];
                        sum += source[ny * width + nx] * kernelValue;
                        weight += kernelValue;
                    }
                }
                result[y * width + x] = sum / weight;
            }
        }
        return result;
    }

    private static double[] toGray(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
        double[] gray = new double[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int color = rgb[i];
            gray[i] = 0.299 * ((color >> 16) & 0xFF)
                    + 0.587 * ((color >> 8) & 0xFF)
                    + 0.114 * (color & 0xFF);
        }
        return gray;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static Vec[] scaleQuad(Vec[] quad, double scaleX, double scaleY) {
        Vec[] scaled = new Vec[quad.length];
        for (int i = 0; i < quad.length; i++) {
            scaled[i] = new Vec(quad[i].x() * scaleX, quad[i].y() * scaleY);
        }
        return scaled;
    }
}
