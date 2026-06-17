package com.imagecompare.pdf;

import java.io.File;

/**
 * 交互式 PDF 内容对比示例
 *
 * 行为：
 *   1) 弹出 UI 给 A 框选版心 —— 若同目录下已有 .contentrect.json 会自动加载，
 *      否则空白起步；可点击「复用上一份」按钮直接复用（首次为灰）
 *   2) 弹出 UI 给 B 框选版心 —— 复用按钮此时启用，可一键继承 A 的版心
 *   3) 「确定」时自动写 sidecar JSON，下次跑同一份 PDF 不必再画
 *   4) 跑对比、打印结果
 *
 * 需要 GUI 环境，**不能开启 headless**。
 */
public class PdfCompareInteractiveExample {

    public static void main(String[] args) throws Exception {
        // === 改成你自己的两个 PDF ===
        String pdfA = "C:\\Users\\whh\\Downloads\\1.pdf";
        String pdfB = "C:\\Users\\whh\\Downloads\\b.pdf";
        // ==========================

        System.out.println("→ 给 PDF A 框选版心: " + pdfA);
        System.out.println("  sidecar: " + PdfContentArea.sidecarFile(new File(pdfA)));
        PdfContentArea areaA = PdfContentAreaPicker.pick(pdfA);
        System.out.println("  结果: " + areaA);

        System.out.println("\n→ 给 PDF B 框选版心: " + pdfB);
        System.out.println("  sidecar: " + PdfContentArea.sidecarFile(new File(pdfB)));
        // 把 A 的 area 作为"复用上一份"的种子传给 B 的拾取器
        PdfContentArea areaB = PdfContentAreaPicker.pick(new File(pdfB), areaA, true);
        System.out.println("  结果: " + areaB);

        long t0 = System.currentTimeMillis();
        PdfContentComparator comparator = new PdfContentComparator();
        PdfContentDiff diff = comparator.compare(pdfA, areaA, pdfB, areaB);
        long ms = System.currentTimeMillis() - t0;

        System.out.printf("%n耗时: %d ms%n%n", ms);
        System.out.println(diff.summary());
    }
}
