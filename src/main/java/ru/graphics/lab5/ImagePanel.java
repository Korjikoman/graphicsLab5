package ru.graphics.lab5;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/** Просмотр изображения и перевод координат мыши из экранных в пиксельные. */
public final class ImagePanel extends JComponent implements Scrollable {
    private BufferedImage image;
    private Rectangle selection;
    private Point anchor;
    private double zoom; // 0 = вписать в окно; иначе масштаб 1.0 = 100%.
    private boolean selecting;
    private final Consumer<Rectangle> onSelection;

    public ImagePanel(Consumer<Rectangle> onSelection) {
        this.onSelection = onSelection;
        setOpaque(true);
        setBackground(new Color(34, 39, 47));
        setToolTipText(onSelection == null ? "Исходное изображение"
                : "Потяните мышью для выделения фрагмента. Щелчок снимает выделение.");
        if (onSelection != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (!isEnabled() || image == null || !SwingUtilities.isLeftMouseButton(e)) return;
                    Point point = imagePoint(e.getPoint(), false);
                    if (point == null) return;
                    anchor = point;
                    selecting = true;
                    selection = new Rectangle(point.x, point.y, 1, 1);
                    repaint();
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (!selecting) return;
                    Point end = imagePoint(e.getPoint(), true);
                    // +1 включает конечный пиксель: в частности, можно выделить 1x1.
                    selection = new Rectangle(Math.min(anchor.x, end.x), Math.min(anchor.y, end.y),
                            Math.abs(end.x - anchor.x) + 1, Math.abs(end.y - anchor.y) + 1);
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (!selecting || !SwingUtilities.isLeftMouseButton(e)) return;
                    selecting = false;
                    if (selection.width == 1 && selection.height == 1) selection = null;
                    onSelection.accept(selection == null ? null : new Rectangle(selection));
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }
    }

    public void setImage(BufferedImage image) {
        this.image = image;
        revalidate();
        repaint();
    }

    public void setSelection(Rectangle selection) {
        this.selection = selection == null ? null : new Rectangle(selection);
        repaint();
    }

    public void setZoom(double zoom) {
        this.zoom = zoom;
        revalidate();
        repaint();
    }

    private double scale() {
        if (image == null) return 1;
        if (zoom > 0) return zoom;
        return Math.max(0.001, Math.min((getWidth() - 24.0) / image.getWidth(),
                (getHeight() - 24.0) / image.getHeight()));
    }

    private int offsetX(double scale) {
        return Math.max(0, (getWidth() - (int) Math.round(image.getWidth() * scale)) / 2);
    }

    private int offsetY(double scale) {
        return Math.max(0, (getHeight() - (int) Math.round(image.getHeight() * scale)) / 2);
    }

    private Point imagePoint(Point screen, boolean clamp) {
        double scale = scale();
        int x = (int) Math.floor((screen.x - offsetX(scale)) / scale);
        int y = (int) Math.floor((screen.y - offsetY(scale)) / scale);
        if (!clamp && (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight())) return null;
        return new Point(Math.max(0, Math.min(image.getWidth() - 1, x)),
                Math.max(0, Math.min(image.getHeight() - 1, y)));
    }

    @Override public Dimension getPreferredSize() {
        if (image == null || zoom == 0) return new Dimension(360, 400);
        return new Dimension((int) Math.ceil(image.getWidth() * zoom) + 24,
                (int) Math.ceil(image.getHeight() * zoom) + 24);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        if (image == null) {
            g.setColor(new Color(190, 198, 210));
            g.drawString("Откройте изображение", 24, 36);
            g.dispose();
            return;
        }
        double scale = scale();
        int dx = offsetX(scale), dy = offsetY(scale);
        int width = (int) Math.round(image.getWidth() * scale);
        int height = (int) Math.round(image.getHeight() * scale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 1
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, dx, dy, width, height, null);
        if (selection != null) {
            // Рамка — только интерфейс: она никогда не записывается в пиксели и файл.
            int x = dx + (int) Math.round(selection.x * scale);
            int y = dy + (int) Math.round(selection.y * scale);
            int w = Math.max(1, (int) Math.round(selection.width * scale));
            int h = Math.max(1, (int) Math.round(selection.height * scale));
            g.setColor(new Color(0, 0, 0, 110));
            g.fillRect(dx, dy, width, Math.max(0, y - dy));
            g.fillRect(dx, y + h, width, Math.max(0, dy + height - y - h));
            g.fillRect(dx, y, Math.max(0, x - dx), h);
            g.fillRect(x + w, y, Math.max(0, dx + width - x - w), h);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(3));
            g.drawRect(x, y, w, h);
            g.setColor(new Color(18, 173, 153));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10, new float[]{6, 4}, 0));
            g.drawRect(x, y, w, h);
        }
        g.dispose();
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(360, 400); }
    @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
        return Math.max(24, (orientation == SwingConstants.HORIZONTAL ? r.width : r.height) - 24);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return zoom == 0; }
    @Override public boolean getScrollableTracksViewportHeight() { return zoom == 0; }
}
