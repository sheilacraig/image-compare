package com.imagecompare.algorithm;

import com.imagecompare.ImagePreprocessor.PreprocessResult;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 轮廓匹配算法（多组件双向版）
 *
 * 改进点：
 * 1. 不再只取最大轮廓 —— 汉字常含多个外部组件（明=日+月、林=两个木）。
 *    取前 N 个最大轮廓，对每个去对面找"最像的"伙伴，按面积加权平均得到方向分。
 * 2. 双向对称：A→B 和 B→A 都做，取平均，避免"小被大包含"假高分。
 * 3. 轮廓数量差异作惩罚因子。
 * 4. 距离 → 分数的映射用更严格的 1/(1+2d)，避免分布堆在 [0.7, 1.0]。
 */
public class ContourMatchAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.15;

    /** 最多考虑前 N 个最大轮廓 */
    private static final int TOP_N = 5;

    @Override
    public String name() {
        return "轮廓匹配";
    }

    @Override
    public double compare(PreprocessResult a, PreprocessResult b) {
        List<MatOfPoint> c1 = findContoursSorted(a.binary);
        List<MatOfPoint> c2 = findContoursSorted(b.binary);

        try {
            if (c1.isEmpty() && c2.isEmpty()) return 1.0;
            if (c1.isEmpty() || c2.isEmpty()) return 0.0;

            List<MatOfPoint> top1 = c1.subList(0, Math.min(TOP_N, c1.size()));
            List<MatOfPoint> top2 = c2.subList(0, Math.min(TOP_N, c2.size()));

            double dir1 = directionalShapeScore(top1, top2);
            double dir2 = directionalShapeScore(top2, top1);
            double shapeScore = (dir1 + dir2) / 2.0;

            // 轮廓数量差异惩罚
            int n1 = c1.size(), n2 = c2.size();
            int diff = Math.abs(n1 - n2);
            int max = Math.max(n1, n2);
            double countPenalty = 1.0 - (double) diff / (max + 1.0);

            // 总面积比
            double totalArea1 = totalArea(c1);
            double totalArea2 = totalArea(c2);
            double areaRatio = Math.min(totalArea1, totalArea2)
                    / Math.max(totalArea1, totalArea2 + 1e-9);

            // 几何混合：形状 65% + 面积比 20% + 数量惩罚 15%
            return shapeScore * 0.65 + areaRatio * 0.20 + countPenalty * 0.15;
        } finally {
            releaseContours(c1);
            releaseContours(c2);
        }
    }

    /**
     * 对 src 中每个轮廓，去 dst 里找最匹配的，按面积加权平均匹配度
     */
    private double directionalShapeScore(List<MatOfPoint> src, List<MatOfPoint> dst) {
        double scoreSum = 0;
        double weightSum = 0;
        for (MatOfPoint cs : src) {
            double areaS = Imgproc.contourArea(cs);
            if (areaS < 1e-6) continue;

            double bestScore = 0;
            for (MatOfPoint cd : dst) {
                double d1 = Imgproc.matchShapes(cs, cd, Imgproc.CONTOURS_MATCH_I1, 0);
                double d2 = Imgproc.matchShapes(cs, cd, Imgproc.CONTOURS_MATCH_I2, 0);
                // 用 I1 + I2 平均；不用 I3（仅取最大差，噪声大）
                double d = (d1 + d2) / 2.0;
                double s = 1.0 / (1.0 + 2.0 * d);
                if (s > bestScore) bestScore = s;
            }
            scoreSum += bestScore * areaS;
            weightSum += areaS;
        }
        return weightSum > 0 ? scoreSum / weightSum : 0.0;
    }

    private List<MatOfPoint> findContoursSorted(Mat binary) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(binary.clone(), contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();

        contours.sort(Comparator.comparingDouble(c -> -Imgproc.contourArea(c)));

        // 过滤明显噪点：面积 < 最大轮廓 1% 的丢掉
        if (!contours.isEmpty()) {
            double maxArea = Imgproc.contourArea(contours.get(0));
            double threshold = Math.max(maxArea * 0.01, 4.0);
            contours.removeIf(c -> Imgproc.contourArea(c) < threshold);
        }
        return contours;
    }

    private double totalArea(List<MatOfPoint> contours) {
        double total = 0;
        for (MatOfPoint c : contours) total += Imgproc.contourArea(c);
        return total + 1e-9;
    }

    private void releaseContours(List<MatOfPoint> contours) {
        for (MatOfPoint c : contours) c.release();
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
