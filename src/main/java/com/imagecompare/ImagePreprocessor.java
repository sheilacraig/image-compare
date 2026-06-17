package com.imagecompare;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * 图像预处理器
 * 流程：读取 → 灰度化 → 二值化(Otsu) → 按质心居中到方形画布 → 归一化尺寸
 * 专为白底黑字图片设计
 *
 * 支持三种输入：文件路径 / BufferedImage / 已是灰度的 OpenCV Mat。
 */
public class ImagePreprocessor {

    /** 归一化后的目标尺寸 */
    public static final int NORMALIZED_SIZE = 128;

    /** 字形外边距（像素），避免笔画贴边 */
    private static final int PADDING = 6;

    public static class PreprocessResult {
        public final Mat binary;
        public final Mat grayscale;
        public final double aspectRatio;
        public final int foregroundPixels;

        public PreprocessResult(Mat binary, Mat grayscale, double aspectRatio, int foregroundPixels) {
            this.binary = binary;
            this.grayscale = grayscale;
            this.aspectRatio = aspectRatio;
            this.foregroundPixels = foregroundPixels;
        }

        public void release() {
            binary.release();
            grayscale.release();
        }
    }

    public PreprocessResult preprocess(String imagePath) {
        Mat src = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (src.empty()) {
            throw new IllegalArgumentException("无法读取图片: " + imagePath);
        }
        try {
            return processGray(src);
        } finally {
            src.release();
        }
    }

    /** 直接处理一个 BufferedImage（如 PDF 渲染得到的字形位图） */
    public PreprocessResult preprocess(BufferedImage image) {
        Mat src = bufferedImageToGrayMat(image);
        try {
            return processGray(src);
        } finally {
            src.release();
        }
    }

    /** 核心流程：从一张已加载的灰度 Mat 走完二值化→裁剪→归一化 */
    private PreprocessResult processGray(Mat src) {
        Mat binary = new Mat();
        Imgproc.threshold(src, binary, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        Mat points = new Mat();
        Core.findNonZero(binary, points);

        if (points.empty()) {
            points.release();
            binary.release();
            Mat blankBinary = Mat.zeros(NORMALIZED_SIZE, NORMALIZED_SIZE, CvType.CV_8UC1);
            Mat blankGray = new Mat(NORMALIZED_SIZE, NORMALIZED_SIZE, CvType.CV_8UC1, new Scalar(255));
            return new PreprocessResult(blankBinary, blankGray, 1.0, 0);
        }

        Rect boundingRect = Imgproc.boundingRect(points);
        points.release();
        double aspectRatio = (double) boundingRect.width / Math.max(1, boundingRect.height);

        Moments m = Imgproc.moments(binary, true);
        double centroidX, centroidY;
        if (m.get_m00() > 1e-6) {
            centroidX = m.get_m10() / m.get_m00();
            centroidY = m.get_m01() / m.get_m00();
        } else {
            centroidX = boundingRect.x + boundingRect.width / 2.0;
            centroidY = boundingRect.y + boundingRect.height / 2.0;
        }

        Mat normalizedBinary = squareAndNormalize(binary, boundingRect, centroidX, centroidY,
                CvType.CV_8UC1, new Scalar(0), true);
        Mat normalizedGray = squareAndNormalize(src, boundingRect, centroidX, centroidY,
                CvType.CV_8UC1, new Scalar(255), false);

        int foregroundPixels = Core.countNonZero(normalizedBinary);

        binary.release();

        return new PreprocessResult(normalizedBinary, normalizedGray, aspectRatio, foregroundPixels);
    }

    private Mat squareAndNormalize(Mat sourceFullImage,
                                   Rect boundingRect,
                                   double centroidX,
                                   double centroidY,
                                   int matType,
                                   Scalar background,
                                   boolean rebinarize) {
        Mat cropped = sourceFullImage.submat(boundingRect);

        int side = Math.max(boundingRect.width, boundingRect.height) + 2 * PADDING;
        Mat square = new Mat(side, side, matType, background);

        double localCx = centroidX - boundingRect.x;
        double localCy = centroidY - boundingRect.y;
        int offsetX = (int) Math.round(side / 2.0 - localCx);
        int offsetY = (int) Math.round(side / 2.0 - localCy);

        offsetX = Math.max(0, Math.min(side - boundingRect.width, offsetX));
        offsetY = Math.max(0, Math.min(side - boundingRect.height, offsetY));

        Mat roi = square.submat(new Rect(offsetX, offsetY, boundingRect.width, boundingRect.height));
        cropped.copyTo(roi);

        Mat normalized = new Mat();
        Imgproc.resize(square, normalized, new Size(NORMALIZED_SIZE, NORMALIZED_SIZE),
                0, 0, Imgproc.INTER_AREA);
        square.release();

        if (rebinarize) {
            Imgproc.threshold(normalized, normalized, 127, 255, Imgproc.THRESH_BINARY);
        }
        return normalized;
    }

    /** 把 BufferedImage 转换成 OpenCV 8UC1 灰度 Mat（必要时复制数据） */
    private static Mat bufferedImageToGrayMat(BufferedImage image) {
        BufferedImage gray;
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            gray = image;
        } else {
            gray = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = gray.createGraphics();
            try {
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        byte[] data = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(gray.getHeight(), gray.getWidth(), CvType.CV_8UC1);
        mat.put(0, 0, data);
        return mat;
    }
}
