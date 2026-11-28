package dev.packtoggles;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws a 3D bust (head + shoulders, with the hat / jacket / sleeve layers) from a skin, like a server avatar.
 * Pure Java2D, so it runs on any thread. Painter's algorithm on one quad per skin pixel, 4x supersampled.
 */
final class HeadRender {
    private static final double YAW = Math.toRadians(-28), PITCH = Math.toRadians(18);
    private static final double CY = Math.cos(YAW), SY = Math.sin(YAW), CP = Math.cos(PITCH), SP = Math.sin(PITCH);
    private static final int SS = 4;

    private record Quad(double[] xs, double[] ys, double depth, int argb) { }

    private HeadRender() { }

    /** PNG bytes. bg = ARGB background in a rounded square, or null for transparent. */
    static byte[] png(BufferedImage skin, boolean slim, int size, Integer bg) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(bust(skin, slim, size, bg), "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    static BufferedImage bust(BufferedImage skin, boolean slim, int size, Integer bg) {
        boolean modern = skin.getHeight() >= 64;
        int aw = slim ? 3 : 4;
        List<Quad> q = new ArrayList<>();
        // body
        box(q, skin, 16, 16, 8, 12, 4, -4, 0, -2, 0, false);
        if (modern) box(q, skin, 16, 32, 8, 12, 4, -4, 0, -2, 0.25, true);
        // right arm (viewer's left)
        box(q, skin, 40, 16, aw, 12, 4, -4 - aw, 0, -2, 0, false);
        if (modern) box(q, skin, 40, 32, aw, 12, 4, -4 - aw, 0, -2, 0.25, true);
        // left arm (old 64x32 skins reuse the right arm)
        if (modern) {
            box(q, skin, 32, 48, aw, 12, 4, 4, 0, -2, 0, false);
            box(q, skin, 48, 48, aw, 12, 4, 4, 0, -2, 0.25, true);
        } else {
            box(q, skin, 40, 16, aw, 12, 4, 4, 0, -2, 0, false);
        }
        // head + hat
        box(q, skin, 0, 0, 8, 8, 8, -4, 12, -4, 0, false);
        box(q, skin, 32, 0, 8, 8, 8, -4, 12, -4, 0.5, true);

        // frame on the head: ~58% of the width, small gap on top, torso cut off at the bottom
        double minX = 1e9, maxX = -1e9, minY = 1e9;
        for (int i = 0; i < 8; i++) {
            double[] r = rot(new double[]{(i & 1) == 0 ? -4.5 : 4.5, (i & 2) == 0 ? 11.5 : 20.5, (i & 4) == 0 ? -4.5 : 4.5});
            minX = Math.min(minX, r[0]);
            maxX = Math.max(maxX, r[0]);
            minY = Math.min(minY, -r[1]);
        }
        int big = size * SS;
        double scale = big * 0.58 / (maxX - minX);
        double ox = big / 2.0 - (minX + maxX) / 2 * scale, oy = big * 0.1 - minY * scale;

        BufferedImage img = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        Shape frame = new RoundRectangle2D.Double(0, 0, big, big, big * 0.36, big * 0.36);
        if (bg != null) {
            g.setColor(new Color(bg, true));
            g.fill(frame);
            g.setClip(frame);
        }
        g.setStroke(new BasicStroke(1f));
        q.sort(Comparator.comparingDouble(Quad::depth));
        for (Quad t : q) {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(ox + t.xs[0] * scale, oy + t.ys[0] * scale);
            for (int k = 1; k < 4; k++) p.lineTo(ox + t.xs[k] * scale, oy + t.ys[k] * scale);
            p.closePath();
            g.setColor(new Color(t.argb, true));
            g.fill(p);
            if ((t.argb >>> 24) == 255) g.draw(p);   // closes hairline gaps between pixels
        }
        g.dispose();

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D o = out.createGraphics();
        o.drawImage(img.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        o.dispose();
        return out;
    }

    /** Rotate into camera space: yaw around Y, then tilt so we look down a little. z = towards the viewer. */
    private static double[] rot(double[] p) {
        double x1 = p[0] * CY + p[2] * SY, z1 = -p[0] * SY + p[2] * CY;
        double y2 = p[1] * CP - z1 * SP, z2 = z1 * CP + p[1] * SP;
        return new double[]{x1, y2, z2};
    }

    /** A skin box: (u,v) = top-left of its UV net, w/h/d = size in pixels, (x,y,z) = min corner, inf = overlay inflation. */
    private static void box(List<Quad> out, BufferedImage skin, int u, int v, int w, int h, int d,
                            double x, double y, double z, double inf, boolean overlay) {
        double X0 = x - inf, X1 = x + w + inf, Y0 = y - inf, Y1 = y + h + inf, Z0 = z - inf, Z1 = z + d + inf;
        double W = X1 - X0, H = Y1 - Y0, D = Z1 - Z0;
        face(out, skin, u + d, v + d, w, h, new double[]{X0, Y1, Z1}, new double[]{W, 0, 0}, new double[]{0, -H, 0}, new double[]{0, 0, 1}, 0.9, overlay);
        face(out, skin, u + 2 * d + w, v + d, w, h, new double[]{X1, Y1, Z0}, new double[]{-W, 0, 0}, new double[]{0, -H, 0}, new double[]{0, 0, -1}, 0.7, overlay);
        face(out, skin, u, v + d, d, h, new double[]{X0, Y1, Z0}, new double[]{0, 0, D}, new double[]{0, -H, 0}, new double[]{-1, 0, 0}, 0.72, overlay);
        face(out, skin, u + d + w, v + d, d, h, new double[]{X1, Y1, Z1}, new double[]{0, 0, -D}, new double[]{0, -H, 0}, new double[]{1, 0, 0}, 0.72, overlay);
        face(out, skin, u + d, v, w, d, new double[]{X0, Y1, Z0}, new double[]{W, 0, 0}, new double[]{0, 0, D}, new double[]{0, 1, 0}, 1.0, overlay);
    }

    private static void face(List<Quad> out, BufferedImage skin, int tu, int tv, int tw, int th,
                             double[] P, double[] U, double[] V, double[] n, double shade, boolean overlay) {
        if (rot(n)[2] <= 0) return;   // facing away
        for (int j = 0; j < th; j++) {
            for (int i = 0; i < tw; i++) {
                int sx = tu + i, sy = tv + j;
                if (sx >= skin.getWidth() || sy >= skin.getHeight()) continue;
                int px = skin.getRGB(sx, sy);
                int a = px >>> 24;
                if (overlay && a == 0) continue;
                if (!overlay) a = 255;
                int r = (int) ((px >> 16 & 255) * shade), gg = (int) ((px >> 8 & 255) * shade), b = (int) ((px & 255) * shade);
                double[][] c = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                double[] xs = new double[4], ys = new double[4];
                double depth = 0;
                for (int k = 0; k < 4; k++) {
                    double s = c[k][0] / tw, t = c[k][1] / th;
                    double[] r3 = rot(new double[]{P[0] + U[0] * s + V[0] * t, P[1] + U[1] * s + V[1] * t, P[2] + U[2] * s + V[2] * t});
                    xs[k] = r3[0];
                    ys[k] = -r3[1];
                    depth += r3[2] / 4;
                }
                out.add(new Quad(xs, ys, depth, (a << 24) | (r << 16) | (gg << 8) | b));
            }
        }
    }
}
