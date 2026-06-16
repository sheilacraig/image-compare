package com.imagecompare.algorithm;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

/**
 * 网格直方图算法
 *
 * 将图像分成 N×N 的网格，计算每个格子中前景像素的密度（比例），
 * 然后比较两张图的密度向量的相似度。
 *
 * 这种方法对笔画粗细变化有很好的容忍度：
 * - 粗笔画和细笔画在同一个网格区域内的密度值虽然不同，但差异可控
 * - 比逐像素比较（IoU）柔和得多
 * - 但不同字形的密度分布模式会有显著差异
 */
public class GridHistogramAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.4;

    /** 网格大小（N×N），8×8 对 128×128 的图像是每格 16×16 像素 */
    private static final int GRID_SIZE = 8;

    @Override
    public String name() {
        return "网格直方图";
    }

    @Override
    public double compare(Mat binary1, Mat binary2, Mat gray1, Mat gray2) {
        double[] hist1 = computeGridHistogram(binary1);
        double[] hist2 = computeGridHistogram(binary2);

        // 使用余弦相似度比较两个直方图向量
        return cosineSimilarity(hist1, hist2);
    }

    /**
     * 计算网格密度直方图
     */
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

    /**
     * 余弦相似度：范围 [-1, 1]，1 表示完全一致
     */
    private double cosineSimilarity(double[] a, double[] b) {
        double dotProduct = 0, normA = 0, normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0) {
            return (normA == 0 && normB == 0) ? 1.0 : 0.0;
        }

        return dotProduct / denominator;
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
