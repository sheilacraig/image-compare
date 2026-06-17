package com.imagecompare.pdf;

import java.awt.image.BufferedImage;

/**
 * PDF 中的单个字形实例（一个 TextPosition 对应一个 PdfGlyph）
 */
public final class PdfGlyph {

    /** 0-based 页号 */
    public final int pageIndex;
    /** 全文阅读顺序下的序号（跨页连续） */
    public final int sequenceIndex;
    /** PDFBox 提取出来的 Unicode（可能为 null/空/私有区脏码） */
    public final String unicode;
    /** 从渲染页面上裁切的字形位图（含少量 padding，后续由 ImagePreprocessor 精修） */
    public final BufferedImage image;
    /** 在 PDF 显示坐标下的左上 X（用于报告位置） */
    public final float pdfX;
    /** 在 PDF 显示坐标下的基线 Y */
    public final float pdfY;

    public PdfGlyph(int pageIndex, int sequenceIndex, String unicode,
                    BufferedImage image, float pdfX, float pdfY) {
        this.pageIndex = pageIndex;
        this.sequenceIndex = sequenceIndex;
        this.unicode = unicode == null ? "" : unicode;
        this.image = image;
        this.pdfX = pdfX;
        this.pdfY = pdfY;
    }

    /** 当前 Unicode 是否"可信" —— 非空、非控制符、非 PUA 私有区 */
    public boolean isUnicodeTrustworthy() {
        if (unicode.isEmpty()) return false;
        for (int i = 0; i < unicode.length(); ) {
            int cp = unicode.codePointAt(i);
            if (cp < 0x20) return false;                               // 控制符
            if (cp >= 0xE000 && cp <= 0xF8FF) return false;            // 私有区
            if (cp >= 0xF0000 && cp <= 0x10FFFD) return false;         // 补充私有区
            i += Character.charCount(cp);
        }
        return true;
    }

    /** 给报告/调试用的简短描述 */
    public String describe() {
        String repr = unicode.isEmpty() ? "∅" : unicode;
        return String.format("p%d#%d '%s'", pageIndex + 1, sequenceIndex, repr);
    }
}
