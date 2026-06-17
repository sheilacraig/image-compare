package com.imagecompare;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

/**
 * 图像预处理器
 * 流程：读取 → 灰度化 → 二值化(Otsu) → 按质心居中到方形画布 → 归一化尺寸
 * 专为白底黑字图片设计
 */
public class ImagePreprocessor {

    /** 归一化后的目标尺寸 */
    public static final int NORMALIZED_SIZE = 128;

    /** 字形外边距（像素），避免笔画贴边 */
    private static final int PADDING = 6;

    /**
     * 预处理结果
     */
    public static class PreprocessResult {
        /** 二值化图像（前景=255，背景=0），尺寸 NORMALIZED_SIZE × NORMALIZED_SIZE */
        public final Mat binary;
        /** 灰度图像（白底黑字），尺寸 NORMALIZED_SIZE × NORMALIZED_SIZE */
        public final Mat grayscale;
        /** 字形原始 bounding rect 的宽高比 = width / height（≥ 0） */
        public final double aspectRatio;
        /** 字形前景像素数（在 NORMALIZED_SIZE 尺度上） */
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
        // 1. 读取灰度图
        Mat src = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (src.empty()) {
            throw new IllegalArgumentException("无法读取图片: " + imagePath);
        }

        // 2. 二值化 (Otsu 反转：字形=255，背景=0)
        Mat binary = new Mat();
        Imgproc.threshold(src, binary, 0, 255, Imgproc.THRESH_BINARY_INV | Imgproc.THRESH_OTSU);

        // 3. 找前景区域
        Mat points = new Mat();
        Core.findNonZero(binary, points);

        if (points.empty()) {
            points.release();
            Mat blankBinary = Mat.zeros(NORMALIZED_SIZE, NORMALIZED_SIZE, CvType.CV_8UC1);
            Mat blankGray = new Mat(NORMALIZED_SIZE, NORMALIZED_SIZE, CvType.CV_8UC1, new Scalar(255));
            src.release();
            binary.release();
            return new PreprocessResult(blankBinary, blankGray, 1.0, 0);
        }

        Rect boundingRect = Imgproc.boundingRect(points);
        points.release();
        double aspectRatio = (double) boundingRect.width / Math.max(1, boundingRect.height);

        // 4. 用 binary 的质心做居中（比 bounding box 几何中心更鲁棒，
        //    对非对称字形如 "卜""了""乙" 更友好）
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

        src.release();
        binary.release();

        return new PreprocessResult(normalizedBinary, normalizedGray, aspectRatio, foregroundPixels);
    }

    /**
     * 将 bounding rect 区域裁出来，放进一个正方形画布（边长 = max(w,h) + 2*padding），
     * 按质心居中，再 resize 到 NORMALIZED_SIZE。
     *
     * 用质心而非 bbox 几何中心可以让非对称字形对齐得更稳定（毛笔字的"重心"对齐）。
     */
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

        // 期望：质心落到 square 的中心 (side/2, side/2)
        // 把 cropped 放在 square 的 (offX, offY) 处，使得：
        //   (offX + (centroidX - boundingRect.x), offY + (centroidY - boundingRect.y)) ≈ (side/2, side/2)
        double localCx = centroidX - boundingRect.x;
        double localCy = centroidY - boundingRect.y;
        int offsetX = (int) Math.round(side / 2.0 - localCx);
        int offsetY = (int) Math.round(side / 2.0 - localCy);

        // 钳制，保证整块 cropped 区域留在 square 内（极端不对称时退化为几何中心对齐）
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
}
