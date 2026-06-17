package com.imagecompare.pdf;

import com.imagecompare.CompareResult;
import com.imagecompare.GlyphComparator;
import com.imagecompare.ImagePreprocessor;
import com.imagecompare.ImagePreprocessor.PreprocessResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 两个 PDF 的内容对比器
 *
 * 流程：
 *  1. 用 {@link PdfGlyphExtractor} 把两本 PDF 各自展开成 List&lt;PdfGlyph&gt;
 *  2. 用 Unicode 等价做 LCS 序列对齐，产出 raw ops（MATCH / DELETE / INSERT）
 *  3. Refine 阶段：
 *     a. 已 MATCH 的位置可选用字形引擎二次验证（字形差距巨大时降级为 UNICODE_MATCH_GLYPH_DIFF）
 *     b. 连续的 DELETE+INSERT 段中，用字形引擎在窗口内做最佳配对：
 *        - 高相似度 (≥ GLYPH_SAME_THRESHOLD) ⇒ 标 GLYPH_MATCH_UNICODE_DIFF（内容同/编码不同）
 *        - 其余 ⇒ REPLACEMENT
 *     剩余落单的 ⇒ ONLY_IN_A / ONLY_IN_B
 *
 * 几个核心阈值都是百分制（0~100）。
 */
public class PdfContentComparator {

    /** 字形配对判同的阈值（>= 即认为视觉上同一个字） */
    public static final double GLYPH_SAME_THRESHOLD = 70.0;
    /** Unicode 一致但字形相似度若低于此值，标记为可疑（字体替换、错排等） */
    public static final double GLYPH_SUSPICION_THRESHOLD = 45.0;

    /** Refine 阶段的滑动窗口长度，限制 O(N×M) 字形比对开销 */
    private static final int REFINE_WINDOW = 12;

    private final PdfGlyphExtractor extractor;
    private final GlyphComparator glyphComparator;
    /** 是否对所有 Unicode-MATCH 也跑一次字形验证（开启更准但慢） */
    private final boolean verifyMatches;

    public PdfContentComparator() {
        this(new PdfGlyphExtractor(), new GlyphComparator(), true);
    }

    public PdfContentComparator(PdfGlyphExtractor extractor,
                                GlyphComparator glyphComparator,
                                boolean verifyMatches) {
        this.extractor = extractor;
        this.glyphComparator = glyphComparator;
        this.verifyMatches = verifyMatches;
    }

    public PdfContentDiff compare(String pdfA, String pdfB) throws IOException {
        return compare(pdfA, (PdfContentArea) null, pdfB, (PdfContentArea) null);
    }

    /** 单矩形版本 —— 内部包成 area */
    public PdfContentDiff compare(String pdfA, PdfContentRect rectA,
                                  String pdfB, PdfContentRect rectB) throws IOException {
        return compare(pdfA, PdfContentArea.single(rectA),
                       pdfB, PdfContentArea.single(rectB));
    }

    /**
     * 给两个 PDF 分别指定版心（可由 {@link PdfContentAreaPicker} 弹 UI 框选得到）。
     * 任一 area 为 null/空集即按 extractor 自身的默认配置或整页处理。
     */
    public PdfContentDiff compare(String pdfA, PdfContentArea areaA,
                                  String pdfB, PdfContentArea areaB) throws IOException {
        List<PdfGlyph> a = extractor.extract(pdfA, areaA);
        List<PdfGlyph> b = extractor.extract(pdfB, areaB);

        // 预处理两侧所有字形（一次性，缓存复用），最后统一释放
        PreprocessResult[] preA = preprocessAll(a);
        PreprocessResult[] preB = preprocessAll(b);

        try {
            List<RawOp> raw = lcsAlign(a, b);
            List<PdfContentDiff.Op> finalOps = refine(raw, a, b, preA, preB);
            return new PdfContentDiff(finalOps, a.size(), b.size());
        } finally {
            for (PreprocessResult p : preA) if (p != null) p.release();
            for (PreprocessResult p : preB) if (p != null) p.release();
        }
    }

    private PreprocessResult[] preprocessAll(List<PdfGlyph> glyphs) {
        ImagePreprocessor pp = glyphComparator.preprocessor();
        PreprocessResult[] arr = new PreprocessResult[glyphs.size()];
        for (int i = 0; i < glyphs.size(); i++) {
            try {
                arr[i] = pp.preprocess(glyphs.get(i).image);
            } catch (Exception e) {
                arr[i] = null; // 个别失败不影响整体对齐
            }
        }
        return arr;
    }

    // ===========================================================
    //  LCS 对齐
    // ===========================================================

    private enum RawType { MATCH, DELETE, INSERT }

    private static final class RawOp {
        final RawType type;
        final int aIdx;
        final int bIdx;
        RawOp(RawType type, int aIdx, int bIdx) {
            this.type = type;
            this.aIdx = aIdx;
            this.bIdx = bIdx;
        }
    }

    /**
     * 简单 O(N×M) DP LCS。
     * 相等条件：双侧 Unicode 可信且相等。
     * 对超大文档（>5000 字符）建议另接 Hirschberg / Myers diff。
     */
    private List<RawOp> lcsAlign(List<PdfGlyph> a, List<PdfGlyph> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (unicodeEquals(a.get(i - 1), b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        // 回溯
        List<RawOp> ops = new ArrayList<>();
        int i = n, j = m;
        while (i > 0 && j > 0) {
            if (unicodeEquals(a.get(i - 1), b.get(j - 1))) {
                ops.add(new RawOp(RawType.MATCH, i - 1, j - 1));
                i--; j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                ops.add(new RawOp(RawType.DELETE, i - 1, -1));
                i--;
            } else {
                ops.add(new RawOp(RawType.INSERT, -1, j - 1));
                j--;
            }
        }
        while (i > 0) { ops.add(new RawOp(RawType.DELETE, --i, -1)); }
        while (j > 0) { ops.add(new RawOp(RawType.INSERT, -1, --j)); }
        java.util.Collections.reverse(ops);
        return ops;
    }

    private boolean unicodeEquals(PdfGlyph x, PdfGlyph y) {
        if (!x.isUnicodeTrustworthy() || !y.isUnicodeTrustworthy()) return false;
        return x.unicode.equals(y.unicode);
    }

    // ===========================================================
    //  Refine：用字形引擎二次裁定
    // ===========================================================

    private List<PdfContentDiff.Op> refine(List<RawOp> raw,
                                           List<PdfGlyph> a, List<PdfGlyph> b,
                                           PreprocessResult[] preA, PreprocessResult[] preB) {
        List<PdfContentDiff.Op> out = new ArrayList<>();
        int k = 0;
        while (k < raw.size()) {
            RawOp op = raw.get(k);
            if (op.type == RawType.MATCH) {
                PdfGlyph ga = a.get(op.aIdx);
                PdfGlyph gb = b.get(op.bIdx);
                double sim = Double.NaN;
                PdfContentDiff.OpType type = PdfContentDiff.OpType.MATCH;
                if (verifyMatches) {
                    sim = compareGlyph(preA[op.aIdx], preB[op.bIdx], ga.describe());
                    if (!Double.isNaN(sim) && sim < GLYPH_SUSPICION_THRESHOLD) {
                        type = PdfContentDiff.OpType.UNICODE_MATCH_GLYPH_DIFF;
                    }
                }
                out.add(new PdfContentDiff.Op(type, ga, gb, sim));
                k++;
                continue;
            }
            // 收集一段连续的 DELETE/INSERT，准备做字形配对
            int runStart = k;
            List<Integer> dels = new ArrayList<>();
            List<Integer> inss = new ArrayList<>();
            while (k < raw.size() && raw.get(k).type != RawType.MATCH) {
                RawOp r = raw.get(k);
                if (r.type == RawType.DELETE) dels.add(r.aIdx);
                else inss.add(r.bIdx);
                k++;
            }
            resolveRun(dels, inss, a, b, preA, preB, out);
            // 防御性：runStart < k 一定成立
            if (k == runStart) k++;
        }
        return out;
    }

    /**
     * 在一段 DELETE+INSERT 中，用字形相似度做最佳配对。
     * 用滑动窗口限制：A[i] 只在 B 的 [i - WIN, i + WIN] 范围内找配对。
     * 贪心：所有候选对按相似度降序，逐个吃掉。
     */
    private void resolveRun(List<Integer> dels, List<Integer> inss,
                            List<PdfGlyph> a, List<PdfGlyph> b,
                            PreprocessResult[] preA, PreprocessResult[] preB,
                            List<PdfContentDiff.Op> out) {
        if (dels.isEmpty() && inss.isEmpty()) return;
        if (inss.isEmpty()) {
            for (int ai : dels) out.add(new PdfContentDiff.Op(
                    PdfContentDiff.OpType.ONLY_IN_A, a.get(ai), null, Double.NaN));
            return;
        }
        if (dels.isEmpty()) {
            for (int bi : inss) out.add(new PdfContentDiff.Op(
                    PdfContentDiff.OpType.ONLY_IN_B, null, b.get(bi), Double.NaN));
            return;
        }

        // 候选对：(score, aIdxInDels, bIdxInInss)
        List<double[]> candidates = new ArrayList<>();
        for (int di = 0; di < dels.size(); di++) {
            int ai = dels.get(di);
            for (int ii = 0; ii < inss.size(); ii++) {
                int bi = inss.get(ii);
                if (Math.abs(ai - bi) > REFINE_WINDOW) continue;
                double sim = compareGlyph(preA[ai], preB[bi], "refine");
                if (Double.isNaN(sim)) continue;
                candidates.add(new double[]{sim, di, ii});
            }
        }
        candidates.sort((x, y) -> Double.compare(y[0], x[0]));

        boolean[] usedA = new boolean[dels.size()];
        boolean[] usedB = new boolean[inss.size()];
        // 记录每个 dels 配对结果，后续按原顺序输出
        int[] pairedB = new int[dels.size()];
        double[] pairedSim = new double[dels.size()];
        java.util.Arrays.fill(pairedB, -1);

        for (double[] c : candidates) {
            int di = (int) c[1];
            int ii = (int) c[2];
            if (usedA[di] || usedB[ii]) continue;
            usedA[di] = true;
            usedB[ii] = true;
            pairedB[di] = ii;
            pairedSim[di] = c[0];
        }

        // 把 A 段全部按原顺序输出（配对的 ⇒ GLYPH_MATCH_UNICODE_DIFF / REPLACEMENT；未配对 ⇒ ONLY_IN_A）
        for (int di = 0; di < dels.size(); di++) {
            int ai = dels.get(di);
            if (pairedB[di] >= 0) {
                int bi = inss.get(pairedB[di]);
                double sim = pairedSim[di];
                PdfContentDiff.OpType type = sim >= GLYPH_SAME_THRESHOLD
                        ? PdfContentDiff.OpType.GLYPH_MATCH_UNICODE_DIFF
                        : PdfContentDiff.OpType.REPLACEMENT;
                out.add(new PdfContentDiff.Op(type, a.get(ai), b.get(bi), sim));
            } else {
                out.add(new PdfContentDiff.Op(
                        PdfContentDiff.OpType.ONLY_IN_A, a.get(ai), null, Double.NaN));
            }
        }
        // 把 B 段中未被配对的输出为 ONLY_IN_B
        for (int ii = 0; ii < inss.size(); ii++) {
            if (!usedB[ii]) {
                int bi = inss.get(ii);
                out.add(new PdfContentDiff.Op(
                        PdfContentDiff.OpType.ONLY_IN_B, null, b.get(bi), Double.NaN));
            }
        }
    }

    private double compareGlyph(PreprocessResult pa, PreprocessResult pb, String label) {
        if (pa == null || pb == null) return Double.NaN;
        try {
            CompareResult r = glyphComparator.compare(pa, pb, label);
            return r.getTotalScore();
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
