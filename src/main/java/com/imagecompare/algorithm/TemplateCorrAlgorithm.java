package com.imagecompare.algorithm;

import com.imagecompare.ImagePreprocessor.PreprocessResult;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/**
 * 模板匹配算法（归一化互相关 NCC）
 *
 * - 在灰度图上做轻度高斯模糊 → 抑制抗锯齿/微小位移引入的噪声
 * - 用 padded image + smaller template + TM_CCOEFF_NORMED 滑窗：
 *   实际在 ±SEARCH_PAD 个像素窗口内搜索最佳偏移，取最大 NCC，
 *   补偿预处理阶段质心对齐可能残留的微小位移
 */
public class TemplateCorrAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.20;
    private static final int SEARCH_PAD = 3;

    @Override
    public String name() {
        return "模板匹配";
    }

    @Override
    public double compare(PreprocessResult a, PreprocessResult b) {
        Mat blurred1 = new Mat();
        Mat blurred2 = new Mat();
        Imgproc.GaussianBlur(a.grayscale, blurred1, new Size(3, 3), 0.5);
        Imgproc.GaussianBlur(b.grayscale, blurred2, new Size(3, 3), 0.5);

        // 让 b 充当被搜索的"大图"（带边距），a 充当 template。
        // 边距用 BORDER_REPLICATE 而不是常数 255，避免 NCC 因边界亮度突变假高分。
        Mat padded = new Mat();
        Core.copyMakeBorder(blurred2, padded, SEARCH_PAD, SEARCH_PAD, SEARCH_PAD, SEARCH_PAD,
                Core.BORDER_REPLICATE);

        Mat result = new Mat();
        Imgproc.matchTemplate(padded, blurred1, result, Imgproc.TM_CCOEFF_NORMED);

        Core.MinMaxLocResult mm = Core.minMaxLoc(result);
        double ncc = mm.maxVal; // 范围 [-1, 1]

        result.release();
        padded.release();
        blurred1.release();
        blurred2.release();

        return Math.max(0.0, ncc);
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
