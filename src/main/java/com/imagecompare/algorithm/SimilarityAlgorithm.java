package com.imagecompare.algorithm;

import com.imagecompare.ImagePreprocessor.PreprocessResult;

/**
 * 相似度算法接口
 *
 * 算法直接拿到 {@link PreprocessResult}，可按需访问 binary / grayscale / 原始宽高比等信息。
 */
public interface SimilarityAlgorithm {

    String name();

    /**
     * 比较两张预处理结果的相似度
     * @return 相似度，范围 0.0 ~ 1.0
     */
    double compare(PreprocessResult a, PreprocessResult b);

    /** 在几何均值融合中的权重（相对权重，归一化后使用） */
    double weight();
}
