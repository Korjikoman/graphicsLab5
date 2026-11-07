package ru.graphics.lab5;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.Arrays;

/** 256 уровней яркости: исходное изображение — линия, результат — столбцы. */
public final class HistogramPanel extends JPanel {
    private long[] before = new long[256];
    private long[] after = new long[256];
    private boolean logarithmic;
    private static final int LEFT = 62, RIGHT = 18, TOP = 24, BOTTOM = 34;

    public HistogramPanel() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(600, 160));
        setMinimumSize(new Dimension(240, 125));
        addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int width = getWidth() - LEFT - RIGHT;
                if (width <= 0 || e.getX() < LEFT || e.getX() >= getWidth() - RIGHT) {
                    setToolTipText(null);
                    return;
                }
                int level = Math.min(255, (e.getX() - LEFT) * 256 / width);
                setToolTipText("Яркость " + level + ": исходное — " + before[level]
                        + ", результат — " + after[level] + " пикселей");
            }
        });
    }

    public void setHistograms(long[] before, long[] after) {
        this.before = before.clone();
        this.after = after.clone();
        repaint();
    }

    public void setLogarithmic(boolean logarithmic) {
        this.logarithmic = logarithmic;
        repaint();
    }

    private double height(long count) { return logarithmic ? Math.log1p(count) : count; }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        int w = getWidth() - LEFT - RIGHT, h = getHeight() - TOP - BOTTOM;
        if (w < 1 || h < 1) { g.dispose(); return; }
        long maxCount = Math.max(Arrays.stream(before).max().orElse(0), Arrays.stream(after).max().orElse(0));
        double max = Math.max(1, height(maxCount));
        g.setColor(new Color(231, 235, 239));
        for (int i = 0; i <= 4; i++) {
            int y = TOP + i * h / 4;
            g.drawLine(LEFT, y, LEFT + w, y);
        }
        // Одинаковая вертикальная шкала у обеих гистограмм позволяет сравнивать
        // реальные количества. В log-режиме log(1+n) определён и при n=0.
        g.setColor(new Color(27, 157, 137, 180));
        for (int i = 0; i < 256; i++) {
            int x0 = LEFT + i * w / 256, x1 = LEFT + (i + 1) * w / 256;
            int bar = (int) Math.round(height(after[i]) / max * h);
            g.fillRect(x0, TOP + h - bar, Math.max(1, x1 - x0), bar);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(103, 111, 127));
        g.setStroke(new BasicStroke(1.2f));
        Path2D path = new Path2D.Double();
        for (int i = 0; i < 256; i++) {
            double x = LEFT + (i + 0.5) * w / 256.0;
            double y = TOP + h - height(before[i]) / max * h;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        g.draw(path);
        g.setColor(new Color(70, 78, 91));
        g.drawLine(LEFT, TOP, LEFT, TOP + h);
        g.drawLine(LEFT, TOP + h, LEFT + w, TOP + h);
        g.drawString("Пиксели" + (logarithmic ? " (log)" : ""), LEFT, 16);
        String maxLabel = maxCount >= 10000 ? String.format("%.0fk", maxCount / 1000.0)
                : Long.toString(maxCount);
        g.drawString(maxLabel, Math.max(2, LEFT - 8 - g.getFontMetrics().stringWidth(maxLabel)), TOP + 5);
        g.drawString("0", LEFT - 16, TOP + h + 4);
        for (int level : new int[]{0, 64, 128, 192, 255}) {
            int x = LEFT + level * w / 255;
            g.drawLine(x, TOP + h, x, TOP + h + 4);
            String text = Integer.toString(level);
            g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, TOP + h + 19);
        }
        g.drawString("Яркость", LEFT + w / 2 - 22, getHeight() - 2);
        g.dispose();
    }
}
