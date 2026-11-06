package app.cadlook.demo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import app.cadlook.core.CadDocument;
import app.cadlook.core.CadDrawer;

public final class Preview {
    private Preview() {
    }

    public static void write(CadDocument document, Path png) throws Exception {
        int width = 900;
        int height = 600;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x1B1B1B));
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double worldW = document.maxX - document.minX;
        double worldH = document.maxY - document.minY;
        final double scale = 0.9 * Math.min((width - 40) / worldW, (height - 40) / worldH);
        final double panX = (width - worldW * scale) / 2 - document.minX * scale;
        final double panY = (height + worldH * scale) / 2 + document.minY * scale;
        CadDrawer.draw(document, new CadDrawer.Pen() {
            @Override
            public void line(double x1, double y1, double x2, double y2, int rgb) {
                g.setColor(new Color(0xFF000000 | rgb, true));
                g.drawLine(sx(x1), sy(y1), sx(x2), sy(y2));
            }

            @Override
            public void text(double x, double y, double height, double rotationDeg, String text, int rgb) {
                float size = (float) (height * scale);
                if (size < 2f) {
                    return;
                }
                g.setColor(new Color(0xFF000000 | rgb, true));
                g.setFont(g.getFont().deriveFont(size));
                g.drawString(text.replace('\n', ' '), sx(x), sy(y));
            }

            private int sx(double x) {
                return (int) Math.round(panX + x * scale);
            }

            private int sy(double y) {
                return (int) Math.round(panY - y * scale);
            }
        }, scale);
        g.dispose();
        int ink = 0;
        int red = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y) & 0xFFFFFF;
                if (pixel != 0x1B1B1B) {
                    ink++;
                }
                int r = (pixel >> 16) & 255;
                int gg = (pixel >> 8) & 255;
                int b = pixel & 255;
                if (r > 180 && gg < 80 && b < 80) {
                    red++;
                }
            }
        }
        if (ink < 50) {
            throw new IllegalStateException("预览几乎是空的");
        }
        if (red < 5) {
            throw new IllegalStateException("预览里没有红色的圆");
        }
        if (png.getParent() != null) {
            Files.createDirectories(png.getParent());
        }
        ImageIO.write(image, "png", png.toFile());
    }
}
