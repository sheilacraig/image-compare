package com.imagecompare.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 两个 PDF 的内容对比结果
 */
public final class PdfContentDiff {

    public enum OpType {
        /** Unicode 一致（且字形相似度未触发可疑阈值） */
        MATCH,
        /** Unicode 一致但字形差异巨大 —— 可能字体替换/排印偏差 */
        UNICODE_MATCH_GLYPH_DIFF,
        /** Unicode 不同但字形几乎一致 —— 多见于嵌入子集字体内部编码不同 */
        GLYPH_MATCH_UNICODE_DIFF,
        /** A 中独有 */
        ONLY_IN_A,
        /** B 中独有 */
        ONLY_IN_B,
        /** 真正替换：Unicode 与 字形都不同 */
        REPLACEMENT
    }

    public static final class Op {
        public final OpType type;
        public final PdfGlyph a;          // 可能为 null（INSERT）
        public final PdfGlyph b;          // 可能为 null（DELETE）
        public final double glyphSimilarity; // 0~100，未计算则为 NaN

        public Op(OpType type, PdfGlyph a, PdfGlyph b, double glyphSimilarity) {
            this.type = type;
            this.a = a;
            this.b = b;
            this.glyphSimilarity = glyphSimilarity;
        }

        public boolean isAligned() {
            return a != null && b != null;
        }
    }

    private final List<Op> ops;
    private final int totalA;
    private final int totalB;

    public PdfContentDiff(List<Op> ops, int totalA, int totalB) {
        this.ops = ops;
        this.totalA = totalA;
        this.totalB = totalB;
    }

    public List<Op> getOps() { return Collections.unmodifiableList(ops); }
    public int getTotalA() { return totalA; }
    public int getTotalB() { return totalB; }

    public int count(OpType type) {
        int c = 0;
        for (Op op : ops) if (op.type == type) c++;
        return c;
    }

    /** "真等同"字符数 = MATCH + GLYPH_MATCH_UNICODE_DIFF（两者都视为内容一致） */
    public int matchedCount() {
        return count(OpType.MATCH) + count(OpType.GLYPH_MATCH_UNICODE_DIFF);
    }

    /** 整体一致度：两个文档"对齐字符数 × 2 / (totalA + totalB)" */
    public double overallSimilarity() {
        if (totalA == 0 && totalB == 0) return 1.0;
        return 2.0 * matchedCount() / (totalA + totalB);
    }

    public List<Op> firstDifferences(int limit) {
        List<Op> out = new ArrayList<>(limit);
        for (Op op : ops) {
            if (op.type == OpType.MATCH) continue;
            out.add(op);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 把差异格式化打印 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== PDF Content Diff ==========\n");
        sb.append(String.format("PDF A: %d glyphs%n", totalA));
        sb.append(String.format("PDF B: %d glyphs%n", totalB));
        sb.append("\nBreakdown:\n");
        sb.append(String.format("  MATCH (Unicode 同 + 字形相似)     : %d%n", count(OpType.MATCH)));
        sb.append(String.format("  GLYPH_MATCH_UNICODE_DIFF (内容同/编码异): %d%n",
                count(OpType.GLYPH_MATCH_UNICODE_DIFF)));
        sb.append(String.format("  UNICODE_MATCH_GLYPH_DIFF (字体替换?): %d%n",
                count(OpType.UNICODE_MATCH_GLYPH_DIFF)));
        sb.append(String.format("  ONLY_IN_A (B 缺)                  : %d%n", count(OpType.ONLY_IN_A)));
        sb.append(String.format("  ONLY_IN_B (A 缺)                  : %d%n", count(OpType.ONLY_IN_B)));
        sb.append(String.format("  REPLACEMENT (真实替换)            : %d%n", count(OpType.REPLACEMENT)));
        sb.append(String.format("%n整体一致度: %.2f%%%n", overallSimilarity() * 100.0));

        List<Op> diffs = firstDifferences(20);
        if (!diffs.isEmpty()) {
            sb.append("\n前 20 条差异:\n");
            for (Op op : diffs) {
                sb.append("  ").append(formatOp(op)).append('\n');
            }
        }
        return sb.toString();
    }

    private String formatOp(Op op) {
        String simStr = Double.isNaN(op.glyphSimilarity) ? "  -  "
                : String.format("%5.1f", op.glyphSimilarity);
        switch (op.type) {
            case ONLY_IN_A:
                return String.format("[A 多] %s", op.a.describe());
            case ONLY_IN_B:
                return String.format("[B 多] %s", op.b.describe());
            case UNICODE_MATCH_GLYPH_DIFF:
                return String.format("[字形可疑] %s vs %s   字形相似度=%s",
                        op.a.describe(), op.b.describe(), simStr);
            case GLYPH_MATCH_UNICODE_DIFF:
                return String.format("[编码差异] %s vs %s   字形相似度=%s",
                        op.a.describe(), op.b.describe(), simStr);
            case REPLACEMENT:
                return String.format("[真实差异] %s vs %s   字形相似度=%s",
                        op.a.describe(), op.b.describe(), simStr);
            default:
                return op.type.name();
        }
    }
}
