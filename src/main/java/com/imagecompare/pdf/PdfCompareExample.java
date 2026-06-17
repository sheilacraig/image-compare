package com.imagecompare.pdf;

/**
 * PDF 内容对比示例
 * <p>
 * 在 main 里改两个 pdf 路径即可运行。
 */
public class PdfCompareExample {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");

        String pdfA = "C:\\Users\\whh\\Downloads\\1.pdf";
        String pdfB = "C:\\Users\\whh\\Downloads\\b.pdf";

        long t0 = System.currentTimeMillis();

        PdfContentComparator comparator = new PdfContentComparator();
        PdfContentDiff diff = comparator.compare(pdfA, pdfB);

        long ms = System.currentTimeMillis() - t0;
        System.out.printf("耗时: %d ms%n%n", ms);
        System.out.println(diff.summary());
    }
}
