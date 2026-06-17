package com.imagecompare.algorithm;

import com.imagecompare.ImagePreprocessor.PreprocessResult;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/**
 * 梯度方向直方图 (HoG) 算法
 *
 * 对汉字最具判别力的特征是 "笔画方向分布"：横/竖/撇/捺在哪些区域。
 * 本算法：
 *   1. 在 binary 图上计算 Sobel 梯度 gx, gy
 *   2. 把图像分成 8×8 个 cell（每 cell 16×16 像素）
 *   3. 每个 cell 内统计 9 个方向 bin（0~180°，三角插值分摊到相邻 bin）
 *   4. 每个 cell 的直方图做 L2 归一化（降低密度差影响，只看方向比例）
 *   5. 把所有 cell 特征拼成 8×8×9 = 576 维向量
 *   6. 用余弦相似度比较两个特征向量
 *
 * 因为每 cell 单独归一化了，这里的余弦相似度不会像 GridHistogram 那样虚高 ——
 * 方向不同的字（如"未"和"末"、"已""己""巳"）能被有效拉开。
 */
public class HogAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.20;

    private static final int CELL_SIZE = 16;     // 128 / 16 = 8 cells per side
    private static final int GRID = 8;
    private static final int BINS = 9;
    private static final double PI = Math.PI;

    @Override
    public String name() {
        return "梯度方向";
    }

    @Override
    public double compare(PreprocessResult a, PreprocessResult b) {
        double[] f1 = computeHog(a.binary);
        double[] f2 = computeHog(b.binary);

        double dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < f1.length; i++) {
            dot += f1[i] * f2[i];
            n1 += f1[i] * f1[i];
            n2 += f2[i] * f2[i];
        }
        double denom = Math.sqrt(n1) * Math.sqrt(n2);
        if (denom < 1e-9) {
            return (n1 < 1e-9 && n2 < 1e-9) ? 1.0 : 0.0;
        }
        return Math.max(0.0, dot / denom);
    }

    private double[] computeHog(Mat binary) {
        Mat gx = new Mat(), gy = new Mat();
        Imgproc.Sobel(binary, gx, CvType.CV_32F, 1, 0, 3);
        Imgproc.Sobel(binary, gy, CvType.CV_32F, 0, 1, 3);

        int rows = binary.rows(), cols = binary.cols();
        int total = rows * cols;
        float[] gxBuf = new float[total];
        float[] gyBuf = new float[total];
        gx.get(0, 0, gxBuf);
        gy.get(0, 0, gyBuf);
        gx.release();
        gy.release();

        double[] features = new double[GRID * GRID * BINS];

        for (int y = 0; y < rows; y++) {
            int cy = Math.min(GRID - 1, y / CELL_SIZE);
            int rowBase = y * cols;
            for (int x = 0; x < cols; x++) {
                int cx = Math.min(GRID - 1, x / CELL_SIZE);
                int idx = rowBase + x;
                float dx = gxBuf[idx];
                float dy = gyBuf[idx];
                double mag = Math.sqrt(dx * dx + dy * dy);
                if (mag < 1e-3) continue;

                // 无符号方向 [0, π)
                double angle = Math.atan2(dy, dx);
                if (angle < 0) angle += PI;
                if (angle >= PI) angle -= PI;

                // 三角插值到相邻 bin
                double binF = angle / PI * BINS;
                int b0 = ((int) Math.floor(binF)) % BINS;
                int b1 = (b0 + 1) % BINS;
                double frac = binF - Math.floor(binF);

                int base = (cy * GRID + cx) * BINS;
                features[base + b0] += mag * (1 - frac);
                features[base + b1] += mag * frac;
            }
        }

        // 每个 cell L2 归一化
        for (int c = 0; c < GRID * GRID; c++) {
            int base = c * BINS;
            double norm = 0;
            for (int b = 0; b < BINS; b++) norm += features[base + b] * features[base + b];
            norm = Math.sqrt(norm);
            if (norm > 1e-6) {
                for (int b = 0; b < BINS; b++) features[base + b] /= norm;
            }
        }
        return features;
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
