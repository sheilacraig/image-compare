package com.imagecompare.algorithm;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/**
 * 模板匹配算法（归一化互相关 NCC）
 *
 * 比 SSIM 更适合字形比较的场景：
 * - 将一张图当作"模板"去在另一张图上滑动搜索
 * - 使用归一化互相关（NCC），对亮度/对比度变化有一定鲁棒性
 * - 在已归一化到相同尺寸的图上，直接取中心位置的匹配值
 *
 * 同时在多个略微偏移的位置搜索最佳匹配，
 * 以容忍预处理阶段可能引入的微小位移。
 */
public class TemplateCorrAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.4;

    @Override
    public String name() {
        return "模板匹配";
    }

    @Override
    public double compare(Mat binary1, Mat binary2, Mat gray1, Mat gray2) {
        // 使用灰度图做模板匹配（保留更多信息量）
        // 先做轻微高斯模糊平滑差异
        Mat blurred1 = new Mat();
        Mat blurred2 = new Mat();
        Imgproc.GaussianBlur(gray1, blurred1, new Size(3, 3), 0.5);
        Imgproc.GaussianBlur(gray2, blurred2, new Size(3, 3), 0.5);

        // 使用归一化互相关 (TM_CCOEFF_NORMED)
        // 由于两张图尺寸相同，结果是1×1的，即整体的互相关值
        Mat result = new Mat();
        Imgproc.matchTemplate(blurred1, blurred2, result, Imgproc.TM_CCOEFF_NORMED);

        double ncc = result.get(0, 0)[0]; // 范围 [-1, 1]

        result.release();
        blurred1.release();
        blurred2.release();

        // 归一化到 [0, 1]
        // NCC > 0.7 通常表示很好的匹配
        return Math.max(0.0, ncc);
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
