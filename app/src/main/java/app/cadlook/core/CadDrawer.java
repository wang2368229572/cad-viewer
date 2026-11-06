package app.cadlook.core;

/** 把图纸画成世界坐标里的线段和文字。界面负责换算到屏幕。 */
public final class CadDrawer {
    public interface Pen {
        void line(double x1, double y1, double x2, double y2, int rgb);

        void text(double x, double y, double height, double rotationDeg, String text, int rgb);
    }

    private CadDrawer() {
    }

    public static void draw(CadDocument doc, Pen pen, double pixelsPerUnit) {
        for (CadDocument.Line line : doc.lines) {
            pen.line(line.x1, line.y1, line.x2, line.y2, line.rgb);
        }
        for (CadDocument.Circle circle : doc.circles) {
            int steps = Curves.steps(circle.r, 360, pixelsPerUnit);
            drawArc(pen, circle.cx, circle.cy, circle.r, 0, 360, steps, circle.rgb);
        }
        for (CadDocument.Arc arc : doc.arcs) {
            int steps = Curves.steps(arc.r, arc.sweepDeg, pixelsPerUnit);
            drawArc(pen, arc.cx, arc.cy, arc.r, arc.startDeg, arc.sweepDeg, steps, arc.rgb);
        }
        for (CadDocument.Text text : doc.texts) {
            if (text.value == null || text.value.isEmpty()) {
                continue;
            }
            pen.text(text.x, text.y, text.height, text.rotationDeg, text.value, text.rgb);
        }
    }

    private static void drawArc(final Pen pen, double cx, double cy, double r,
                                double start, double sweep, int steps, final int rgb) {
        Curves.arc(new Curves.Segment() {
            public void line(double x1, double y1, double x2, double y2) {
                pen.line(x1, y1, x2, y2, rgb);
            }
        }, cx, cy, r, start, sweep, steps);
    }
}
