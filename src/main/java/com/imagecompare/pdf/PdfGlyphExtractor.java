package com.imagecompare.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 PDF 中按阅读顺序提取每一个字符的 (Unicode + 渲染字形位图)
 *
 * 支持**版心**配置：通过设置上下左右边距，超出"版心矩形"的字（页眉/页脚/页码/水印等）
 * 不进入对比集合。两种用法：
 * <pre>
 *   // 1) 比例（推荐）：相对于每页 cropBox 的比例，0.08 = 8%
 *   new PdfGlyphExtractor().withMarginRatios(0.08, 0.08, 0.10, 0.10);
 *
 *   // 2) 绝对点 (PDF point = 1/72 inch)：72 ≈ 1 inch ≈ 2.54 cm
 *   new PdfGlyphExtractor().withMargins(54f, 54f, 54f, 54f); // 上下左右各 0.75 inch
 * </pre>
 *
 * 边距全为 0 时表示不裁，提取整页全部文本。
 */
public class PdfGlyphExtractor {

    /** 渲染倍数。2.5 ≈ 180 DPI */
    public static final float DEFAULT_SCALE = 2.5f;

    /** 裁字形时在 PDF 坐标系给的外扩 padding（像素） */
    private static final int CROP_PADDING_PX = 4;

    private final float scale;
    private final boolean skipWhitespace;

    // 版心边距（含义见 marginsAreRatios）
    private float marginTop = 0f;
    private float marginBottom = 0f;
    private float marginLeft = 0f;
    private float marginRight = 0f;
    /** true: marginXxx 是 0..1 比例；false: 是 PDF points */
    private boolean marginsAreRatios = false;

    public PdfGlyphExtractor() {
        this(DEFAULT_SCALE, true);
    }

    public PdfGlyphExtractor(float scale, boolean skipWhitespace) {
        this.scale = scale;
        this.skipWhitespace = skipWhitespace;
    }

    /**
     * 设置版心边距（PDF point，1 pt = 1/72 inch）。在该边距以外的字会被丢弃。
     */
    public PdfGlyphExtractor withMargins(float topPt, float bottomPt, float leftPt, float rightPt) {
        this.marginTop = clampNonNegative(topPt);
        this.marginBottom = clampNonNegative(bottomPt);
        this.marginLeft = clampNonNegative(leftPt);
        this.marginRight = clampNonNegative(rightPt);
        this.marginsAreRatios = false;
        return this;
    }

    /**
     * 设置版心边距比例（0..0.5）。例如 (0.08, 0.08, 0.10, 0.10)
     * 表示上下各裁掉页面 8%、左右各裁 10%。
     */
    public PdfGlyphExtractor withMarginRatios(double top, double bottom, double left, double right) {
        this.marginTop = (float) clampRatio(top);
        this.marginBottom = (float) clampRatio(bottom);
        this.marginLeft = (float) clampRatio(left);
        this.marginRight = (float) clampRatio(right);
        this.marginsAreRatios = true;
        return this;
    }

    /** 默认书籍版心预设：上下 8%、左右 10% */
    public PdfGlyphExtractor withDefaultBookMargins() {
        return withMarginRatios(0.08, 0.08, 0.10, 0.10);
    }

    public List<PdfGlyph> extract(String pdfPath) throws IOException {
        return extract(new File(pdfPath), (PdfContentArea) null);
    }

    public List<PdfGlyph> extract(File pdfFile) throws IOException {
        return extract(pdfFile, (PdfContentArea) null);
    }

    public List<PdfGlyph> extract(String pdfPath, PdfContentRect rectOverride) throws IOException {
        return extract(new File(pdfPath), PdfContentArea.single(rectOverride));
    }

    /** 单矩形 overload —— 内部包成 PdfContentArea */
    public List<PdfGlyph> extract(File pdfFile, PdfContentRect rectOverride) throws IOException {
        return extract(pdfFile, PdfContentArea.single(rectOverride));
    }

    public List<PdfGlyph> extract(String pdfPath, PdfContentArea areaOverride) throws IOException {
        return extract(new File(pdfPath), areaOverride);
    }

    /**
     * 提取字形。
     * areaOverride 非空且非空集 → 用作版心（多矩形并集）；否则使用通过 withMargins/withMarginRatios
     * 配置的默认值；都缺时整页提取。
     */
    public List<PdfGlyph> extract(File pdfFile, PdfContentArea areaOverride) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            int pageCount = doc.getNumberOfPages();
            BufferedImage[] pageImages = new BufferedImage[pageCount];
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < pageCount; i++) {
                pageImages[i] = renderer.renderImage(i, scale, ImageType.GRAY);
            }

            List<PdfGlyph> out = new ArrayList<>();
            int[] seq = {0};

            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String string, List<TextPosition> textPositions) {
                    int pageIdx = getCurrentPageNo() - 1;
                    if (pageIdx < 0 || pageIdx >= pageImages.length) return;
                    BufferedImage pageImg = pageImages[pageIdx];

                    PDPage page = getCurrentPage();
                    PDRectangle box = page.getCropBox();
                    float pageW = box.getWidth();
                    float pageH = box.getHeight();

                    for (TextPosition tp : textPositions) {
                        String u = tp.getUnicode();
                        if (skipWhitespace && (u == null || isAllWhitespace(u))) continue;
                        if (!isInContentArea(tp, pageW, pageH, areaOverride)) continue;

                        BufferedImage crop = cropGlyph(pageImg, tp);
                        if (crop == null) continue;
                        out.add(new PdfGlyph(pageIdx, seq[0]++, u, crop,
                                tp.getXDirAdj(), tp.getYDirAdj()));
                    }
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(doc);

            return out;
        }
    }

    /**
     * 判断字符是否在版心范围内。
     * areaOverride 优先于实例配置的边距；二者都缺时 → 整页不裁。
     * 多矩形 area 是并集语义：任一矩形接受即接受。
     */
    private boolean isInContentArea(TextPosition tp, float pageW, float pageH,
                                    PdfContentArea areaOverride) {
        float x = tp.getXDirAdj();
        // 用字符垂直中点而非基线，避免基线刚好压在边界上时被错判
        float yCenter = tp.getYDirAdj() - tp.getHeightDir() / 2f;

        if (areaOverride != null && !areaOverride.isEmpty()) {
            return areaOverride.accepts(x, yCenter, pageW, pageH);
        }
        float topPt    = marginsAreRatios ? marginTop    * pageH : marginTop;
        float bottomPt = marginsAreRatios ? marginBottom * pageH : marginBottom;
        float leftPt   = marginsAreRatios ? marginLeft   * pageW : marginLeft;
        float rightPt  = marginsAreRatios ? marginRight  * pageW : marginRight;
        if (topPt == 0 && bottomPt == 0 && leftPt == 0 && rightPt == 0) return true;
        return x >= leftPt && x <= pageW - rightPt
                && yCenter >= topPt && yCenter <= pageH - bottomPt;
    }

    private BufferedImage cropGlyph(BufferedImage page, TextPosition tp) {
        float w = tp.getWidthDirAdj();
        float h = tp.getHeightDir();
        if (w <= 0 || h <= 0) return null;

        float xLeft = tp.getXDirAdj();
        float yTop = tp.getYDirAdj() - h;

        int px = Math.round(xLeft * scale) - CROP_PADDING_PX;
        int py = Math.round(yTop * scale) - CROP_PADDING_PX;
        int pw = Math.round(w * scale) + 2 * CROP_PADDING_PX;
        int ph = Math.round(h * scale) + 2 * CROP_PADDING_PX;

        int pageW = page.getWidth();
        int pageH = page.getHeight();
        if (px < 0) { pw += px; px = 0; }
        if (py < 0) { ph += py; py = 0; }
        if (px + pw > pageW) pw = pageW - px;
        if (py + ph > pageH) ph = pageH - py;
        if (pw <= 0 || ph <= 0) return null;

        BufferedImage sub = page.getSubimage(px, py, pw, ph);
        BufferedImage copy = new BufferedImage(pw, ph, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(sub, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static boolean isAllWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    private static float clampNonNegative(float v) {
        return v < 0 ? 0 : v;
    }

    private static double clampRatio(double v) {
        if (v < 0) return 0;
        if (v > 0.5) return 0.5; // 单边不超过 50%，避免把整页都裁掉
        return v;
    }
}
