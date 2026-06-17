package com.imagecompare.algorithm;

import com.imagecompare.ImagePreprocessor.PreprocessResult;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/**
 * 距离容忍匹配算法（平滑落距版）
 *
 * 改进点：
 * 1. 旧版用硬阈值 (dist ≤ 3 ⇒ 命中, 否则不命中)，相当于"目标膨胀 3 像素后求重叠"，过于宽松。
 *    新版改为高斯落距 weight = exp(-d² / (2σ²))，距离越远分越低，连续可微，更具区分度。
 * 2. 双向对称：src→dst 和 dst→src 都做。如果 src 比 dst 多了笔画，第二方向会显著扣分。
 * 3. 用直接 buffer 读取（byte[]/float[]）替代 Mat.get() 单点访问，速度提升 ~50×。
 */
public class DistanceTolerantAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.15;

    /** 高斯衰减的标准差（像素）。σ=2 时：d=2→分=0.61, d=4→分=0.14, d=6→分≈0.01 */
    private static final double SIGMA = 2.0;
    private static final double TWO_SIGMA_SQ = 2.0 * SIGMA * SIGMA;

    @Override
    public String name() {
        return "距离容忍匹配";
    }

    @Override
    public double compare(PreprocessResult a, PreprocessResult b) {
        double matchAtoB = directionalSoftMatch(a.binary, b.binary);
        double matchBtoA = directionalSoftMatch(b.binary, a.binary);
        return (matchAtoB + matchBtoA) / 2.0;
    }

    /**
     * 对 source 中每个前景像素 p，记其到 target 最近前景像素的距离 d(p)，
     * 用 exp(-d²/(2σ²)) 给一个 [0,1] 的"软命中分"，对所有 source 前景平均。
     */
    private double directionalSoftMatch(Mat source, Mat target) {
        int rows = source.rows(), cols = source.cols(), total = rows * cols;

        // 1. target 反转后做距离变换：每个 target 前景位置距离=0，离得远的位置距离大
        Mat targetInv = new Mat();
        Core.bitwise_not(target, targetInv);
        Mat distMap = new Mat();
        Imgproc.distanceTransform(targetInv, distMap, Imgproc.DIST_L2, 3);
        targetInv.release();

        // 2. 一次性读出 buffer，避免 Mat.get() 单点开销
        byte[] srcBuf = new byte[total];
        source.get(0, 0, srcBuf);

        float[] distBuf = new float[total];
        distMap.get(0, 0, distBuf);
        distMap.release();

        double sumScore = 0;
        int srcCount = 0;
        for (int i = 0; i < total; i++) {
            if ((srcBuf[i] & 0xFF) > 0) {
                double d = distBuf[i];
                sumScore += Math.exp(-(d * d) / TWO_SIGMA_SQ);
                srcCount++;
            }
        }
        if (srcCount == 0) {
            // source 全空：若 target 也空，则一致；否则零分
            return Core.countNonZero(target) == 0 ? 1.0 : 0.0;
        }
        return sumScore / srcCount;
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
