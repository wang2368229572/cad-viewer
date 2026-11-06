package app.cadlook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.cadlook.core.CadDocument;
import app.cadlook.core.Curves;

/** 双指缩放，单指平移，单击切换界面，双击把点击处放大一倍。线条按颜色合成少量路径。 */
public final class CadView extends View {
    public interface ChromeListener {
        void onToggleChrome();
    }
    private static final class Ink {
        final int rgb;
        final Path path;

        Ink(int rgb, Path path) {
            this.rgb = rgb;
            this.path = path;
        }
    }

    private static final class Label {
        final double x, y, height, rotationDeg;
        final String[] rows;
        final int rgb;

        Label(CadDocument.Text text) {
            x = text.x;
            y = text.y;
            height = text.height;
            rotationDeg = text.rotationDeg;
            rgb = text.rgb;
            rows = text.value == null ? new String[0] : text.value.split("\n", -1);
        }
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleDetector;
    private CadDocument document;
    private Ink[] inks = new Ink[0];
    private Label[] labels = new Label[0];
    private float scale = 1f;
    private float panX;
    private float panY;
    private boolean fitted;
    private Bitmap cache;
    private Canvas cacheCanvas;
    private final Matrix cacheMatrix = new Matrix();
    private final Paint cachePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float cacheScale = 1f;
    private float cachePanX;
    private float cachePanY;
    private boolean cacheValid;
    private boolean interacting;
    private ChromeListener chromeListener;

    public CadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setKeepScreenOn(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeJoin(Paint.Join.BEVEL);
        fill.setStyle(Paint.Style.FILL);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (scaleDetector.isInProgress()) {
                    return false;
                }
                panX -= distanceX;
                panY -= distanceY;
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (chromeListener != null) {
                    chromeListener.onToggleChrome();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAt(e.getX(), e.getY(), 2f);
                cacheValid = false;
                return true;
            }
        });
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
                return true;
            }
        });
    }

    public void setChromeListener(ChromeListener listener) {
        chromeListener = listener;
    }

    public void setDocument(CadDocument document) {
        this.document = document;
        inks = buildInks(document);
        labels = buildLabels(document);
        fitted = false;
        cacheValid = false;
        invalidate();
    }

    public void fit() {
        fitted = false;
        cacheValid = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        fitted = false;
        cacheValid = false;
        if (cache != null) {
            cache.recycle();
            cache = null;
            cacheCanvas = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (document == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        if (!fitted) {
            fitNow();
            cacheValid = false;
        }
        if (!cacheValid || cache == null) {
            renderCache();
        }
        if (!interacting || cacheScale == 0f) {
            canvas.drawBitmap(cache, 0f, 0f, null);
            return;
        }
        float factor = scale / cacheScale;
        cacheMatrix.setScale(factor, factor);
        cacheMatrix.postTranslate(panX - cachePanX * factor, panY - cachePanY * factor);
        canvas.drawColor(0xFF1B1B1B);
        canvas.drawBitmap(cache, cacheMatrix, cachePaint);
    }

    private void renderCache() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (cache == null || cache.getWidth() != width || cache.getHeight() != height) {
            if (cache != null) {
                cache.recycle();
            }
            cache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            cacheCanvas = new Canvas(cache);
        }
        cacheCanvas.drawColor(0xFF1B1B1B);
        cacheCanvas.save();
        cacheCanvas.translate(panX, panY);
        cacheCanvas.scale(scale, -scale);
        stroke.setStrokeWidth(Math.max(1f, 1.5f / scale));
        for (Ink ink : inks) {
            stroke.setColor(0xFF000000 | ink.rgb);
            cacheCanvas.drawPath(ink.path, stroke);
        }
        cacheCanvas.restore();
        drawLabels(cacheCanvas);
        cacheScale = scale;
        cachePanX = panX;
        cachePanY = panY;
        cacheValid = true;
    }

    private void drawLabels(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        for (Label label : labels) {
            float size = (float) (label.height * scale);
            if (size < 3f) {
                continue;
            }
            float x = sx(label.x);
            float y = sy(label.y);
            if (x < -width || y < -height || x > width * 2f || y > height * 2f) {
                continue;
            }
            fill.setColor(0xFF000000 | label.rgb);
            fill.setTextSize(size);
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(-(float) label.rotationDeg);
            for (int i = 0; i < label.rows.length; i++) {
                canvas.drawText(label.rows[i], 0, i * size * 1.3f, fill);
            }
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            interacting = true;
        }
        scaleDetector.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            interacting = false;
            cacheValid = false;
            invalidate();
        }
        return true;
    }

    private void fitNow() {
        double worldW = Math.max(document.maxX - document.minX, 1e-6);
        double worldH = Math.max(document.maxY - document.minY, 1e-6);
        float pad = 32f;
        float availW = Math.max(1f, getWidth() - pad * 2);
        float availH = Math.max(1f, getHeight() - pad * 2);
        scale = (float) Math.min(availW / worldW, availH / worldH);
        float contentW = (float) (worldW * scale);
        float contentH = (float) (worldH * scale);
        panX = (getWidth() - contentW) / 2f - (float) (document.minX * scale);
        panY = (getHeight() + contentH) / 2f + (float) (document.minY * scale);
        fitted = true;
    }

    private void zoomAt(float focusX, float focusY, float factor) {
        float next = scale * factor;
        if (next < 1e-6f || next > 1e6f) {
            return;
        }
        double wx = (focusX - panX) / scale;
        double wy = (panY - focusY) / scale;
        scale = next;
        panX = (float) (focusX - wx * scale);
        panY = (float) (focusY + wy * scale);
        fitted = true;
        invalidate();
    }

    private float sx(double x) {
        return panX + (float) (x * scale);
    }

    private float sy(double y) {
        return panY - (float) (y * scale);
    }

    private static Ink[] buildInks(CadDocument document) {
        Map<Integer, Path> paths = new HashMap<Integer, Path>();
        for (CadDocument.Line line : document.lines) {
            addSegment(paths, line.rgb, line.x1, line.y1, line.x2, line.y2);
        }
        for (CadDocument.Circle circle : document.circles) {
            addArc(paths, circle.rgb, circle.cx, circle.cy, circle.r, 0, 360, 48);
        }
        for (CadDocument.Arc arc : document.arcs) {
            int steps = (int) Math.ceil(Math.abs(arc.sweepDeg) / 8.0);
            if (steps < 6) {
                steps = 6;
            }
            if (steps > 48) {
                steps = 48;
            }
            addArc(paths, arc.rgb, arc.cx, arc.cy, arc.r, arc.startDeg, arc.sweepDeg, steps);
        }
        Ink[] inks = new Ink[paths.size()];
        int i = 0;
        for (Map.Entry<Integer, Path> entry : paths.entrySet()) {
            inks[i++] = new Ink(entry.getKey(), entry.getValue());
        }
        return inks;
    }

    private static Label[] buildLabels(CadDocument document) {
        List<Label> labels = new ArrayList<Label>();
        for (CadDocument.Text text : document.texts) {
            if (text.value != null && !text.value.isEmpty() && text.height > 0) {
                labels.add(new Label(text));
            }
        }
        return labels.toArray(new Label[0]);
    }

    private static void addArc(final Map<Integer, Path> paths, final int rgb, double cx, double cy, double r,
                               double start, double sweep, int steps) {
        Curves.arc(new Curves.Segment() {
            @Override
            public void line(double x1, double y1, double x2, double y2) {
                addSegment(paths, rgb, x1, y1, x2, y2);
            }
        }, cx, cy, r, start, sweep, steps);
    }

    private static void addSegment(Map<Integer, Path> paths, int rgb, double x1, double y1, double x2, double y2) {
        Path path = paths.get(rgb);
        if (path == null) {
            path = new Path();
            paths.put(rgb, path);
        }
        path.moveTo((float) x1, (float) y1);
        path.lineTo((float) x2, (float) y2);
    }
}
