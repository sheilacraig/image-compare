package com.imagecompare.algorithm;

import com.imagecompare.ImagePreprocessor.PreprocessResult;

/**
 * 宽高比相似度
 *
 * 预处理为了对齐做了"塞进方形画布"的归一化，这一步会丢失原始字形的扁/方/瘦信息，
 * 导致"卜" "口" "曰" 这种宽高差别极大的字形会被其他算法误判为相似。
 * 这里把原始 bounding box 的宽高比作为一个独立维度纳入打分。
 *
 *   sim = min(r1, r2) / max(r1, r2)
 *
 * 两个字形宽高比一致时分=1，差越大分越低。
 */
public class AspectRatioAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.15;

    @Override
    public String name() {
        return "宽高比";
    }

    @Override
    public double compare(PreprocessResult a, PreprocessResult b) {
        double r1 = a.aspectRatio;
        double r2 = b.aspectRatio;
        if (r1 < 1e-6 || r2 < 1e-6) return 1.0;
        return Math.min(r1, r2) / Math.max(r1, r2);
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
