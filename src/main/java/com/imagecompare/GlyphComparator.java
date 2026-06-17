package com.imagecompare;

import com.imagecompare.algorithm.*;
import nu.pattern.OpenCV;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 字形相似度比较器（多算法 + 几何均值融合）
 *
 * 已注册算法（权重相对值，最终按归一化权重做几何均值融合）：
 * <ul>
 *   <li>网格直方图 (GridHistogram) — 直方图交集，密度分布敏感</li>
 *   <li>轮廓匹配 (ContourMatch) — 多组件双向 Hu 矩匹配 + 数量/面积惩罚</li>
 *   <li>距离容忍匹配 (DistanceTolerant) — 距离变换 + 高斯软命中</li>
 *   <li>模板匹配 (TemplateCorr / NCC) — 多偏移搜索的归一化互相关</li>
 *   <li>梯度方向 (HoG) — 笔画方向直方图，捕捉横/竖/撇/捺布局</li>
 *   <li>宽高比 (AspectRatio) — 弥补"塞进方画布"丢失的字形扁/方/瘦信息</li>
 * </ul>
 *
 * 几何均值的好处：任何一项算法明显偏低都会拖低总分，避免单一算法虚高 GAME 总分。
 */
public class GlyphComparator {

    private final ImagePreprocessor preprocessor;
    private final List<SimilarityAlgorithm> algorithms;
    private final double weightSum;

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
                new DistanceTolerantAlgorithm(),
                new TemplateCorrAlgorithm(),
                new HogAlgorithm(),
                new AspectRatioAlgorithm()
        );
        this.weightSum = algorithms.stream().mapToDouble(SimilarityAlgorithm::weight).sum();
    }

    /** 比较两张图片的字形相似度 */
    public CompareResult compare(String referencePath, String candidatePath) {
        ImagePreprocessor.PreprocessResult ref = preprocessor.preprocess(referencePath);
        ImagePreprocessor.PreprocessResult cand = preprocessor.preprocess(candidatePath);
        try {
            return doCompare(ref, cand, candidatePath);
        } finally {
            ref.release();
            cand.release();
        }
    }

    /**
     * 比较两张内存中 {@link BufferedImage} 字形（适合从 PDF 渲染出来的字形位图，
     * 不必落盘成临时文件）。
     */
    public CompareResult compare(BufferedImage reference, BufferedImage candidate, String label) {
        ImagePreprocessor.PreprocessResult ref = preprocessor.preprocess(reference);
        ImagePreprocessor.PreprocessResult cand = preprocessor.preprocess(candidate);
        try {
            return doCompare(ref, cand, label);
        } finally {
            ref.release();
            cand.release();
        }
    }

    /** 同上但直接接受预处理后的结果，外层可缓存重复 PDF 字形的预处理 */
    public CompareResult compare(ImagePreprocessor.PreprocessResult ref,
                                 ImagePreprocessor.PreprocessResult cand,
                                 String label) {
        return doCompare(ref, cand, label);
    }

    /** 暴露 preprocessor 供外部按需缓存预处理结果 */
    public ImagePreprocessor preprocessor() {
        return preprocessor;
    }

    /**
     * 一对多比较，参考图只预处理一次
     * 结果只保留 SAME，按总分降序，最多 5 条
     */
    public List<CompareResult> compareAll(String referencePath, List<String> candidatePaths) {
        ImagePreprocessor.PreprocessResult ref = preprocessor.preprocess(referencePath);
        List<CompareResult> results = new ArrayList<>();
        try {
            for (String candidatePath : candidatePaths) {
                try {
                    ImagePreprocessor.PreprocessResult cand = preprocessor.preprocess(candidatePath);
                    try {
                        results.add(doCompare(ref, cand, candidatePath));
                    } finally {
                        cand.release();
                    }
                } catch (Exception e) {
                    System.err.println("Failed to process: " + candidatePath + " - " + e.getMessage());
                }
            }
        } finally {
            ref.release();
        }
        return results.stream()
                .filter(CompareResult::isSame)
                .sorted(Comparator.comparingDouble(CompareResult::getTotalScore).reversed())
                .limit(5)
                .collect(Collectors.toList());
    }

    /** 一对目录比较 */
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

    /** 内部：执行一次比较 → 几何均值融合 */
    private CompareResult doCompare(ImagePreprocessor.PreprocessResult ref,
                                    ImagePreprocessor.PreprocessResult cand,
                                    String candidatePath) {
        Map<String, Double> scores = new LinkedHashMap<>();
        double logWeightedSum = 0.0;

        for (SimilarityAlgorithm algo : algorithms) {
            double raw = algo.compare(ref, cand);
            double scoreOnHundred = Math.max(0.0, Math.min(1.0, raw)) * 100.0;
            scores.put(algo.name(), scoreOnHundred);

            // 几何均值：在 [0,1] 区间累加 weight * log(score)
            // 用一个极小下界避免单一算法为 0 把总分直接拉到 0
            double clamped = Math.max(0.05, raw);
            logWeightedSum += algo.weight() * Math.log(clamped);
        }
        double geoMean = Math.exp(logWeightedSum / weightSum); // [0,1]
        double totalScore = geoMean * 100.0;
        return new CompareResult(candidatePath, scores, totalScore);
    }

    // ==========================
    //  打印
    // ==========================
    public static void printResults(String referencePath, List<CompareResult> results) {
        if (results.isEmpty()) {
            System.out.println("\nNo SAME match.\n");
            return;
        }

        List<String> algoNames = new ArrayList<>(results.get(0).getScores().keySet());

        int candidateColWidth = 20;
        StringBuilder header = new StringBuilder();
        header.append(String.format("  %-4s  %-" + candidateColWidth + "s", "Rank", "Candidate"));
        for (String n : algoNames) {
            header.append(String.format("  %7s", abbreviate(n, 7)));
        }
        header.append(String.format("  %7s  %s", "Total", "Verdict"));

        StringBuilder sep = new StringBuilder();
        sep.append("  ----  ").append("-".repeat(candidateColWidth));
        for (int i = 0; i < algoNames.size(); i++) sep.append("  -------");
        sep.append("  -------  -------");

        int totalWidth = header.length();
        String line = "=".repeat(Math.max(80, totalWidth));

        System.out.println();
        System.out.println(line);
        System.out.printf("  Glyph Similarity Results  |  Reference: %s%n", referencePath);
        System.out.println(line);
        System.out.println(header);
        System.out.println(sep);

        int rank = 1;
        for (CompareResult r : results) {
            String fileName = Path.of(r.getCandidatePath()).getFileName().toString();
            String verdict = r.isSame() ? "SAME" : "DIFF";
            if (rank == 1) verdict += "  <-- BEST MATCH";

            StringBuilder row = new StringBuilder();
            row.append(String.format("  %-4d  %-" + candidateColWidth + "s",
                    rank,
                    truncate(fileName, candidateColWidth)));
            for (String n : algoNames) {
                row.append(String.format("  %6.1f%%", r.getScore(n)));
            }
            row.append(String.format("  %6.1f   %s", r.getTotalScore(), verdict));
            System.out.println(row);
            rank++;
        }

        System.out.println(line);
        System.out.printf("  Threshold: %.0f+ = SAME glyph  (几何均值融合)%n", CompareResult.SAME_THRESHOLD);
        System.out.println();
    }

    public static void printResult(String referencePath, CompareResult result) {
        printResults(referencePath, List.of(result));
    }

    private static String abbreviate(String s, int max) {
        // 中文 1 字符宽度近似 2，简单处理保证表头对齐
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private boolean isImageFile(String fileName) {
        String lower = fileName.toLowerCase();
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    // ===========================
    //  示例 main
    // ===========================
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        // === 手动修改参数 ===
        String character = "䀹";
        String fontPath = "C:\\Users\\whh\\IdeaProjects\\glyph-compare\\src\\main\\resources\\fonts\\HYXinHuaSong.ttf";
        String directory = "C:\\Users\\whh\\IdeaProjects\\glyph-compare\\src\\main\\resources\\images\\";
        // =========================

        Path referenceImage = GlyphRenderer.renderToTempFile(character, fontPath);
        try {
            GlyphComparator comparator = new GlyphComparator();
            List<CompareResult> results = comparator.compareDirectory(referenceImage.toString(), directory);

            if (!results.isEmpty()) {
                String refLabel = String.format("'%s' (rendered from %s)", character, fontPath);
                printResults(refLabel, results);
            } else {
                System.out.println("没有找到 SAME 候选");
            }
        } finally {
            Files.deleteIfExists(referenceImage);
        }
    }
}
