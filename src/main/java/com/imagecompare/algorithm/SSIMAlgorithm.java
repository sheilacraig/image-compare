package com.imagecompare.algorithm;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/**
 * SSIM（结构相似性）算法
 * 使用灰度图进行计算。
 * 对输入图像先做轻微高斯模糊，平滑微小的像素级差异，
 * 让SSIM更关注整体结构而非局部噪声。
 */
public class SSIMAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.4;

    private static final double C1 = 6.5025;
    private static final double C2 = 58.5225;
    private static final int WINDOW_SIZE = 11;
    private static final double SIGMA = 1.5;

    @Override
    public String name() {
        return "SSIM";
    }

    @Override
    public double compare(Mat binary1, Mat binary2, Mat gray1, Mat gray2) {
        // 先对灰度图做轻微模糊，平滑抗锯齿差异和微小位移
        Mat blurred1 = new Mat();
        Mat blurred2 = new Mat();
        Imgproc.GaussianBlur(gray1, blurred1, new Size(3, 3), 0.5);
        Imgproc.GaussianBlur(gray2, blurred2, new Size(3, 3), 0.5);

        Mat i1 = new Mat();
        Mat i2 = new Mat();
        blurred1.convertTo(i1, CvType.CV_64F);
        blurred2.convertTo(i2, CvType.CV_64F);
        blurred1.release();
        blurred2.release();

        Size winSize = new Size(WINDOW_SIZE, WINDOW_SIZE);

        Mat mu1 = new Mat(), mu2 = new Mat();
        Imgproc.GaussianBlur(i1, mu1, winSize, SIGMA);
        Imgproc.GaussianBlur(i2, mu2, winSize, SIGMA);

        Mat mu1_sq = new Mat(), mu2_sq = new Mat(), mu1_mu2 = new Mat();
        Core.multiply(mu1, mu1, mu1_sq);
        Core.multiply(mu2, mu2, mu2_sq);
        Core.multiply(mu1, mu2, mu1_mu2);

        Mat sigma1_sq = new Mat(), sigma2_sq = new Mat(), sigma12 = new Mat();
        Mat i1_sq = new Mat(), i2_sq = new Mat(), i1_i2 = new Mat();

        Core.multiply(i1, i1, i1_sq);
        Core.multiply(i2, i2, i2_sq);
        Core.multiply(i1, i2, i1_i2);

        Imgproc.GaussianBlur(i1_sq, sigma1_sq, winSize, SIGMA);
        Core.subtract(sigma1_sq, mu1_sq, sigma1_sq);

        Imgproc.GaussianBlur(i2_sq, sigma2_sq, winSize, SIGMA);
        Core.subtract(sigma2_sq, mu2_sq, sigma2_sq);

        Imgproc.GaussianBlur(i1_i2, sigma12, winSize, SIGMA);
        Core.subtract(sigma12, mu1_mu2, sigma12);

        Mat t1 = new Mat(), t2 = new Mat(), t3 = new Mat();

        Core.multiply(mu1_mu2, new Scalar(2.0), t1);
        Core.add(t1, new Scalar(C1), t1);
        Core.multiply(sigma12, new Scalar(2.0), t2);
        Core.add(t2, new Scalar(C2), t2);
        Core.multiply(t1, t2, t3);

        Core.add(mu1_sq, mu2_sq, t1);
        Core.add(t1, new Scalar(C1), t1);
        Core.add(sigma1_sq, sigma2_sq, t2);
        Core.add(t2, new Scalar(C2), t2);
        Core.multiply(t1, t2, t1);

        Mat ssimMap = new Mat();
        Core.divide(t3, t1, ssimMap);

        Scalar mssim = Core.mean(ssimMap);
        double ssim = mssim.val[0];

        i1.release(); i2.release();
        mu1.release(); mu2.release();
        mu1_sq.release(); mu2_sq.release(); mu1_mu2.release();
        sigma1_sq.release(); sigma2_sq.release(); sigma12.release();
        i1_sq.release(); i2_sq.release(); i1_i2.release();
        t1.release(); t2.release(); t3.release();
        ssimMap.release();

        return Math.max(0.0, ssim);
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
