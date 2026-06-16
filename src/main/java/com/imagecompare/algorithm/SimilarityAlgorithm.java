package com.imagecompare.algorithm;

import org.opencv.core.Mat;

/**
 * 相似度算法接口
 */
public interface SimilarityAlgorithm {

    String name();

    /**
     * 比较两张预处理后的图像的相似度
     *
     * @param binary1 二值化图像1（前景=255, 背景=0）
     * @param binary2 二值化图像2
     * @param gray1   灰度图像1（白底黑字）
     * @param gray2   灰度图像2
     * @return 相似度，范围 0.0 ~ 1.0
     */
    double compare(Mat binary1, Mat binary2, Mat gray1, Mat gray2);

    double weight();
}
