package com.imagecompare;

/**
 * 字形比较结果
 */
public class CompareResult {

    /** 候选图片路径 */
    private final String candidatePath;

    /** 网格直方图得分 (0~100) - 结构密度分布 */
    private final double gridScore;

    /** 轮廓匹配得分 (0~100) - Hu不变矩 */
    private final double contourScore;

    /** 距离容忍匹配得分 (0~100) - 位置容忍的像素比较 */
    private final double distanceScore;

    /** 综合评分 (0~100) */
    private final double totalScore;

    /** 是否判定为相同字形 */
    private final boolean same;

    /** 相同字形的阈值 */
    public static final double SAME_THRESHOLD = 80.0;

    public CompareResult(String candidatePath, double gridScore, double contourScore, double distanceScore) {
        this.candidatePath = candidatePath;
        this.gridScore = gridScore;
        this.contourScore = contourScore;
        this.distanceScore = distanceScore;
        // 加权计算: 网格直方图 40% + 轮廓 30% + 距离容忍 30%
        this.totalScore = gridScore * 0.4 + contourScore * 0.3 + distanceScore * 0.3;
        this.same = this.totalScore >= SAME_THRESHOLD;
    }

    public String getCandidatePath() { return candidatePath; }
    public double getGridScore() { return gridScore; }
    public double getContourScore() { return contourScore; }
    public double getDistanceScore() { return distanceScore; }
    public double getTotalScore() { return totalScore; }
    public boolean isSame() { return same; }

    @Override
    public String toString() {
        return String.format("%-25s  Grid: %5.1f%%  Contour: %5.1f%%  Distance: %5.1f%%  Total: %5.1f  %s",
                candidatePath, gridScore, contourScore, distanceScore, totalScore,
                same ? "SAME" : "DIFF");
    }
}
