package app.cadlook.core;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 读取 ASCII 和二进制 DXF，展开图块，丢掉关闭的图层和图纸空间。
 * 只保留看图需要的线、圆、弧和文字。
 */
public final class DxfParser {
    private static final String DWG_MESSAGE =
            "这是 DWG 文件。这一版只能打开 DXF。请在 CAD 软件里把图纸另存为 DXF 后再打开。";

    private DxfParser() {
    }

    public static CadDocument parse(InputStream input) throws IOException, CadReadException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, n);
        }
        return parse(buffer.toByteArray());
    }

    public static CadDocument parse(byte[] data) throws IOException, CadReadException {
        if (data.length >= 6 && data[0] == 'A' && data[1] == 'C' && data[2] == '1') {
            throw new CadReadException(DWG_MESSAGE);
        }
        String head = new String(data, 0, Math.min(data.length, 64), StandardCharsets.ISO_8859_1);
        if (head.startsWith("AutoCAD Binary DXF")) {
            return finish(parseGroups(new BinaryGroups(new BufferedInputStream(new ByteArrayInputStream(data)))));
        }
        Charset charset = charsetOf(data);
        return finish(parseGroups(new AsciiGroups(
                new InputStreamReader(new ByteArrayInputStream(data), charset))));
    }

    /** 头部的 $DWGCODEPAGE。中文图纸常见 ANSI_936，要按 GBK 读文字。 */
    private static Charset charsetOf(byte[] data) {
        int n = Math.min(data.length, 80000);
        String head = new String(data, 0, n, StandardCharsets.ISO_8859_1);
        int mark = head.indexOf("$DWGCODEPAGE");
        if (mark < 0) {
            return StandardCharsets.UTF_8;
        }
        String[] lines = head.substring(mark).split("\r?\n", 4);
        if (lines.length < 3) {
            return StandardCharsets.UTF_8;
        }
        String page = lines[2].trim().toUpperCase(Locale.US);
        try {
            if (page.contains("UTF")) {
                return StandardCharsets.UTF_8;
            }
            if (page.contains("936") || page.contains("GBK") || page.contains("GB2312")) {
                return Charset.forName("GBK");
            }
            if (page.contains("950")) {
                return Charset.forName("Big5");
            }
            if (page.contains("932")) {
                return Charset.forName("Shift_JIS");
            }
            if (page.contains("949")) {
                return Charset.forName("MS949");
            }
            if (page.startsWith("ANSI_")) {
                return Charset.forName("windows-" + page.substring(5));
            }
        } catch (RuntimeException ignored) {
            return StandardCharsets.UTF_8;
        }
        return StandardCharsets.UTF_8;
    }

    private static CadDocument finish(Model model) throws CadReadException {
        if (!model.sawSection) {
            throw new CadReadException("无法识别为 DXF 图纸。");
        }
        List<CadDocument.Line> lines = new ArrayList<CadDocument.Line>();
        List<CadDocument.Circle> circles = new ArrayList<CadDocument.Circle>();
        List<CadDocument.Arc> arcs = new ArrayList<CadDocument.Arc>();
        List<CadDocument.Text> texts = new ArrayList<CadDocument.Text>();
        emit(model.model, Xform.identity(), model.blocks, lines, circles, arcs, texts, 0, new HashSet<String>());
        if (lines.isEmpty() && circles.isEmpty() && arcs.isEmpty() && texts.isEmpty()) {
            throw new CadReadException("图纸里没有可显示的图形。关闭的图层和图纸空间里的内容已跳过。");
        }
        return CadDocument.fromParts(lines, circles, arcs, texts);
    }

    private static void emit(Block block, Xform xform, Map<String, Block> blocks,
                             List<CadDocument.Line> lines, List<CadDocument.Circle> circles,
                             List<CadDocument.Arc> arcs, List<CadDocument.Text> texts,
                             int depth, Set<String> stack) {
        if (block == null || depth > 8) {
            return;
        }
        double[] p = new double[2];
        double[] q = new double[2];
        for (CadDocument.Line line : block.lines) {
            xform.map(line.x1, line.y1, p);
            xform.map(line.x2, line.y2, q);
            lines.add(new CadDocument.Line(p[0], p[1], q[0], q[1], line.rgb));
        }
        for (CadDocument.Circle circle : block.circles) {
            if (xform.isConformal()) {
                xform.map(circle.cx, circle.cy, p);
                circles.add(new CadDocument.Circle(p[0], p[1], circle.r * xform.scale(), circle.rgb));
            } else {
                sampleCircle(circle, xform, lines);
            }
        }
        for (CadDocument.Arc arc : block.arcs) {
            if (xform.isConformal()) {
                xform.map(arc.cx, arc.cy, p);
                double sweep = xform.mirrored() ? -arc.sweepDeg : arc.sweepDeg;
                arcs.add(new CadDocument.Arc(p[0], p[1], arc.r * xform.scale(),
                        arc.startDeg + xform.rotationDeg(), sweep, arc.rgb));
            } else {
                sampleArc(arc, xform, lines);
            }
        }
        for (CadDocument.Text text : block.texts) {
            xform.map(text.x, text.y, p);
            texts.add(new CadDocument.Text(p[0], p[1], text.height * xform.scale(),
                    text.rotationDeg + xform.rotationDeg(), text.value, text.rgb));
        }
        for (Insert insert : block.inserts) {
            Block child = blocks.get(insert.name.toUpperCase(Locale.US));
            if (child == null || stack.contains(insert.name.toUpperCase(Locale.US))) {
                continue;
            }
            stack.add(insert.name.toUpperCase(Locale.US));
            emit(child, xform.applyInsert(insert, child), blocks, lines, circles, arcs, texts, depth + 1, stack);
            stack.remove(insert.name.toUpperCase(Locale.US));
        }
    }

    private static void sampleCircle(CadDocument.Circle circle, Xform xform, List<CadDocument.Line> lines) {
        CadDocument.Arc arc = new CadDocument.Arc(circle.cx, circle.cy, circle.r, 0, 360, circle.rgb);
        sampleArc(arc, xform, lines);
    }

    private static void sampleArc(final CadDocument.Arc arc, final Xform xform, final List<CadDocument.Line> lines) {
        final double[] p = new double[2];
        final double[] q = new double[2];
        Curves.arc(new Curves.Segment() {
            public void line(double x1, double y1, double x2, double y2) {
                xform.map(x1, y1, p);
                xform.map(x2, y2, q);
                lines.add(new CadDocument.Line(p[0], p[1], q[0], q[1], arc.rgb));
            }
        }, arc.cx, arc.cy, arc.r, arc.startDeg, arc.sweepDeg, 48);
    }

    private static Model parseGroups(GroupReader reader) throws IOException, CadReadException {
        Model model = new Model();
        String section = "";
        Block current = null;
        Group group = reader.read();
        while (group != null) {
            if (group.code != 0) {
                group = reader.read();
                continue;
            }
            String type = group.text().toUpperCase(Locale.US);
            if ("EOF".equals(type)) {
                break;
            }
            if ("SECTION".equals(type)) {
                model.sawSection = true;
                List<Group> body = readBody(reader);
                section = valueOf(body, 2, "");
                current = null;
                group = reader.peekZero();
                continue;
            }
            if ("ENDSEC".equals(type)) {
                readBody(reader);
                section = "";
                current = null;
                group = reader.peekZero();
                continue;
            }
            if ("TABLE".equals(type)) {
                readBody(reader);
                group = reader.peekZero();
                continue;
            }
            if ("ENDTAB".equals(type)) {
                readBody(reader);
                group = reader.peekZero();
                continue;
            }
            if ("LAYER".equals(type) && "TABLES".equals(section)) {
                readLayer(readBody(reader), model.layers);
                group = reader.peekZero();
                continue;
            }
            if ("BLOCK".equals(type) && "BLOCKS".equals(section)) {
                List<Group> body = readBody(reader);
                String name = valueOf(body, 2, "");
                current = new Block(name, number(body, 10, 0), number(body, 20, 0));
                if (!name.isEmpty()) {
                    model.blocks.put(name.toUpperCase(Locale.US), current);
                }
                group = reader.peekZero();
                continue;
            }
            if ("ENDBLK".equals(type)) {
                readBody(reader);
                current = null;
                group = reader.peekZero();
                continue;
            }
            Block dest = null;
            if ("ENTITIES".equals(section)) {
                dest = model.model;
            } else if ("BLOCKS".equals(section) && current != null) {
                dest = current;
            }
            if (dest == null) {
                readBody(reader);
                group = reader.peekZero();
                continue;
            }
            if ("POLYLINE".equals(type)) {
                List<Group> body = readBody(reader);
                boolean closed = (flag(body) & 1) != 0;
                int rgb = colorOf(body, model.layers);
                boolean paper = paperspace(body);
                List<Vertex> vertices = new ArrayList<Vertex>();
                group = reader.peekZero();
                while (group != null && group.code == 0 && "VERTEX".equals(group.text().toUpperCase(Locale.US))) {
                    List<Group> vertex = readBody(reader);
                    vertices.add(new Vertex(number(vertex, 10, 0), number(vertex, 20, 0), number(vertex, 42, 0)));
                    group = reader.peekZero();
                }
                if (group != null && group.code == 0 && "SEQEND".equals(group.text().toUpperCase(Locale.US))) {
                    readBody(reader);
                    group = reader.peekZero();
                }
                if (!paper && rgb >= 0) {
                    addPoly(dest, vertices, closed, rgb);
                }
                continue;
            }
            List<Group> body = readBody(reader);
            addEntity(dest, type, body, model);
            group = reader.peekZero();
        }
        return model;
    }

    private static void addEntity(Block dest, String type, List<Group> body, Model model) {
        if (paperspace(body)) {
            return;
        }
        int rgb = colorOf(body, model.layers);
        if (rgb < 0) {
            return;
        }
        if ("LINE".equals(type)) {
            dest.lines.add(new CadDocument.Line(
                    number(body, 10, 0), number(body, 20, 0),
                    number(body, 11, 0), number(body, 21, 0), rgb));
        } else if ("CIRCLE".equals(type)) {
            double r = number(body, 40, 0);
            if (r > 0) {
                dest.circles.add(new CadDocument.Circle(number(body, 10, 0), number(body, 20, 0), r, rgb));
            }
        } else if ("ARC".equals(type)) {
            double r = number(body, 40, 0);
            if (r > 0) {
                double start = number(body, 50, 0);
                double end = number(body, 51, 0);
                double sweep = end - start;
                if (sweep <= 1e-9) {
                    sweep += 360;
                }
                dest.arcs.add(new CadDocument.Arc(number(body, 10, 0), number(body, 20, 0), r, start, sweep, rgb));
            }
        } else if ("POINT".equals(type)) {
            double x = number(body, 10, 0);
            double y = number(body, 20, 0);
            dest.lines.add(new CadDocument.Line(x, y, x, y, rgb));
        } else if ("TEXT".equals(type) || "ATTRIB".equals(type) || "ATTDEF".equals(type)) {
            addText(dest, body, rgb, false);
        } else if ("MTEXT".equals(type)) {
            addText(dest, body, rgb, true);
        } else if ("LWPOLYLINE".equals(type)) {
            addPoly(dest, lwVertices(body), (flag(body) & 1) != 0, rgb);
        } else if ("ELLIPSE".equals(type)) {
            addEllipse(dest, body, rgb);
        } else if ("SPLINE".equals(type)) {
            addSpline(dest, body, rgb);
        } else if ("SOLID".equals(type) || "TRACE".equals(type) || "3DFACE".equals(type)) {
            addSolid(dest, body, rgb);
        } else if ("INSERT".equals(type)) {
            String name = valueOf(body, 2, "");
            if (!name.isEmpty()) {
                double sx = number(body, 41, 1);
                double sy = number(body, 42, 1);
                dest.inserts.add(new Insert(name, number(body, 10, 0), number(body, 20, 0),
                        sx == 0 ? 1 : sx, sy == 0 ? 1 : sy, number(body, 50, 0)));
            }
        }
    }

    private static void addText(Block dest, List<Group> body, int rgb, boolean mtext) {
        List<Group> geom = beforeEmbedded(body);
        String raw = mtext ? mtextValue(geom) : valueOf(geom, 1, "");
        String text = cleanText(raw);
        if (text.isEmpty()) {
            return;
        }
        double x = firstNumber(geom, 10, 0);
        double y = firstNumber(geom, 20, 0);
        int align = (int) firstNumber(geom, 72, 0);
        int valign = (int) firstNumber(geom, 73, 0);
        if (!mtext && (align != 0 || valign != 0) && hasCode(geom, 11)) {
            x = firstNumber(geom, 11, x);
            y = firstNumber(geom, 21, y);
        }
        double height = firstNumber(geom, 40, 0);
        double rotation;
        if (mtext && hasCode(geom, 11)) {
            rotation = Math.toDegrees(Math.atan2(firstNumber(geom, 21, 0), firstNumber(geom, 11, 1)));
        } else if (mtext) {
            rotation = Math.toDegrees(firstNumber(geom, 50, 0));
        } else {
            rotation = firstNumber(geom, 50, 0);
        }
        dest.texts.add(new CadDocument.Text(x, y, height, rotation, text, rgb));
    }

    /** 组码 101 后面是嵌入对象，里面的坐标会盖住真正的文字位置。 */
    private static List<Group> beforeEmbedded(List<Group> body) {
        List<Group> geom = new ArrayList<Group>();
        for (Group group : body) {
            if (group.code == 101) {
                break;
            }
            geom.add(group);
        }
        return geom;
    }

    private static double firstNumber(List<Group> body, int code, double fallback) {
        for (Group group : body) {
            if (group.code == code) {
                return group.number();
            }
        }
        return fallback;
    }

    private static void addEllipse(Block dest, List<Group> body, int rgb) {
        double cx = number(body, 10, 0);
        double cy = number(body, 20, 0);
        double mx = number(body, 11, 0);
        double my = number(body, 21, 0);
        double ratio = number(body, 40, 1);
        double t0 = number(body, 41, 0);
        double t1 = number(body, 42, Math.PI * 2);
        if (t1 <= t0) {
            t1 += Math.PI * 2;
        }
        double minorX = -my * ratio;
        double minorY = mx * ratio;
        int steps = 64;
        double prevX = 0;
        double prevY = 0;
        for (int i = 0; i <= steps; i++) {
            double t = t0 + (t1 - t0) * i / steps;
            double x = cx + mx * Math.cos(t) + minorX * Math.sin(t);
            double y = cy + my * Math.cos(t) + minorY * Math.sin(t);
            if (i > 0) {
                dest.lines.add(new CadDocument.Line(prevX, prevY, x, y, rgb));
            }
            prevX = x;
            prevY = y;
        }
    }

    private static void addSpline(Block dest, List<Group> body, int rgb) {
        List<Vertex> fit = pairs(body, 11, 21);
        List<Vertex> control = pairs(body, 10, 20);
        List<Vertex> use = fit.size() >= 2 ? fit : control;
        addPoly(dest, use, false, rgb);
    }

    private static void addSolid(Block dest, List<Group> body, int rgb) {
        double x1 = number(body, 10, 0);
        double y1 = number(body, 20, 0);
        double x2 = number(body, 11, x1);
        double y2 = number(body, 21, y1);
        double x3 = number(body, 12, x1);
        double y3 = number(body, 22, y1);
        double x4 = number(body, 13, x3);
        double y4 = number(body, 23, y3);
        dest.lines.add(new CadDocument.Line(x1, y1, x2, y2, rgb));
        dest.lines.add(new CadDocument.Line(x2, y2, x4, y4, rgb));
        dest.lines.add(new CadDocument.Line(x4, y4, x3, y3, rgb));
        dest.lines.add(new CadDocument.Line(x3, y3, x1, y1, rgb));
    }

    private static List<Vertex> pairs(List<Group> body, int xCode, int yCode) {
        List<Vertex> vertices = new ArrayList<Vertex>();
        Double x = null;
        for (Group group : body) {
            if (group.code == xCode) {
                x = group.number();
            } else if (group.code == yCode && x != null) {
                vertices.add(new Vertex(x, group.number(), 0));
                x = null;
            }
        }
        return vertices;
    }

    private static List<Vertex> lwVertices(List<Group> body) {
        List<Vertex> vertices = new ArrayList<Vertex>();
        Vertex current = null;
        for (Group group : body) {
            if (group.code == 10) {
                current = new Vertex(group.number(), 0, 0);
                vertices.add(current);
            } else if (group.code == 20 && current != null) {
                current.y = group.number();
            } else if (group.code == 42 && current != null) {
                current.bulge = group.number();
            }
        }
        return vertices;
    }

    private static void addPoly(Block dest, List<Vertex> vertices, boolean closed, int rgb) {
        int count = vertices.size();
        if (count < 2) {
            return;
        }
        int edges = closed ? count : count - 1;
        for (int i = 0; i < edges; i++) {
            Vertex a = vertices.get(i);
            Vertex b = vertices.get((i + 1) % count);
            if (Math.abs(a.bulge) < 1e-10) {
                dest.lines.add(new CadDocument.Line(a.x, a.y, b.x, b.y, rgb));
            } else {
                CadDocument.Arc arc = bulgeArc(a.x, a.y, b.x, b.y, a.bulge, rgb);
                if (arc == null) {
                    dest.lines.add(new CadDocument.Line(a.x, a.y, b.x, b.y, rgb));
                } else {
                    dest.arcs.add(arc);
                }
            }
        }
    }

    /**
     * bulge = tan(圆心角 / 4)。正值表示从起点到终点逆时针。
     * 圆心在弦中点沿左法线偏移 r * cos(圆心角 / 2)，r 带符号。
     */
    static CadDocument.Arc bulgeArc(double x1, double y1, double x2, double y2, double bulge, int rgb) {
        double theta = 4 * Math.atan(bulge);
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-12) {
            return null;
        }
        double sinHalf = Math.sin(theta / 2);
        if (Math.abs(sinHalf) < 1e-12) {
            return null;
        }
        double signedRadius = dist / (2 * sinHalf);
        double mx = (x1 + x2) / 2;
        double my = (y1 + y2) / 2;
        double nx = -dy / dist;
        double ny = dx / dist;
        double cx = mx + nx * (signedRadius * Math.cos(theta / 2));
        double cy = my + ny * (signedRadius * Math.cos(theta / 2));
        double start = Math.toDegrees(Math.atan2(y1 - cy, x1 - cx));
        return new CadDocument.Arc(cx, cy, Math.abs(signedRadius), start, Math.toDegrees(theta), rgb);
    }

    private static void readLayer(List<Group> body, Map<String, Layer> layers) {
        String name = valueOf(body, 2, "0");
        int color = (int) number(body, 62, 7);
        boolean off = color < 0;
        if (off) {
            color = -color;
        }
        layers.put(name.toUpperCase(Locale.US), new Layer(color == 0 ? 7 : color, off));
    }

    private static int colorOf(List<Group> body, Map<String, Layer> layers) {
        String layerName = valueOf(body, 8, "0").toUpperCase(Locale.US);
        Layer layer = layers.get(layerName);
        if (layer == null) {
            layer = layers.get("0");
        }
        if (layer == null) {
            layer = new Layer(7, false);
        }
        if (layer.off) {
            return -1;
        }
        if (hasCode(body, 420)) {
            int packed = (int) number(body, 420, 0);
            if (packed != 0) {
                return packed & 0xFFFFFF;
            }
        }
        int aci = (int) number(body, 62, 256);
        if (aci < 0) {
            return -1;
        }
        return AciColor.rgb(aci, layer.aci);
    }

    private static boolean paperspace(List<Group> body) {
        return (int) number(body, 67, 0) == 1;
    }

    private static int flag(List<Group> body) {
        return (int) number(body, 70, 0);
    }

    private static String mtextValue(List<Group> body) {
        StringBuilder builder = new StringBuilder();
        for (Group group : body) {
            if (group.code == 3 || group.code == 1) {
                builder.append(group.value);
            }
        }
        return builder.toString();
    }

    static String cleanText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == 'P' || next == 'p') {
                    out.append('\n');
                    i++;
                    continue;
                }
                if (next == '~') {
                    out.append(' ');
                    i++;
                    continue;
                }
                if ((next == 'U' || next == 'u') && i + 6 < raw.length() && raw.charAt(i + 2) == '+') {
                    try {
                        out.append((char) Integer.parseInt(raw.substring(i + 3, i + 7), 16));
                        i += 6;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // 按普通文字留下
                    }
                }
                if (next == '\\' || next == '{' || next == '}') {
                    out.append(next);
                    i++;
                    continue;
                }
                if ("CcHhFfAaQqWwTtSsLlOoKk".indexOf(next) >= 0) {
                    int semi = raw.indexOf(';', i + 2);
                    if (semi > i) {
                        i = semi;
                        continue;
                    }
                }
            }
            if (c == '{' || c == '}') {
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    private static List<Group> readBody(GroupReader reader) throws IOException, CadReadException {
        List<Group> body = new ArrayList<Group>();
        while (true) {
            Group next = reader.read();
            if (next == null) {
                break;
            }
            if (next.code == 0) {
                reader.unread(next);
                break;
            }
            body.add(next);
        }
        return body;
    }

    private static String valueOf(List<Group> body, int code, String fallback) {
        String value = fallback;
        for (Group group : body) {
            if (group.code == code) {
                value = group.text();
            }
        }
        return value;
    }

    private static double number(List<Group> body, int code, double fallback) {
        double value = fallback;
        boolean found = false;
        for (Group group : body) {
            if (group.code == code) {
                value = group.number();
                found = true;
            }
        }
        return found ? value : fallback;
    }

    private static boolean hasCode(List<Group> body, int code) {
        for (Group group : body) {
            if (group.code == code) {
                return true;
            }
        }
        return false;
    }

    private static final class Model {
        final Block model = new Block("*MODEL", 0, 0);
        final Map<String, Block> blocks = new HashMap<String, Block>();
        final Map<String, Layer> layers = new HashMap<String, Layer>();
        boolean sawSection;

        Model() {
            layers.put("0", new Layer(7, false));
        }
    }

    private static final class Layer {
        final int aci;
        final boolean off;

        Layer(int aci, boolean off) {
            this.aci = aci;
            this.off = off;
        }
    }

    private static final class Block {
        final String name;
        final double baseX;
        final double baseY;
        final List<CadDocument.Line> lines = new ArrayList<CadDocument.Line>();
        final List<CadDocument.Circle> circles = new ArrayList<CadDocument.Circle>();
        final List<CadDocument.Arc> arcs = new ArrayList<CadDocument.Arc>();
        final List<CadDocument.Text> texts = new ArrayList<CadDocument.Text>();
        final List<Insert> inserts = new ArrayList<Insert>();

        Block(String name, double baseX, double baseY) {
            this.name = name;
            this.baseX = baseX;
            this.baseY = baseY;
        }
    }

    private static final class Insert {
        final String name;
        final double x, y, sx, sy, rot;

        Insert(String name, double x, double y, double sx, double sy, double rot) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.sx = sx;
            this.sy = sy;
            this.rot = rot;
        }
    }

    private static final class Vertex {
        final double x;
        double y;
        double bulge;

        Vertex(double x, double y, double bulge) {
            this.x = x;
            this.y = y;
            this.bulge = bulge;
        }
    }

    private static final class Xform {
        final double a, b, c, d, tx, ty;

        Xform(double a, double b, double c, double d, double tx, double ty) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.tx = tx;
            this.ty = ty;
        }

        static Xform identity() {
            return new Xform(1, 0, 0, 1, 0, 0);
        }

        void map(double x, double y, double[] out) {
            out[0] = a * x + b * y + tx;
            out[1] = c * x + d * y + ty;
        }

        double scale() {
            return Math.hypot(a, c);
        }

        double rotationDeg() {
            return Math.toDegrees(Math.atan2(c, a));
        }

        boolean mirrored() {
            return a * d - b * c < 0;
        }

        boolean isConformal() {
            double s = scale();
            if (s < 1e-12) {
                return true;
            }
            double det = Math.abs(a * d - b * c);
            return Math.abs(det - s * s) <= 1e-6 * s * s;
        }

        Xform applyInsert(Insert insert, Block child) {
            double rad = Math.toRadians(insert.rot);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double la = cos * insert.sx;
            double lb = -sin * insert.sy;
            double lc = sin * insert.sx;
            double ld = cos * insert.sy;
            double ltx = insert.x - (la * child.baseX + lb * child.baseY);
            double lty = insert.y - (lc * child.baseX + ld * child.baseY);
            return multiply(new Xform(la, lb, lc, ld, ltx, lty));
        }

        Xform multiply(Xform local) {
            return new Xform(
                    a * local.a + b * local.c,
                    a * local.b + b * local.d,
                    c * local.a + d * local.c,
                    c * local.b + d * local.d,
                    a * local.tx + b * local.ty + tx,
                    c * local.tx + d * local.ty + ty);
        }
    }

    private interface GroupReader {
        Group read() throws IOException, CadReadException;

        void unread(Group group);

        Group peekZero() throws IOException, CadReadException;
    }

    private static final class Group {
        final int code;
        final String value;

        Group(int code, String value) {
            this.code = code;
            this.value = value == null ? "" : value;
        }

        String text() {
            return value.trim();
        }

        double number() {
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private static final class AsciiGroups implements GroupReader {
        private final BufferedReader reader;
        private Group pushback;

        AsciiGroups(InputStreamReader reader) {
            this.reader = new BufferedReader(reader);
        }

        public Group read() throws IOException, CadReadException {
            if (pushback != null) {
                Group group = pushback;
                pushback = null;
                return group;
            }
            String codeLine = reader.readLine();
            if (codeLine == null) {
                return null;
            }
            if (codeLine.startsWith("\uFEFF")) {
                codeLine = codeLine.substring(1);
            }
            String trimmed = codeLine.trim();
            if (trimmed.isEmpty()) {
                return read();
            }
            int code;
            try {
                code = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new CadReadException("DXF 组码无法识别：" + trimmed);
            }
            String value = reader.readLine();
            if (value == null) {
                value = "";
            }
            return new Group(code, value);
        }

        public void unread(Group group) {
            pushback = group;
        }

        public Group peekZero() throws IOException, CadReadException {
            return read();
        }
    }

    private static final class BinaryGroups implements GroupReader {
        private final BufferedInputStream in;
        private Group pushback;
        private boolean headerLeft = true;

        BinaryGroups(BufferedInputStream in) throws IOException, CadReadException {
            this.in = in;
            StringBuilder sentinel = new StringBuilder();
            while (sentinel.length() < 22) {
                int b = in.read();
                if (b < 0) {
                    throw new CadReadException("二进制 DXF 文件不完整。");
                }
                sentinel.append((char) b);
            }
            if (!sentinel.toString().startsWith("AutoCAD Binary DXF")) {
                throw new CadReadException("无法识别为二进制 DXF。");
            }
        }

        public Group read() throws IOException, CadReadException {
            if (pushback != null) {
                Group group = pushback;
                pushback = null;
                return group;
            }
            if (headerLeft) {
                headerLeft = false;
            }
            int code = readU16();
            if (code < 0) {
                return null;
            }
            return new Group(code, readValue(code));
        }

        public void unread(Group group) {
            pushback = group;
        }

        public Group peekZero() throws IOException, CadReadException {
            return read();
        }

        private String readValue(int code) throws IOException, CadReadException {
            if (isString(code)) {
                return readString();
            }
            if (isDouble(code)) {
                return Double.toString(readDouble());
            }
            if (isByte(code)) {
                int b = in.read();
                if (b < 0) {
                    throw new CadReadException("二进制 DXF 文件不完整。");
                }
                return Integer.toString(b);
            }
            if (isInt16(code)) {
                return Integer.toString((short) readU16Checked());
            }
            if (isInt64(code)) {
                return Long.toString(readLong());
            }
            return Integer.toString(readInt());
        }

        private static boolean isString(int code) {
            return (code >= 0 && code <= 9) || code == 100 || code == 102 || code == 105
                    || (code >= 300 && code <= 309) || (code >= 320 && code <= 369)
                    || (code >= 390 && code <= 399) || (code >= 410 && code <= 419)
                    || (code >= 430 && code <= 439) || (code >= 470 && code <= 481)
                    || code == 999 || (code >= 1000 && code <= 1009);
        }

        private static boolean isDouble(int code) {
            return (code >= 10 && code <= 59) || (code >= 110 && code <= 149)
                    || (code >= 210 && code <= 239) || (code >= 460 && code <= 469)
                    || (code >= 1010 && code <= 1059);
        }

        private static boolean isByte(int code) {
            return code >= 290 && code <= 299;
        }

        private static boolean isInt16(int code) {
            return (code >= 60 && code <= 79) || (code >= 170 && code <= 179)
                    || (code >= 270 && code <= 289) || (code >= 370 && code <= 389)
                    || (code >= 400 && code <= 409) || (code >= 1060 && code <= 1070);
        }

        private static boolean isInt64(int code) {
            return code >= 160 && code <= 169;
        }

        private String readString() throws IOException {
            ByteArrayBuilder builder = new ByteArrayBuilder();
            while (true) {
                int b = in.read();
                if (b <= 0) {
                    break;
                }
                builder.add(b);
            }
            return new String(builder.bytes(), StandardCharsets.UTF_8);
        }

        private int readU16() throws IOException {
            int b0 = in.read();
            if (b0 < 0) {
                return -1;
            }
            int b1 = in.read();
            if (b1 < 0) {
                return -1;
            }
            return b0 | (b1 << 8);
        }

        private int readU16Checked() throws IOException, CadReadException {
            int v = readU16();
            if (v < 0) {
                throw new CadReadException("二进制 DXF 文件不完整。");
            }
            return v;
        }

        private int readInt() throws IOException, CadReadException {
            byte[] b = readBytes(4);
            return (b[0] & 255) | ((b[1] & 255) << 8) | ((b[2] & 255) << 16) | (b[3] << 24);
        }

        private long readLong() throws IOException, CadReadException {
            byte[] b = readBytes(8);
            long v = 0;
            for (int i = 7; i >= 0; i--) {
                v = (v << 8) | (b[i] & 255L);
            }
            return v;
        }

        private double readDouble() throws IOException, CadReadException {
            return Double.longBitsToDouble(readLong());
        }

        private byte[] readBytes(int n) throws IOException, CadReadException {
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(b, off, n - off);
                if (r < 0) {
                    throw new CadReadException("二进制 DXF 文件不完整。");
                }
                off += r;
            }
            return b;
        }
    }

    private static final class ByteArrayBuilder {
        private byte[] data = new byte[32];
        private int size;

        void add(int b) {
            if (size == data.length) {
                byte[] next = new byte[data.length * 2];
                System.arraycopy(data, 0, next, 0, size);
                data = next;
            }
            data[size++] = (byte) b;
        }

        byte[] bytes() {
            byte[] copy = new byte[size];
            System.arraycopy(data, 0, copy, 0, size);
            return copy;
        }
    }

}
