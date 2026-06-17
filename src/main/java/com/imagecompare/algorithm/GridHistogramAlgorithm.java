package com.imagecompare.algorithm;

import com.imagecompare.ImagePreprocessor.PreprocessResult;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

/**
 * 网格直方图算法
 *
 * 把图像分成 N×N 网格，统计每格前景密度，构成 64 维向量。
 *
 * 旧版本用余弦相似度（方向化、量级不敏感、虚高），新版本用 **直方图交集 (Jaccard-style)**：
 *   sim = Σ min(a_i, b_i) / Σ max(a_i, b_i)
 *
 * 直方图交集对密度量级差异敏感得多，能有效拉开"分布相似但密度不同"的字形差距。
 */
public class GridHistogramAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.15;

    /** 网格大小（N×N），8×8 对 128×128 图像每格 16×16 像素 */
    private static final int GRID_SIZE = 8;

    @Override
    public String name() {
        return "网格直方图";
    }

    @Override
    public double compare(PreprocessResult a, PreprocessResult b) {
        double[] h1 = computeGridHistogram(a.binary);
        double[] h2 = computeGridHistogram(b.binary);

        double interSum = 0, unionSum = 0;
        for (int i = 0; i < h1.length; i++) {
            interSum += Math.min(h1[i], h2[i]);
            unionSum += Math.max(h1[i], h2[i]);
        }
        if (unionSum < 1e-9) {
            return 1.0; // 两张都空 → 视为完全一致
        }
        return interSum / unionSum;
    }

    private double[] computeGridHistogram(Mat binary) {
        int rows = binary.rows();
        int cols = binary.cols();
        int cellH = rows / GRID_SIZE;
        int cellW = cols / GRID_SIZE;

        double[] histogram = new double[GRID_SIZE * GRID_SIZE];

        for (int gy = 0; gy < GRID_SIZE; gy++) {
            for (int gx = 0; gx < GRID_SIZE; gx++) {
                int x = gx * cellW;
                int y = gy * cellH;
                int w = (gx == GRID_SIZE - 1) ? (cols - x) : cellW;
                int h = (gy == GRID_SIZE - 1) ? (rows - y) : cellH;

                Mat cell = binary.submat(new Rect(x, y, w, h));
                int foreground = Core.countNonZero(cell);
                int totalPixels = w * h;

                histogram[gy * GRID_SIZE + gx] = (double) foreground / totalPixels;
            }
        }
        return histogram;
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
