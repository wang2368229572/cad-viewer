package app.cadlook.core;

/** 把圆弧折成短线段。角度沿用 DXF：0 度在 +X，逆时针，Y 朝上。 */
public final class Curves {
    public interface Segment {
        void line(double x1, double y1, double x2, double y2);
    }

    private Curves() {
    }

    public static int steps(double radius, double sweepDeg, double pixelsPerUnit) {
        double sweep = Math.abs(sweepDeg);
        if (sweep < 1e-6) {
            return 1;
        }
        double screenRadius = Math.abs(radius * pixelsPerUnit);
        int count = (int) Math.ceil(screenRadius * Math.toRadians(sweep) / 3.0);
        int floor = (int) Math.ceil(sweep / 12.0);
        if (count < floor) {
            count = floor;
        }
        if (count < 8) {
            count = 8;
        }
        if (count > 720) {
            count = 720;
        }
        return count;
    }

    public static void arc(Segment out, double cx, double cy, double radius,
                            double startDeg, double sweepDeg, int steps) {
        if (radius <= 0 || steps < 1 || Math.abs(sweepDeg) < 1e-9) {
            return;
        }
        double start = Math.toRadians(startDeg);
        double x0 = cx + radius * Math.cos(start);
        double y0 = cy + radius * Math.sin(start);
        for (int i = 1; i <= steps; i++) {
            double angle = Math.toRadians(startDeg + sweepDeg * i / steps);
            double x = cx + radius * Math.cos(angle);
            double y = cy + radius * Math.sin(angle);
            out.line(x0, y0, x, y);
            x0 = x;
            y0 = y;
        }
    }
}
