package com.imagecompare.algorithm;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/**
 * 距离容忍匹配算法（替代传统IoU）
 *
 * 传统 IoU 要求像素精确重叠，对笔画粗细差异极其敏感。
 * 本算法使用距离变换（Distance Transform）：
 * - 对每个前景像素，检查它距离另一张图最近的前景像素有多远
 * - 如果在容忍距离内（如3像素），就算"匹配成功"
 * - 最终计算双向匹配率的平均值
 *
 * 这样即使笔画粗一点或细一点，只要形状位置基本一致就能得到高分。
 */
public class DistanceTolerantAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.3;

    /** 容忍距离（像素），在此范围内的前景像素视为匹配 */
    private static final double TOLERANCE = 3.0;

    @Override
    public String name() {
        return "距离容忍匹配";
    }

    @Override
    public double compare(Mat binary1, Mat binary2, Mat gray1, Mat gray2) {
        // 双向匹配：A中的前景是否靠近B的前景，以及反过来
        double matchAtoB = computeDirectionalMatch(binary1, binary2);
        double matchBtoA = computeDirectionalMatch(binary2, binary1);

        // 取平均
        return (matchAtoB + matchBtoA) / 2.0;
    }

    /**
     * 计算 source 中有多少比例的前景像素在 target 前景的 TOLERANCE 范围内
     */
    private double computeDirectionalMatch(Mat source, Mat target) {
        int sourceFg = Core.countNonZero(source);
        if (sourceFg == 0) return 1.0;

        // 对 target 的背景（反转后）做距离变换
        // distanceTransform 需要背景=0的图，计算每个0像素到最近255像素的距离
        // 但我们需要的是：每个前景像素到target最近前景的距离
        // 所以反转target，做距离变换，然后查source前景像素位置的距离值

        // 反转 target：前景(255)→0，背景(0)→255
        Mat targetInv = new Mat();
        Core.bitwise_not(target, targetInv);

        // 距离变换：计算每个像素到最近 0 值像素（即target前景）的距离
        Mat distMap = new Mat();
        Imgproc.distanceTransform(targetInv, distMap, Imgproc.DIST_L2, 3);
        targetInv.release();

        // 遍历 source 的前景像素，检查它们在 distMap 中的距离
        int matchCount = 0;
        for (int y = 0; y < source.rows(); y++) {
            for (int x = 0; x < source.cols(); x++) {
                if (source.get(y, x)[0] > 0) { // source 前景像素
                    double dist = distMap.get(y, x)[0];
                    if (dist <= TOLERANCE) {
                        matchCount++;
                    }
                }
            }
        }

        distMap.release();

        return (double) matchCount / sourceFg;
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
