package app.cadlook.core;

import java.util.ArrayList;
import java.util.List;

public final class CadDocument {
    public static final class Line {
        public final double x1, y1, x2, y2;
        public final int rgb;

        public Line(double x1, double y1, double x2, double y2, int rgb) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.rgb = rgb;
        }
    }

    public static final class Circle {
        public final double cx, cy, r;
        public final int rgb;

        public Circle(double cx, double cy, double r, int rgb) {
            this.cx = cx;
            this.cy = cy;
            this.r = r;
            this.rgb = rgb;
        }
    }

    /** sweepDeg 为正是逆时针，为负是顺时针。 */
    public static final class Arc {
        public final double cx, cy, r, startDeg, sweepDeg;
        public final int rgb;

        public Arc(double cx, double cy, double r, double startDeg, double sweepDeg, int rgb) {
            this.cx = cx;
            this.cy = cy;
            this.r = r;
            this.startDeg = startDeg;
            this.sweepDeg = sweepDeg;
            this.rgb = rgb;
        }
    }

    public static final class Text {
        public final double x, y, height, rotationDeg;
        public final String value;
        public final int rgb;

        public Text(double x, double y, double height, double rotationDeg, String value, int rgb) {
            this.x = x;
            this.y = y;
            this.height = height;
            this.rotationDeg = rotationDeg;
            this.value = value;
            this.rgb = rgb;
        }
    }

    public final List<Line> lines;
    public final List<Circle> circles;
    public final List<Arc> arcs;
    public final List<Text> texts;
    public final double minX, minY, maxX, maxY;

    public CadDocument(List<Line> lines, List<Circle> circles, List<Arc> arcs, List<Text> texts,
                        double minX, double minY, double maxX, double maxY) {
        this.lines = lines;
        this.circles = circles;
        this.arcs = arcs;
        this.texts = texts;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public int entityCount() {
        return lines.size() + circles.size() + arcs.size() + texts.size();
    }

    public boolean passes(double startDeg, double sweepDeg, double targetDeg) {
        double sweep = Math.abs(sweepDeg);
        if (sweep >= 360 - 1e-6) {
            return true;
        }
        double delta = norm(sweepDeg >= 0 ? targetDeg - startDeg : startDeg - targetDeg);
        return delta <= sweep + 1e-4;
    }

    private static double norm(double deg) {
        double v = deg % 360.0;
        if (v < 0) {
            v += 360.0;
        }
        return v;
    }

    static void includeArc(Bounds bounds, Arc arc) {
        includePoint(bounds, arc.cx + arc.r * Math.cos(Math.toRadians(arc.startDeg)),
                arc.cy + arc.r * Math.sin(Math.toRadians(arc.startDeg)));
        includePoint(bounds, arc.cx + arc.r * Math.cos(Math.toRadians(arc.startDeg + arc.sweepDeg)),
                arc.cy + arc.r * Math.sin(Math.toRadians(arc.startDeg + arc.sweepDeg)));
        double[] cardinal = {0, 90, 180, 270};
        for (double angle : cardinal) {
            if (boundsOwnerPasses(arc, angle)) {
                includePoint(bounds, arc.cx + arc.r * Math.cos(Math.toRadians(angle)),
                        arc.cy + arc.r * Math.sin(Math.toRadians(angle)));
            }
        }
    }

    private static boolean boundsOwnerPasses(Arc arc, double target) {
        double sweep = Math.abs(arc.sweepDeg);
        if (sweep >= 360 - 1e-6) {
            return true;
        }
        double delta = norm(arc.sweepDeg >= 0 ? target - arc.startDeg : arc.startDeg - target);
        return delta <= sweep + 1e-4;
    }

    static void includePoint(Bounds bounds, double x, double y) {
        if (!bounds.any) {
            bounds.minX = bounds.maxX = x;
            bounds.minY = bounds.maxY = y;
            bounds.any = true;
            return;
        }
        bounds.minX = Math.min(bounds.minX, x);
        bounds.minY = Math.min(bounds.minY, y);
        bounds.maxX = Math.max(bounds.maxX, x);
        bounds.maxY = Math.max(bounds.maxY, y);
    }

    static final class Bounds {
        boolean any;
        double minX, minY, maxX, maxY;
    }

    public static CadDocument fromParts(List<Line> lines, List<Circle> circles, List<Arc> arcs, List<Text> texts) {
        Bounds bounds = new Bounds();
        for (Line line : lines) {
            includePoint(bounds, line.x1, line.y1);
            includePoint(bounds, line.x2, line.y2);
        }
        for (Circle circle : circles) {
            includePoint(bounds, circle.cx - circle.r, circle.cy - circle.r);
            includePoint(bounds, circle.cx + circle.r, circle.cy + circle.r);
        }
        for (Arc arc : arcs) {
            includeArc(bounds, arc);
        }
        for (Text text : texts) {
            includePoint(bounds, text.x, text.y);
        }
        if (!bounds.any) {
            bounds.minX = 0;
            bounds.minY = 0;
            bounds.maxX = 1;
            bounds.maxY = 1;
        }
        if (bounds.maxX - bounds.minX < 1e-9) {
            bounds.maxX = bounds.minX + 1;
        }
        if (bounds.maxY - bounds.minY < 1e-9) {
            bounds.maxY = bounds.minY + 1;
        }
        return new CadDocument(lines, circles, arcs, texts,
                bounds.minX, bounds.minY, bounds.maxX, bounds.maxY);
    }

    public static List<Line> emptyLines() {
        return new ArrayList<Line>();
    }
}
