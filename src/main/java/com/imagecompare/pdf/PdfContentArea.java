package com.imagecompare.pdf;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个 PDF 的版心定义：N 个 {@link PdfContentRect} 的并集
 *
 * 多矩形对多栏排版（双栏 / 三栏 / 大边距 + 间栏等）特别有用 —— 只要字形落在任一矩形内
 * 即视为正文。空 area（0 个矩形）等同于"不过滤、整页对比"。
 *
 * 支持序列化到一个 sidecar JSON 文件（默认放在 PDF 同目录下，
 * 文件名 {@code <pdf-no-ext>.contentrect.json}），方便下次自动加载。
 */
public final class PdfContentArea {

    /** sidecar 文件版本号 */
    private static final int CURRENT_FORMAT_VERSION = 1;

    /** 解析单条矩形条目的正则 */
    private static final Pattern RECT_PATTERN = Pattern.compile(
            "\\{[^{}]*\"top\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)" +
            "[^{}]*\"bottom\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)" +
            "[^{}]*\"left\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)" +
            "[^{}]*\"right\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)" +
            "[^{}]*\\}");

    private final List<PdfContentRect> rects;

    public PdfContentArea(List<PdfContentRect> rects) {
        this.rects = rects == null ? Collections.emptyList()
                : List.copyOf(rects);
    }

    public static PdfContentArea empty() {
        return new PdfContentArea(Collections.emptyList());
    }

    public static PdfContentArea single(PdfContentRect r) {
        return r == null ? empty() : new PdfContentArea(Collections.singletonList(r));
    }

    public static PdfContentArea of(PdfContentRect... rs) {
        if (rs == null || rs.length == 0) return empty();
        return new PdfContentArea(Arrays.asList(rs));
    }

    public List<PdfContentRect> rects() { return rects; }
    public boolean isEmpty() { return rects.isEmpty(); }
    public int size() { return rects.size(); }

    /**
     * 给定一个字形 (x, yCenter)（PDF point，origin 左上）是否落在版心范围内。
     * 多矩形是"并集"语义：只要被任一矩形包含就接受。
     */
    public boolean accepts(float x, float yCenter, float pageW, float pageH) {
        if (rects.isEmpty()) return true;
        for (PdfContentRect r : rects) {
            if (r.accepts(x, yCenter, pageW, pageH)) return true;
        }
        return false;
    }

    /** 与给定 PDF 配对的 sidecar 文件路径，例如 {@code a.pdf → a.contentrect.json} */
    public static File sidecarFile(File pdfFile) {
        String name = pdfFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        File parent = pdfFile.getParentFile();
        String fileName = name + ".contentrect.json";
        return parent == null ? new File(fileName) : new File(parent, fileName);
    }

    /**
     * 写到 sidecar。失败抛 IOException；调用方可用 {@link #trySaveTo(File)} 做静默版本。
     */
    public void saveTo(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(CURRENT_FORMAT_VERSION).append(",\n");
        sb.append("  \"unit\": \"pt\",\n");
        sb.append("  \"rects\": [\n");
        for (int i = 0; i < rects.size(); i++) {
            PdfContentRect r = rects.get(i);
            sb.append(String.format(
                    "    { \"top\": %.2f, \"bottom\": %.2f, \"left\": %.2f, \"right\": %.2f }",
                    r.topPt, r.bottomPt, r.leftPt, r.rightPt));
            if (i < rects.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    /** 静默落盘版：失败返回 false（不抛异常），用于交互流程关闭时自动保存 */
    public boolean trySaveTo(File file) {
        try {
            saveTo(file);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从 sidecar JSON 加载。文件不存在或为空时返回 {@link #empty()}。
     * 解析容错：忽略无法识别的字段，只抓 rects 列表内的 top/bottom/left/right。
     */
    public static PdfContentArea loadFrom(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) return empty();
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        List<PdfContentRect> list = new ArrayList<>();
        Matcher m = RECT_PATTERN.matcher(content);
        while (m.find()) {
            try {
                list.add(new PdfContentRect(
                        Float.parseFloat(m.group(1)),
                        Float.parseFloat(m.group(2)),
                        Float.parseFloat(m.group(3)),
                        Float.parseFloat(m.group(4))));
            } catch (NumberFormatException ignored) { /* 跳过坏数据 */ }
        }
        return new PdfContentArea(list);
    }

    public static PdfContentArea tryLoadFrom(File file) {
        try {
            return loadFrom(file);
        } catch (IOException e) {
            return empty();
        }
    }

    @Override
    public String toString() {
        if (rects.isEmpty()) return "PdfContentArea(empty / 整页)";
        StringBuilder sb = new StringBuilder("PdfContentArea(" + rects.size() + " 个矩形:");
        for (PdfContentRect r : rects) {
            sb.append("\n  ").append(r);
        }
        return sb.append(")").toString();
    }
}
