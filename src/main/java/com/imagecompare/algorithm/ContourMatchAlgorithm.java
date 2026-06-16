package com.imagecompare.algorithm;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 轮廓匹配算法
 * 使用 OpenCV 的 matchShapes() 基于 Hu 不变矩比较轮廓形状
 * 对平移、缩放、旋转具有不变性
 */
public class ContourMatchAlgorithm implements SimilarityAlgorithm {

    private static final double WEIGHT = 0.3;

    @Override
    public String name() {
        return "轮廓匹配";
    }

    @Override
    public double compare(Mat binary1, Mat binary2, Mat gray1, Mat gray2) {
        // 使用外轮廓以避免内轮廓的排序不一致问题
        List<MatOfPoint> contours1 = findContoursSorted(binary1);
        List<MatOfPoint> contours2 = findContoursSorted(binary2);

        if (contours1.isEmpty() || contours2.isEmpty()) {
            releaseContours(contours1);
            releaseContours(contours2);
            return contours1.isEmpty() && contours2.isEmpty() ? 1.0 : 0.0;
        }

        // 使用三种matchShapes方法的综合分数
        double score1 = 0, score2 = 0, score3 = 0;

        // 只比较最大轮廓（整个字形的外轮廓），避免子轮廓排序错配
        MatOfPoint c1 = contours1.get(0);
        MatOfPoint c2 = contours2.get(0);

        // I1 方法：对称Hu矩差
        double d1 = Imgproc.matchShapes(c1, c2, Imgproc.CONTOURS_MATCH_I1, 0);
        score1 = 1.0 / (1.0 + 0.25 * d1);

        // I2 方法：Hu矩log差的绝对值之和
        double d2 = Imgproc.matchShapes(c1, c2, Imgproc.CONTOURS_MATCH_I2, 0);
        score2 = 1.0 / (1.0 + d2);

        // I3 方法：最大差值
        double d3 = Imgproc.matchShapes(c1, c2, Imgproc.CONTOURS_MATCH_I3, 0);
        score3 = 1.0 / (1.0 + d3);

        // 还考虑面积比（形状面积的相对差异）
        double area1 = Imgproc.contourArea(c1);
        double area2 = Imgproc.contourArea(c2);
        double areaRatio = Math.min(area1, area2) / Math.max(area1, area2);

        releaseContours(contours1);
        releaseContours(contours2);

        // 综合三种方法 + 面积比
        double shapeScore = (score1 + score2 + score3) / 3.0;
        return shapeScore * 0.8 + areaRatio * 0.2;
    }

    private List<MatOfPoint> findContoursSorted(Mat binary) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        // 使用 RETR_EXTERNAL 只提取外轮廓
        Imgproc.findContours(binary.clone(), contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();

        contours.sort(Comparator.comparingDouble(c -> -Imgproc.contourArea(c)));

        // 过滤噪点
        if (!contours.isEmpty()) {
            double maxArea = Imgproc.contourArea(contours.get(0));
            double threshold = maxArea * 0.01;
            contours.removeIf(c -> Imgproc.contourArea(c) < threshold);
        }

        return contours;
    }

    private void releaseContours(List<MatOfPoint> contours) {
        for (MatOfPoint c : contours) {
            c.release();
        }
    }

    @Override
    public double weight() {
        return WEIGHT;
    }
}
