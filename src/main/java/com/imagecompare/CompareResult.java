package com.imagecompare;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字形比较结果（多算法版）
 */
public class CompareResult {

    /** 候选图片路径 */
    private final String candidatePath;

    /** 各算法得分（算法名 -> 0~100），保持插入顺序方便打印 */
    private final Map<String, Double> scores;

    /** 综合评分 0~100（由 {@code GlyphComparator} 用几何均值融合后传入） */
    private final double totalScore;

    /** 是否判定为相同字形 */
    private final boolean same;

    /**
     * 相同字形阈值。
     * 新算法采用几何均值融合 + 每算法独立打分，分数比旧版分散：
     *   - 同字形通常 75 ~ 95
     *   - 不同字形通常 30 ~ 65
     * 70 为新的合理切点。
     */
    public static final double SAME_THRESHOLD = 70.0;

    public CompareResult(String candidatePath, Map<String, Double> scores, double totalScore) {
        this.candidatePath = candidatePath;
        this.scores = new LinkedHashMap<>(scores);
        this.totalScore = totalScore;
        this.same = totalScore >= SAME_THRESHOLD;
    }

    public String getCandidatePath() { return candidatePath; }
    public double getTotalScore() { return totalScore; }
    public boolean isSame() { return same; }

    /** 按算法名取得分 */
    public double getScore(String algorithmName) {
        return scores.getOrDefault(algorithmName, 0.0);
    }

    /** 所有算法得分（只读） */
    public Map<String, Double> getScores() {
        return Collections.unmodifiableMap(scores);
    }

    // === 旧版字段访问器（向后兼容） ===
    public double getGridScore()     { return getScore("网格直方图"); }
    public double getContourScore()  { return getScore("轮廓匹配"); }
    public double getDistanceScore() { return getScore("距离容忍匹配"); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-25s ", candidatePath));
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            sb.append(String.format(" %s:%5.1f%%", e.getKey(), e.getValue()));
        }
        sb.append(String.format("  Total:%5.1f  %s", totalScore, same ? "SAME" : "DIFF"));
        return sb.toString();
    }
}
