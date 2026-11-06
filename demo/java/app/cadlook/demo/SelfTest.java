package app.cadlook.demo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import app.cadlook.core.CadDocument;
import app.cadlook.core.CadReadException;
import app.cadlook.core.DxfParser;

public final class SelfTest {
    public static void main(String[] args) throws Exception {
        Path sample = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("samples", "demo.dxf");
        CadDocument demo = DxfParser.parse(Files.readAllBytes(sample));
        check(demo.lines.size() == 2, "样例线段数量，实际 " + demo.lines.size());
        check(demo.circles.size() == 1, "样例圆数量");
        check(demo.arcs.size() == 2, "样例圆弧数量，实际 " + demo.arcs.size());
        check(demo.texts.size() == 1, "样例文字数量");
        check(demo.texts.get(0).value.contains("中") && demo.texts.get(0).value.contains("A"),
                "文字解码，实际 " + demo.texts.get(0).value);
        check(demo.circles.get(0).rgb == 0xFF0000, "圆应为红色");
        check(near(demo.minX, 0) && near(demo.minY, 0), "范围左下");
        check(near(demo.maxX, 100) && near(demo.maxY, 50), "范围右上 " + demo.maxX + "," + demo.maxY);
        boolean inserted = false;
        for (CadDocument.Line line : demo.lines) {
            if (near(line.x1, 50) && near(line.y1, 50) && near(line.x2, 60) && near(line.y2, 50)) {
                inserted = true;
            }
        }
        check(inserted, "图块没有插到 (50,50)");
        CadDocument.Arc bulge = null;
        for (CadDocument.Arc arc : demo.arcs) {
            if (arc.r < 2) {
                bulge = arc;
            }
        }
        check(bulge != null, "没有凸度圆弧");
        check(near(bulge.cx, 0) && near(bulge.cy, 0), "凸度圆心 " + bulge.cx + "," + bulge.cy);
        double mid = Math.toRadians(bulge.startDeg + bulge.sweepDeg / 2);
        double mx = bulge.cx + bulge.r * Math.cos(mid);
        double my = bulge.cy + bulge.r * Math.sin(mid);
        check(Math.abs(mx - 0.7071) < 0.02 && Math.abs(my - 0.7071) < 0.02,
                "凸度中点 " + mx + "," + my);

        CadDocument based = DxfParser.parse(dxf(
                "0", "SECTION", "2", "BLOCKS",
                "0", "BLOCK", "2", "MARK", "70", "0", "10", "5", "20", "5",
                "0", "LINE", "8", "0", "10", "5", "20", "5", "11", "8", "21", "5",
                "0", "ENDBLK",
                "0", "ENDSEC",
                "0", "SECTION", "2", "ENTITIES",
                "0", "INSERT", "8", "0", "2", "MARK", "10", "0", "20", "0",
                "0", "ENDSEC", "0", "EOF").getBytes(StandardCharsets.UTF_8));
        check(based.lines.size() == 1, "基点图块线段");
        CadDocument.Line shifted = based.lines.get(0);
        check(near(shifted.x1, 0) && near(shifted.y1, 0) && near(shifted.x2, 3) && near(shifted.y2, 0),
                "基点换算 " + shifted.x1 + "," + shifted.y1 + " -> " + shifted.x2 + "," + shifted.y2);

        byte[] dwg = new byte[]{'A', 'C', '1', '0', '2', '7', 0, 0};
        try {
            DxfParser.parse(dwg);
            throw new AssertionError("DWG 应被拒绝");
        } catch (CadReadException e) {
            check(e.getMessage().contains("DWG"), "DWG 提示");
        }

        CadDocument binary = DxfParser.parse(binaryLine());
        check(binary.lines.size() == 1, "二进制 DXF 线段");
        CadDocument.Line line = binary.lines.get(0);
        check(near(line.x1, 1) && near(line.y1, 2) && near(line.x2, 3) && near(line.y2, 4),
                "二进制坐标");

        String png = args.length > 1 ? args[1] : "build/demo.png";
        Preview.write(demo, Paths.get(png));
        System.out.println("通过，预览已写到 " + png);
    }

    private static byte[] binaryLine() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("AutoCAD Binary DXF\r\n\u001A\u0000".getBytes(StandardCharsets.ISO_8859_1));
        pair(out, 0, "SECTION");
        pair(out, 2, "ENTITIES");
        pair(out, 0, "LINE");
        pair(out, 8, "0");
        pair(out, 10, 1);
        pair(out, 20, 2);
        pair(out, 11, 3);
        pair(out, 21, 4);
        pair(out, 0, "ENDSEC");
        pair(out, 0, "EOF");
        return out.toByteArray();
    }

    private static void pair(ByteArrayOutputStream out, int code, String value) throws Exception {
        writeU16(out, code);
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void pair(ByteArrayOutputStream out, int code, double value) throws Exception {
        writeU16(out, code);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) {
            out.write((int) (bits & 255));
            bits >>>= 8;
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int code) {
        out.write(code & 255);
        out.write((code >> 8) & 255);
    }

    private static String dxf(String... rows) {
        StringBuilder builder = new StringBuilder();
        for (String row : rows) {
            builder.append(row).append('\n');
        }
        return builder.toString();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-6;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
