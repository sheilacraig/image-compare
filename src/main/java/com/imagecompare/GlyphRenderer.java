package com.imagecompare;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 字符渲染器：将单个字符按指定字体渲染为白底黑字 PNG，
 * 作为字形比较的参考图。
 */
public final class GlyphRenderer {

    /** 渲染画布边长（像素）。比预处理归一化尺寸大一些，避免抗锯齿后笔画细节丢失。*/
    private static final int CANVAS_SIZE = 256;

    /** 字号占画布的比例。留一点边距，避免笔画贴边被裁。*/
    private static final float FONT_RATIO = 0.78f;

    private GlyphRenderer() {}

    /**
     * 把字符渲染成临时 PNG 文件，返回路径。调用方负责清理。
     *
     * @param character        要渲染的字符（单个 code point）
     * @param fontResourcePath 字体文件路径（绝对/相对路径或 classpath 下的路径）
     */
    public static Path renderToTempFile(String character, String fontResourcePath)
            throws IOException, FontFormatException {
        BufferedImage image = render(character, fontResourcePath);
        Path tempPath = Files.createTempFile("glyph-ref-", ".png");
        ImageIO.write(image, "png", tempPath.toFile());
        return tempPath;
    }

    private static BufferedImage render(String character, String fontResourcePath)
            throws IOException, FontFormatException {
        Font baseFont;
        File file = new File(fontResourcePath);
        if (file.exists() && file.isFile()) {
            baseFont = Font.createFont(Font.TRUETYPE_FONT, file);
        } else {
            try (InputStream in = GlyphRenderer.class.getClassLoader()
                    .getResourceAsStream(fontResourcePath)) {
                if (in == null) {
                    throw new IOException("Font resource not found on classpath or filesystem: " + fontResourcePath);
                }
                baseFont = Font.createFont(Font.TRUETYPE_FONT, in);
            }
        }
        Font font = baseFont.deriveFont(CANVAS_SIZE * FONT_RATIO);

        BufferedImage image = new BufferedImage(CANVAS_SIZE, CANVAS_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, CANVAS_SIZE, CANVAS_SIZE);

            g.setColor(Color.BLACK);
            g.setFont(font);

            FontMetrics fm = g.getFontMetrics();
            int textWidth = fm.stringWidth(character);
            int x = (CANVAS_SIZE - textWidth) / 2;
            int y = (CANVAS_SIZE - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(character, x, y);
        } finally {
            g.dispose();
        }
        return image;
    }
}
