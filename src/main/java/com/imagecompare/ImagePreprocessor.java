package com.imagecompare;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * 图像预处理器
 * 流程：读取 → 灰度化 → 二值化(Otsu) → 裁剪字形区域 → 归一化尺寸
 * 专为白底黑字图片设计
 */
public class ImagePreprocessor {

    /** 归一化后的目标尺寸 */
    public static final int NORMALIZED_SIZE = 128;

    /**
     * 预处理结果
     */
    public static class PreprocessResult {
        /** 二值化图像（前景=255，背景=0） */
        public final Mat binary;
        /** 灰度图像（白底黑字） */
        public final Mat grayscale;

        public PreprocessResult(Mat binary, Mat grayscale) {
            this.binary = binary;
            this.grayscale = grayscale;
        }

        public void release() {
            binary.release();
            grayscale.release();
        }
    }

    /**
     * 读取并预处理图片
     */
    public PreprocessResult preprocess(String imagePath) {
        // 1. 读取图像（灰度）
        Mat src = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (src.empty()) {
            throw new IllegalArgumentException("无法读取图片: " + imagePath);
        }

        // 2. 二值化 (Otsu自适应阈值)
        Mat binary = new Mat();
        Imgproc.threshold(src, binary, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        // 3. 找到字形最小外接矩形
        Mat points = new Mat();
        Core.findNonZero(binary, points);

        Mat normalizedBinary;
        Mat normalizedGray;

        if (points.empty()) {
            points.release();
            normalizedBinary = Mat.zeros(NORMALIZED_SIZE, NORMALIZED_SIZE, CvType.CV_8UC1);
            normalizedGray = new Mat(NORMALIZED_SIZE, NORMALIZED_SIZE, CvType.CV_8UC1, new Scalar(255));
        } else {
            Rect boundingRect = Imgproc.boundingRect(points);
            points.release();

            // 4. 裁剪并居中 - 二值化版本
            normalizedBinary = cropCenterAndNormalize(binary, boundingRect);

            // 5. 裁剪并居中 - 灰度版本
            Mat grayCropped = cropCenterRaw(src, boundingRect);
            normalizedGray = new Mat();
            Imgproc.resize(grayCropped, normalizedGray, new Size(NORMALIZED_SIZE, NORMALIZED_SIZE),
                    0, 0, Imgproc.INTER_AREA);
            grayCropped.release();
        }

        src.release();
        binary.release();

        return new PreprocessResult(normalizedBinary, normalizedGray);
    }

    private Mat cropCenterAndNormalize(Mat image, Rect boundingRect) {
        Mat cropped = image.submat(boundingRect);

        int padding = 4;
        int side = Math.max(boundingRect.width, boundingRect.height) + 2 * padding;
        Mat square = Mat.zeros(side, side, image.type());

        int offsetX = (side - boundingRect.width) / 2;
        int offsetY = (side - boundingRect.height) / 2;
        Mat roi = square.submat(new Rect(offsetX, offsetY, boundingRect.width, boundingRect.height));
        cropped.copyTo(roi);

        Mat normalized = new Mat();
        Imgproc.resize(square, normalized, new Size(NORMALIZED_SIZE, NORMALIZED_SIZE),
                0, 0, Imgproc.INTER_AREA);
        square.release();

        Imgproc.threshold(normalized, normalized, 127, 255, Imgproc.THRESH_BINARY);

        return normalized;
    }

    private Mat cropCenterRaw(Mat src, Rect boundingRect) {
        Mat cropped = src.submat(boundingRect);

        int padding = 4;
        int side = Math.max(boundingRect.width, boundingRect.height) + 2 * padding;
        Mat square = new Mat(side, side, src.type(), new Scalar(255));

        int offsetX = (side - boundingRect.width) / 2;
        int offsetY = (side - boundingRect.height) / 2;
        Mat roi = square.submat(new Rect(offsetX, offsetY, boundingRect.width, boundingRect.height));
        cropped.copyTo(roi);

        return square;
    }
}
