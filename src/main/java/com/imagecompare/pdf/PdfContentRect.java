package com.imagecompare.pdf;

/**
 * PDF 页面的版心矩形（以 4 边距表示，单位 PDF point，1pt = 1/72 inch）
 *
 * 用 4 边距而不是 (x,y,w,h)，是为了和 {@link PdfGlyphExtractor} 内部的过滤逻辑统一，
 * 也方便不同尺寸页面间复用（按"距边距离"理解更稳）。
 */
public final class PdfContentRect {

    public final float topPt;
    public final float bottomPt;
    public final float leftPt;
    public final float rightPt;

    public PdfContentRect(float topPt, float bottomPt, float leftPt, float rightPt) {
        this.topPt = clampNonNegative(topPt);
        this.bottomPt = clampNonNegative(bottomPt);
        this.leftPt = clampNonNegative(leftPt);
        this.rightPt = clampNonNegative(rightPt);
    }

    private static float clampNonNegative(float v) {
        return v < 0 ? 0 : v;
    }

    public boolean isEmpty() {
        return topPt == 0 && bottomPt == 0 && leftPt == 0 && rightPt == 0;
    }

    /**
     * 判断给定 PDF point 坐标点 (x, yCenter) 是否落在本矩形定义的版心内。
     * 坐标系：origin 左上、Y 向下，单位 PDF point。
     */
    public boolean accepts(float x, float yCenter, float pageW, float pageH) {
        return x >= leftPt && x <= pageW - rightPt
                && yCenter >= topPt && yCenter <= pageH - bottomPt;
    }

    @Override
    public String toString() {
        return String.format("PdfContentRect[top=%.1fpt, bottom=%.1fpt, left=%.1fpt, right=%.1fpt]",
                topPt, bottomPt, leftPt, rightPt);
    }

    /** 给用户在控制台保存复用的代码片段 */
    public String toJavaSnippet() {
        return String.format("new PdfContentRect(%.1ff, %.1ff, %.1ff, %.1ff)",
                topPt, bottomPt, leftPt, rightPt);
    }
}
