package com.imagecompare.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 版心手动框选器（多矩形版）
 *
 * 功能：
 *  - 鼠标拖拽 → 画一个新矩形（再次拖拽 = 增加，不会覆盖）
 *  - 单击已有矩形 → 选中（8 个调节句柄出现）
 *  - 拖句柄 → 改大小；拖矩形内部 → 整体移动
 *  - Delete 键 / 「删除选中」 → 移除选中矩形；「清除全部」 → 移除全部
 *  - 「复用上一份」（当且仅当传入 preFill 时启用）→ 用上一份 PDF 的版心替换当前
 *  - 「确定」→ 自动写 sidecar 文件（同目录 {@code <pdf-no-ext>.contentrect.json}），下次自动加载
 *  - 「&lt;」「&gt;」翻页预览，矩形跨页生效
 *
 * 入口：{@link #pick(File)} / {@link #pick(File, PdfContentArea)} / {@link #pick(File, PdfContentArea, boolean)}
 */
public final class PdfContentAreaPicker {

    private static final int MAX_DISPLAY_WIDTH = 900;
    private static final int MAX_DISPLAY_HEIGHT = 1100;

    private PdfContentAreaPicker() {}

    public static PdfContentArea pick(String pdfPath) throws IOException {
        return pick(new File(pdfPath), null, true);
    }

    public static PdfContentArea pick(File pdfFile) throws IOException {
        return pick(pdfFile, null, true);
    }

    /**
     * @param preFill  预填充矩形（来自上一份 PDF 的版心，用于"复用上一份"按钮）；可为 null
     * @param autoLoad 是否自动加载 sidecar；preFill 不为 null 时 sidecar 不参与初始填充，但仍可点击复用按钮重置
     */
    public static PdfContentArea pick(File pdfFile, PdfContentArea preFill, boolean autoLoad)
            throws IOException {

        // 决定初始 area：preFill 优先 > sidecar > 空
        PdfContentArea initial = null;
        File sidecar = PdfContentArea.sidecarFile(pdfFile);
        if (preFill != null && !preFill.isEmpty()) {
            initial = preFill;
        } else if (autoLoad) {
            initial = PdfContentArea.tryLoadFrom(sidecar);
        }
        if (initial == null) initial = PdfContentArea.empty();

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            if (doc.getNumberOfPages() == 0) {
                throw new IOException("PDF 没有任何页面: " + pdfFile);
            }
            PDPage firstPage = doc.getPage(0);
            PDRectangle cb = firstPage.getCropBox();
            float pageWidthPt = cb.getWidth();
            float pageHeightPt = cb.getHeight();

            float scale = Math.min(MAX_DISPLAY_WIDTH / pageWidthPt,
                                   MAX_DISPLAY_HEIGHT / pageHeightPt);
            if (scale > 3f) scale = 3f;
            if (scale < 0.5f) scale = 0.5f;

            PDFRenderer renderer = new PDFRenderer(doc);
            PickerPanel panel = new PickerPanel(
                    renderer, doc.getNumberOfPages(), scale,
                    pageWidthPt, pageHeightPt,
                    initial, preFill,
                    sidecar);

            JDialog dialog = new JDialog((Frame) null,
                    "选择正文版心 - " + pdfFile.getName(), true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setContentPane(panel);
            panel.setOwnerDialog(dialog);
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);

            return panel.getResultArea();
        }
    }

    // =====================================================
    //   PickerPanel
    // =====================================================

    private static final class PickerPanel extends JPanel {

        // 句柄索引（8 个）：
        //   0 NW  1 N  2 NE
        //   7 W       3 E
        //   6 SW  5 S 4 SE
        private static final int HANDLE_HIT_RADIUS = 7;
        private static final int HANDLE_VISUAL_SIZE = 9;
        private static final int MIN_RECT_SIZE = 6; // 像素

        private static final Color RECT_FILL          = new Color(0, 120, 215, 50);
        private static final Color RECT_FILL_SELECTED = new Color(0, 120, 215, 80);
        private static final Color RECT_LINE          = new Color(0, 120, 215);
        private static final Color RECT_LINE_SELECTED = new Color(255, 140, 0);
        private static final Color DRAFT_LINE         = new Color(0, 120, 215);

        private enum DragMode { NONE, DRAWING, MOVING, RESIZING }

        private final PDFRenderer renderer;
        private final int pageCount;
        private final float renderScale; // px / pt
        private final float pageWidthPt;
        private final float pageHeightPt;
        private final PdfContentArea preFill;       // 可为 null
        private final File sidecarFile;

        private int currentPage = 0;
        private BufferedImage currentImage;

        // 矩形以"显示像素"为单位维护，在 onOk 时统一换算成 PDF point margin
        private final List<Rectangle> rects = new ArrayList<>();
        private int selectedIdx = -1;

        private DragMode dragMode = DragMode.NONE;
        private Point dragStart;
        private Rectangle preDragRect;   // MOVING/RESIZING 前的快照
        private int activeHandle = -1;   // RESIZING 时拖的是哪个句柄
        private Rectangle draftRect;     // DRAWING 中的临时矩形

        private final PageCanvas canvas;
        private final JLabel pageLabel;
        private final JLabel statusLabel;
        private final JButton reuseBtn;

        private JDialog ownerDialog;
        private PdfContentArea resultArea;

        PickerPanel(PDFRenderer renderer, int pageCount, float renderScale,
                    float pageWidthPt, float pageHeightPt,
                    PdfContentArea initial, PdfContentArea preFill,
                    File sidecarFile) {
            super(new BorderLayout(8, 8));
            this.renderer = renderer;
            this.pageCount = pageCount;
            this.renderScale = renderScale;
            this.pageWidthPt = pageWidthPt;
            this.pageHeightPt = pageHeightPt;
            this.preFill = preFill;
            this.sidecarFile = sidecarFile;

            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            // ── 顶部：翻页 ──
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            JButton prev = new JButton("<");
            JButton next = new JButton(">");
            pageLabel = new JLabel();
            top.add(prev);
            top.add(pageLabel);
            top.add(next);
            prev.addActionListener(e -> setPage(currentPage - 1));
            next.addActionListener(e -> setPage(currentPage + 1));

            // ── 中部：画布（可滚动）──
            canvas = new PageCanvas();
            JScrollPane scroll = new JScrollPane(canvas);
            scroll.setPreferredSize(new Dimension(
                    Math.min(MAX_DISPLAY_WIDTH + 30, Math.round(pageWidthPt * renderScale) + 30),
                    Math.min(MAX_DISPLAY_HEIGHT + 30, Math.round(pageHeightPt * renderScale) + 30)));

            // ── 底部：状态 + 操作 ──
            statusLabel = new JLabel(" ");
            statusLabel.setForeground(new Color(80, 80, 80));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            JButton clearAll = new JButton("清除全部");
            JButton delSel   = new JButton("删除选中");
            reuseBtn = new JButton("复用上一份");
            JButton cancel   = new JButton("取消");
            JButton ok       = new JButton("确定");
            buttons.add(reuseBtn);
            buttons.add(delSel);
            buttons.add(clearAll);
            buttons.add(cancel);
            buttons.add(ok);

            reuseBtn.setEnabled(preFill != null && !preFill.isEmpty());
            reuseBtn.addActionListener(e -> reuseFromPreFill());
            delSel.addActionListener(e -> deleteSelected());
            clearAll.addActionListener(e -> { rects.clear(); selectedIdx = -1; refresh(); });
            cancel.addActionListener(e -> { resultArea = null; close(); });
            ok.addActionListener(e -> onOk());

            JPanel south = new JPanel(new BorderLayout(6, 6));
            south.add(statusLabel, BorderLayout.CENTER);
            south.add(buttons, BorderLayout.EAST);

            add(top, BorderLayout.NORTH);
            add(scroll, BorderLayout.CENTER);
            add(south, BorderLayout.SOUTH);

            // 设上 Delete 快捷键
            canvas.setFocusable(true);
            canvas.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_DELETE
                            || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                        deleteSelected();
                    }
                }
            });

            setPage(0);
            // 把初始 rects 从 PdfContentArea 转回像素坐标
            seedFromArea(initial);
            refresh();
        }

        void setOwnerDialog(JDialog dialog) { this.ownerDialog = dialog; }
        PdfContentArea getResultArea() { return resultArea; }

        private void close() {
            if (ownerDialog != null) ownerDialog.dispose();
        }

        // ──────── 翻页 ────────
        private void setPage(int idx) {
            if (idx < 0 || idx >= pageCount) return;
            try {
                currentImage = renderer.renderImage(idx, renderScale, ImageType.RGB);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "渲染第 " + (idx + 1) + " 页失败: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            currentPage = idx;
            pageLabel.setText(String.format("  第 %d / %d 页  ", currentPage + 1, pageCount));
            canvas.revalidate();
            canvas.repaint();
            canvas.requestFocusInWindow();
        }

        // ──────── 操作 ────────
        private void deleteSelected() {
            if (selectedIdx < 0 || selectedIdx >= rects.size()) return;
            rects.remove(selectedIdx);
            selectedIdx = -1;
            refresh();
        }

        private void reuseFromPreFill() {
            if (preFill == null || preFill.isEmpty()) return;
            rects.clear();
            selectedIdx = -1;
            for (PdfContentRect r : preFill.rects()) {
                Rectangle px = rectFromPdfRect(r);
                if (px != null) rects.add(px);
            }
            refresh();
        }

        private void seedFromArea(PdfContentArea initial) {
            if (initial == null || initial.isEmpty()) return;
            for (PdfContentRect r : initial.rects()) {
                Rectangle px = rectFromPdfRect(r);
                if (px != null) rects.add(px);
            }
        }

        private Rectangle rectFromPdfRect(PdfContentRect r) {
            int imgW = Math.round(pageWidthPt * renderScale);
            int imgH = Math.round(pageHeightPt * renderScale);
            int x = Math.round(r.leftPt * renderScale);
            int y = Math.round(r.topPt * renderScale);
            int w = imgW - x - Math.round(r.rightPt * renderScale);
            int h = imgH - y - Math.round(r.bottomPt * renderScale);
            if (w < MIN_RECT_SIZE || h < MIN_RECT_SIZE) return null;
            return new Rectangle(x, y, w, h);
        }

        private void onOk() {
            if (rects.isEmpty()) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "你还没画版心矩形，确认要按【整页对比】处理吗？",
                        "确认", JOptionPane.YES_NO_OPTION);
                if (choice != JOptionPane.YES_OPTION) return;
                resultArea = PdfContentArea.empty();
                close();
                return;
            }
            int imgW = currentImage.getWidth();
            int imgH = currentImage.getHeight();
            List<PdfContentRect> pdfRects = new ArrayList<>(rects.size());
            for (Rectangle r : rects) {
                float leftPt   = r.x / renderScale;
                float topPt    = r.y / renderScale;
                float rightPt  = (imgW - r.x - r.width)  / renderScale;
                float bottomPt = (imgH - r.y - r.height) / renderScale;
                pdfRects.add(new PdfContentRect(topPt, bottomPt, leftPt, rightPt));
            }
            resultArea = new PdfContentArea(pdfRects);
            // 落盘 sidecar（失败静默）
            if (sidecarFile != null) {
                resultArea.trySaveTo(sidecarFile);
            }
            close();
        }

        private void refresh() {
            updateStatus();
            canvas.repaint();
        }

        private void updateStatus() {
            if (rects.isEmpty()) {
                statusLabel.setText(" 鼠标拖拽框选正文区域；可画多个矩形覆盖多栏排版 ");
            } else if (selectedIdx >= 0) {
                Rectangle r = rects.get(selectedIdx);
                statusLabel.setText(String.format(
                        " 共 %d 个矩形；选中 #%d：%d×%d 像素 (%.0f×%.0f pt)",
                        rects.size(), selectedIdx + 1, r.width, r.height,
                        r.width / renderScale, r.height / renderScale));
            } else {
                statusLabel.setText(String.format(" 共 %d 个矩形（点击矩形可选中调整）", rects.size()));
            }
        }

        // ────────  几何辅助  ────────
        private Point[] handleCenters(Rectangle r) {
            int x1 = r.x, y1 = r.y;
            int x2 = r.x + r.width, y2 = r.y + r.height;
            int xc = r.x + r.width / 2, yc = r.y + r.height / 2;
            return new Point[] {
                new Point(x1, y1), new Point(xc, y1), new Point(x2, y1),
                new Point(x2, yc),
                new Point(x2, y2), new Point(xc, y2), new Point(x1, y2),
                new Point(x1, yc),
            };
        }

        private int handleAt(Point p, Rectangle r) {
            Point[] hs = handleCenters(r);
            for (int i = 0; i < hs.length; i++) {
                if (Math.abs(p.x - hs[i].x) <= HANDLE_HIT_RADIUS
                        && Math.abs(p.y - hs[i].y) <= HANDLE_HIT_RADIUS) return i;
            }
            return -1;
        }

        private int rectAt(Point p) {
            // 后画的优先（叠在上面）
            for (int i = rects.size() - 1; i >= 0; i--) {
                if (rects.get(i).contains(p)) return i;
            }
            return -1;
        }

        /** 根据句柄方向 + delta 调整矩形 */
        private Rectangle resizeByHandle(Rectangle pre, int handle, int dx, int dy) {
            int l = pre.x, t = pre.y, r = pre.x + pre.width, b = pre.y + pre.height;
            switch (handle) {
                case 0 -> { l += dx; t += dy; }
                case 1 -> { t += dy; }
                case 2 -> { r += dx; t += dy; }
                case 3 -> { r += dx; }
                case 4 -> { r += dx; b += dy; }
                case 5 -> { b += dy; }
                case 6 -> { l += dx; b += dy; }
                case 7 -> { l += dx; }
            }
            int nx = Math.min(l, r);
            int ny = Math.min(t, b);
            int nw = Math.abs(r - l);
            int nh = Math.abs(b - t);
            return clampToImage(new Rectangle(nx, ny, nw, nh));
        }

        private Rectangle clampToImage(Rectangle r) {
            if (currentImage == null) return r;
            int iw = currentImage.getWidth(), ih = currentImage.getHeight();
            int x = Math.max(0, Math.min(iw, r.x));
            int y = Math.max(0, Math.min(ih, r.y));
            int w = Math.max(0, Math.min(iw - x, r.width));
            int h = Math.max(0, Math.min(ih - y, r.height));
            return new Rectangle(x, y, w, h);
        }

        private static int cursorForHandle(int h) {
            return switch (h) {
                case 0 -> Cursor.NW_RESIZE_CURSOR;
                case 1 -> Cursor.N_RESIZE_CURSOR;
                case 2 -> Cursor.NE_RESIZE_CURSOR;
                case 3 -> Cursor.E_RESIZE_CURSOR;
                case 4 -> Cursor.SE_RESIZE_CURSOR;
                case 5 -> Cursor.S_RESIZE_CURSOR;
                case 6 -> Cursor.SW_RESIZE_CURSOR;
                case 7 -> Cursor.W_RESIZE_CURSOR;
                default -> Cursor.CROSSHAIR_CURSOR;
            };
        }

        // ──────── 画布 ────────
        private final class PageCanvas extends JComponent {

            PageCanvas() {
                setOpaque(true);
                setBackground(new Color(50, 50, 55));
                setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

                MouseAdapter mouseHandler = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        if (currentImage == null) return;
                        Point p = clampPoint(e.getPoint());
                        requestFocusInWindow();

                        // 1) 选中矩形上的句柄优先
                        if (selectedIdx >= 0) {
                            int h = handleAt(p, rects.get(selectedIdx));
                            if (h >= 0) {
                                dragMode = DragMode.RESIZING;
                                activeHandle = h;
                                preDragRect = new Rectangle(rects.get(selectedIdx));
                                dragStart = p;
                                return;
                            }
                        }
                        // 2) 点中某个矩形内部 → 选中并准备移动
                        int hit = rectAt(p);
                        if (hit >= 0) {
                            selectedIdx = hit;
                            dragMode = DragMode.MOVING;
                            preDragRect = new Rectangle(rects.get(hit));
                            dragStart = p;
                            refresh();
                            return;
                        }
                        // 3) 在空白处 → 新画
                        selectedIdx = -1;
                        dragMode = DragMode.DRAWING;
                        dragStart = p;
                        draftRect = new Rectangle(p.x, p.y, 0, 0);
                        refresh();
                    }

                    @Override public void mouseReleased(MouseEvent e) {
                        if (dragMode == DragMode.DRAWING && draftRect != null) {
                            if (draftRect.width >= MIN_RECT_SIZE
                                    && draftRect.height >= MIN_RECT_SIZE) {
                                rects.add(new Rectangle(draftRect));
                                selectedIdx = rects.size() - 1;
                            }
                        }
                        dragMode = DragMode.NONE;
                        activeHandle = -1;
                        preDragRect = null;
                        draftRect = null;
                        dragStart = null;
                        refresh();
                    }
                };
                addMouseListener(mouseHandler);

                addMouseMotionListener(new MouseMotionAdapter() {
                    @Override public void mouseDragged(MouseEvent e) {
                        if (dragMode == DragMode.NONE || dragStart == null) return;
                        Point cur = clampPoint(e.getPoint());
                        int dx = cur.x - dragStart.x;
                        int dy = cur.y - dragStart.y;

                        switch (dragMode) {
                            case DRAWING -> {
                                int x = Math.min(dragStart.x, cur.x);
                                int y = Math.min(dragStart.y, cur.y);
                                int w = Math.abs(dragStart.x - cur.x);
                                int h = Math.abs(dragStart.y - cur.y);
                                draftRect = clampToImage(new Rectangle(x, y, w, h));
                            }
                            case MOVING -> {
                                Rectangle r = rects.get(selectedIdx);
                                Rectangle moved = new Rectangle(preDragRect.x + dx, preDragRect.y + dy,
                                        preDragRect.width, preDragRect.height);
                                r.setBounds(clampToImage(moved));
                            }
                            case RESIZING -> {
                                Rectangle r = rects.get(selectedIdx);
                                Rectangle nr = resizeByHandle(preDragRect, activeHandle, dx, dy);
                                r.setBounds(nr);
                            }
                        }
                        refresh();
                    }

                    @Override public void mouseMoved(MouseEvent e) {
                        if (currentImage == null) return;
                        Point p = e.getPoint();
                        int cursor = Cursor.CROSSHAIR_CURSOR;
                        if (selectedIdx >= 0) {
                            int h = handleAt(p, rects.get(selectedIdx));
                            if (h >= 0) cursor = cursorForHandle(h);
                            else if (rects.get(selectedIdx).contains(p)) cursor = Cursor.MOVE_CURSOR;
                            else if (rectAt(p) >= 0) cursor = Cursor.HAND_CURSOR;
                        } else if (rectAt(p) >= 0) {
                            cursor = Cursor.HAND_CURSOR;
                        }
                        setCursor(Cursor.getPredefinedCursor(cursor));
                    }
                });
            }

            private Point clampPoint(Point p) {
                if (currentImage == null) return p;
                int x = Math.max(0, Math.min(currentImage.getWidth() - 1,  p.x));
                int y = Math.max(0, Math.min(currentImage.getHeight() - 1, p.y));
                return new Point(x, y);
            }

            @Override public Dimension getPreferredSize() {
                if (currentImage == null) return new Dimension(400, 400);
                return new Dimension(currentImage.getWidth(), currentImage.getHeight());
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setColor(getBackground());
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    if (currentImage != null) g2.drawImage(currentImage, 0, 0, null);

                    for (int i = 0; i < rects.size(); i++) {
                        boolean selected = (i == selectedIdx);
                        drawRect(g2, rects.get(i), selected, false);
                        if (selected) drawHandles(g2, rects.get(i));
                    }
                    if (draftRect != null) drawRect(g2, draftRect, false, true);
                } finally {
                    g2.dispose();
                }
            }

            private void drawRect(Graphics2D g2, Rectangle r, boolean selected, boolean draft) {
                g2.setColor(selected ? RECT_FILL_SELECTED : RECT_FILL);
                g2.fillRect(r.x, r.y, r.width, r.height);
                g2.setColor(selected ? RECT_LINE_SELECTED : DRAFT_LINE);
                if (draft) {
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER,
                            10f, new float[]{6f, 4f}, 0f));
                } else {
                    g2.setStroke(new BasicStroke(selected ? 2.4f : 1.8f));
                }
                g2.drawRect(r.x, r.y, r.width, r.height);
            }

            private void drawHandles(Graphics2D g2, Rectangle r) {
                Point[] hs = handleCenters(r);
                int s = HANDLE_VISUAL_SIZE;
                for (Point p : hs) {
                    g2.setColor(Color.WHITE);
                    g2.fillRect(p.x - s/2, p.y - s/2, s, s);
                    g2.setColor(RECT_LINE_SELECTED);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawRect(p.x - s/2, p.y - s/2, s, s);
                }
            }
        }
    }
}
