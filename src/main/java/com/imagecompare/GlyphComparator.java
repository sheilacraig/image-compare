package com.imagecompare;

import com.imagecompare.algorithm.ContourMatchAlgorithm;
import com.imagecompare.algorithm.DistanceTolerantAlgorithm;
import com.imagecompare.algorithm.GridHistogramAlgorithm;
import com.imagecompare.algorithm.SimilarityAlgorithm;
import nu.pattern.OpenCV;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 字形相似度比较器
 * <p>
 * 不使用OCR，纯粹基于图像形状特征比较字形相似度。
 * 采用三种互补算法加权打分：
 * - 网格直方图(40%)：比较笔画密度的空间分布
 * - 轮廓匹配(30%)：基于Hu不变矩的形状比较
 * - 距离容忍匹配(30%)：允许小范围位移的像素比较
 * </p>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * GlyphComparator comparator = new GlyphComparator();
 *
 * // 1对1 比较
 * CompareResult result = comparator.compare("reference.png", "candidate.png");
 * System.out.println("相似度: " + result.getTotalScore());
 * System.out.println("是否相同字形: " + result.isSame());
 *
 * // 1对N 比较
 * List<CompareResult> results = comparator.compareAll("reference.png",
 *     Arrays.asList("c1.png", "c2.png", "c3.png"));
 *
 * // 1对目录 比较
 * List<CompareResult> results = comparator.compareDirectory("reference.png", "candidates/");
 * }</pre>
 */
public class GlyphComparator {

    private final ImagePreprocessor preprocessor;
    private final List<SimilarityAlgorithm> algorithms;

    /** 支持的图片扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".bmp", ".tiff", ".tif"
    );

    static {
        OpenCV.loadLocally();
    }

    public GlyphComparator() {
        this.preprocessor = new ImagePreprocessor();
        this.algorithms = List.of(
                new GridHistogramAlgorithm(),
                new ContourMatchAlgorithm(),
                new DistanceTolerantAlgorithm()
        );
    }

    /**
     * 比较两张图片中的字形相似度
     *
     * @param referencePath 参考图片路径
     * @param candidatePath 候选图片路径
     * @return 比较结果，包含各算法分数和综合评分
     */
    public CompareResult compare(String referencePath, String candidatePath) {
        ImagePreprocessor.PreprocessResult ref = preprocessor.preprocess(referencePath);
        ImagePreprocessor.PreprocessResult cand = preprocessor.preprocess(candidatePath);

        CompareResult result = doCompare(ref, cand, candidatePath);

        ref.release();
        cand.release();

        return result;
    }

    /**
     * 一张参考图对多张候选图进行批量比较
     * 参考图只预处理一次，提高效率
     *
     * @param referencePath  参考图片路径
     * @param candidatePaths 候选图片路径列表
     * @return 比较结果列表，按综合评分降序排列
     */
    public List<CompareResult> compareAll(String referencePath, List<String> candidatePaths) {
        ImagePreprocessor.PreprocessResult ref = preprocessor.preprocess(referencePath);

        List<CompareResult> results = new ArrayList<>();
        for (String candidatePath : candidatePaths) {
            try {
                ImagePreprocessor.PreprocessResult cand = preprocessor.preprocess(candidatePath);
                CompareResult result = doCompare(ref, cand, candidatePath);
                cand.release();
                results.add(result);
            } catch (Exception e) {
                System.err.println("Failed to process: " + candidatePath + " - " + e.getMessage());
            }
        }

        ref.release();

        results.sort(Comparator.comparingDouble(CompareResult::getTotalScore).reversed());
        return results;
    }

    /**
     * 一张参考图与指定目录下所有图片进行比较
     *
     * @param referencePath 参考图片路径
     * @param directoryPath 候选图片所在目录路径
     * @return 比较结果列表，按综合评分降序排列
     */
    public List<CompareResult> compareDirectory(String referencePath, String directoryPath) {
        File dir = new File(directoryPath);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a valid directory: " + directoryPath);
        }

        File refFile = new File(referencePath);
        List<String> candidatePaths = Arrays.stream(
                        Objects.requireNonNull(dir.listFiles(), "Empty directory: " + directoryPath))
                .filter(f -> f.isFile() && isImageFile(f.getName()))
                .filter(f -> !f.getAbsolutePath().equals(refFile.getAbsolutePath()))
                .map(File::getAbsolutePath)
                .sorted()
                .collect(Collectors.toList());

        if (candidatePaths.isEmpty()) {
            System.out.println("No image files found in: " + directoryPath);
            return Collections.emptyList();
        }

        return compareAll(referencePath, candidatePaths);
    }

    /**
     * 打印比较结果（格式化表格输出）
     */
    public static void printResults(String referencePath, List<CompareResult> results) {
        System.out.println();
        System.out.println("=============================================================================");
        System.out.printf("  Glyph Similarity Results  |  Reference: %s%n", referencePath);
        System.out.println("=============================================================================");
        System.out.printf("  %-4s  %-20s  %7s  %9s  %9s  %7s  %s%n",
                "Rank", "Candidate", "Grid", "Contour", "Distance", "Total", "Verdict");
        System.out.println("  ----  --------------------  -------  ---------  ---------  -------  -------");

        int rank = 1;
        for (CompareResult r : results) {
            String fileName = Path.of(r.getCandidatePath()).getFileName().toString();
            System.out.printf("  %-4d  %-20s  %5.1f%%  %7.1f%%  %7.1f%%  %5.1f   %s%n",
                    rank++,
                    fileName.length() > 20 ? fileName.substring(0, 17) + "..." : fileName,
                    r.getGridScore(),
                    r.getContourScore(),
                    r.getDistanceScore(),
                    r.getTotalScore(),
                    r.isSame() ? "SAME" : "DIFF");
        }
        System.out.println("=============================================================================");
        System.out.printf("  Threshold: %.0f+ = SAME glyph%n", CompareResult.SAME_THRESHOLD);
        System.out.println();
    }

    /**
     * 打印单次比较结果
     */
    public static void printResult(String referencePath, CompareResult result) {
        printResults(referencePath, List.of(result));
    }

    /**
     * 执行实际比较（内部方法）
     */
    private CompareResult doCompare(ImagePreprocessor.PreprocessResult ref,
                                     ImagePreprocessor.PreprocessResult cand,
                                     String candidatePath) {
        double gridScore = 0, contourScore = 0, distanceScore = 0;

        for (SimilarityAlgorithm algo : algorithms) {
            double score = algo.compare(ref.binary, cand.binary, ref.grayscale, cand.grayscale) * 100.0;
            score = Math.max(0, Math.min(100, score));

            switch (algo.name()) {
                case "\u7F51\u683C\u76F4\u65B9\u56FE" -> gridScore = score;         // 网格直方图
                case "\u8F6E\u5ED3\u5339\u914D" -> contourScore = score;             // 轮廓匹配
                case "\u8DDD\u79BB\u5BB9\u5FCD\u5339\u914D" -> distanceScore = score; // 距离容忍匹配
            }
        }

        return new CompareResult(candidatePath, gridScore, contourScore, distanceScore);
    }

    private boolean isImageFile(String fileName) {
        String lower = fileName.toLowerCase();
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    // ===========================
    //  示例 main 方法
    // ===========================
    public static void main(String[] args) {
        GlyphComparator comparator = new GlyphComparator();

        // --- 示例1: 1对1 比较 ---
        CompareResult result = comparator.compare("images/1.png", "images/2.png");
        GlyphComparator.printResult("images/1.png", result);

        // --- 示例2: 1对N 比较（传入文件列表）---
        // List<CompareResult> results = comparator.compareAll("reference.png",
        //     Arrays.asList("candidate1.png", "candidate2.png", "candidate3.png"));
        // GlyphComparator.printResults("reference.png", results);

        // --- 示例3: 1对目录 比较 ---
        // List<CompareResult> results = comparator.compareDirectory("reference.png", "./candidates/");
        // GlyphComparator.printResults("reference.png", results);
    }
}
