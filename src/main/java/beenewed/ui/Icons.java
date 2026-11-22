package beenewed.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;

/**
 * Simple line icons drawn with Java2D on a 24x24 grid, so the app needs no image files.
 */
public final class Icons {
    private Icons() {}

    public enum Type {
        // navigation / UI
        DASHBOARD, CARD, CALENDAR, CHART, GEAR, PLUS, SEARCH, BELL, HISTORY, INFO, PENCIL, SWAP,
        CLOSE, CHECK, CHEVRON_DOWN, CHEVRON_UP, CHEVRON_LEFT, CHEVRON_RIGHT, ARROW_RIGHT, RECEIPT, USERS, USER_PLUS, CASH, LOCK,
        FILTER, CLOCK, DOTS, X_CIRCLE, SUN, MOON, USER, SAVE, EXTERNAL, ALERT, CHECK_CIRCLE, SLIDERS, WALLET, TRASH, LIST, REFRESH,
        // subscription icons (16 generic icons from the icon selector)
        TELEVISION, COMPUTER, AUDIO, MUSIC, GAMING, CLOUD, EDUCATION, WORK, MOVIES, MOBILE, INTERNET,
        UTILITIES, DESIGN, SECURITY, SHOPPING, OTHER
    }

    /** The 16 icons offered in the "Select Icon" grid, with their display names. */
    public static final Type[] SUBSCRIPTION_ICONS = {
        Type.TELEVISION, Type.COMPUTER, Type.AUDIO, Type.MUSIC, Type.GAMING, Type.CLOUD, Type.EDUCATION, Type.WORK,
        Type.MOVIES, Type.MOBILE, Type.INTERNET, Type.UTILITIES, Type.DESIGN, Type.SECURITY, Type.SHOPPING, Type.OTHER
    };

    public static String label(Type t) {
        String n = t.name().toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public static Icon of(Type t, int size, Color c) { return new VIcon(t, size, c, 2.0f); }
    public static Icon of(Type t, int size, Color c, float stroke) { return new VIcon(t, size, c, stroke); }

    static final class VIcon implements Icon {
        final Type type; final int size; final Color color; final float stroke;
        VIcon(Type t, int s, Color c, float st) { type = t; size = s; color = c; stroke = st; }
        public int getIconWidth() { return size; }
        public int getIconHeight() { return size; }

        public void paintIcon(Component comp, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.translate(x, y);
            double s = size / 24.0;
            g.scale(s, s);
            g.setColor(color);
            // stroke is in 24-grid units (like Lucide icons); keep at least ~1.1px on screen
            float w = (float) Math.max(stroke, 1.1 / s);
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g, type);
            g.dispose();
        }
    }

    private static void rr(Graphics2D g, double x, double y, double w, double h, double r) {
        g.draw(new RoundRectangle2D.Double(x, y, w, h, r, r));
    }
    private static void ln(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }
    private static void circ(Graphics2D g, double cx, double cy, double r) {
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }
    private static void dot(Graphics2D g, double cx, double cy, double r) {
        g.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }
    private static void poly(Graphics2D g, double... p) {
        Path2D path = new Path2D.Double();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        g.draw(path);
    }

    private static void draw(Graphics2D g, Type t) {
        switch (t) {
            case DASHBOARD -> { rr(g, 3, 3, 7, 9, 2); rr(g, 14, 3, 7, 5, 2); rr(g, 14, 12, 7, 9, 2); rr(g, 3, 16, 7, 5, 2); }
            case CARD, WALLET -> { rr(g, 2.5, 5, 19, 14, 3); ln(g, 2.5, 10, 21.5, 10); if (t == Type.WALLET) dot(g, 17, 14.5, 1.2); }
            case CALENDAR -> { rr(g, 3, 4.5, 18, 16.5, 3); ln(g, 3, 9.5, 21, 9.5); ln(g, 8, 2.5, 8, 6); ln(g, 16, 2.5, 16, 6); }
            case CHART -> { rr(g, 3, 3, 18, 18, 3); ln(g, 8, 16, 8, 12); ln(g, 12, 16, 12, 8); ln(g, 16, 16, 16, 11); }
            case GEAR -> {
                circ(g, 12, 12, 3);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    ln(g, 12 + Math.cos(a) * 6.5, 12 + Math.sin(a) * 6.5, 12 + Math.cos(a) * 9, 12 + Math.sin(a) * 9);
                }
                circ(g, 12, 12, 6.5);
            }
            case PLUS -> { ln(g, 12, 5, 12, 19); ln(g, 5, 12, 19, 12); }
            case SEARCH -> { circ(g, 10.5, 10.5, 6.5); ln(g, 15.5, 15.5, 20.5, 20.5); }
            case BELL -> {
                Path2D p = new Path2D.Double();
                p.moveTo(6, 16.5); p.lineTo(6, 11); p.curveTo(6, 7, 8.7, 4.5, 12, 4.5); p.curveTo(15.3, 4.5, 18, 7, 18, 11);
                p.lineTo(18, 16.5); p.lineTo(19.5, 18); p.lineTo(4.5, 18); p.closePath(); g.draw(p);
                ln(g, 10, 21, 14, 21);
            }
            case HISTORY, CLOCK -> {
                if (t == Type.HISTORY) {
                    g.draw(new Arc2D.Double(3.5, 3.5, 17, 17, 200, -310, Arc2D.OPEN));
                    poly(g, 3, 7, 4.2, 10.7, 7.8, 9.6);
                } else circ(g, 12, 12, 8.5);
                poly(g, 12, 7.5, 12, 12, 15, 14);
            }
            case INFO -> { circ(g, 12, 12, 9); ln(g, 12, 11, 12, 16.5); dot(g, 12, 7.8, 1.1); }
            case ALERT -> { circ(g, 12, 12, 9); ln(g, 12, 7.5, 12, 13); dot(g, 12, 16.3, 1.1); }
            case SUN -> {
                circ(g, 12, 12, 4);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    ln(g, 12 + Math.cos(a) * 7, 12 + Math.sin(a) * 7, 12 + Math.cos(a) * 9.5, 12 + Math.sin(a) * 9.5);
                }
            }
            case MOON -> {
                java.awt.geom.Area moon = new java.awt.geom.Area(new Ellipse2D.Double(3.5, 3.5, 17, 17));
                moon.subtract(new java.awt.geom.Area(new Ellipse2D.Double(9, 0.5, 15, 15)));
                g.draw(moon);
            }
            case USER -> { circ(g, 12, 8.5, 4); g.draw(new Arc2D.Double(4.5, 14, 15, 14, 0, 180, Arc2D.OPEN)); }
            case X_CIRCLE -> { circ(g, 12, 12, 9); ln(g, 9, 9, 15, 15); ln(g, 15, 9, 9, 15); }
            case CHECK_CIRCLE -> { circ(g, 12, 12, 9); poly(g, 8, 12.3, 10.8, 15, 16, 9.5); }
            case PENCIL -> { poly(g, 4, 20, 4.8, 15.8, 15.5, 5.1, 18.9, 8.5, 8.2, 19.2, 4, 20); ln(g, 13.3, 7.3, 16.7, 10.7); }
            case SWAP -> { poly(g, 4, 8, 19, 8); poly(g, 15.5, 4.5, 19, 8, 15.5, 11.5); poly(g, 20, 16, 5, 16); poly(g, 8.5, 12.5, 5, 16, 8.5, 19.5); }
            case CLOSE -> { ln(g, 6, 6, 18, 18); ln(g, 18, 6, 6, 18); }
            case CHECK -> poly(g, 5, 12.5, 9.8, 17, 19, 7);
            case CHEVRON_DOWN -> poly(g, 6, 9, 12, 15, 18, 9);
            case CHEVRON_UP -> poly(g, 6, 15, 12, 9, 18, 15);
            case CHEVRON_LEFT -> poly(g, 15, 6, 9, 12, 15, 18);
            case CHEVRON_RIGHT -> poly(g, 9, 6, 15, 12, 9, 18);
            case ARROW_RIGHT -> { ln(g, 5, 12, 19, 12); poly(g, 13, 6, 19, 12, 13, 18); }
            case RECEIPT -> { poly(g, 5, 3, 19, 3, 19, 21, 16.5, 19.5, 14, 21, 12, 19.5, 10, 21, 7.5, 19.5, 5, 21, 5, 3); ln(g, 8.5, 8, 15.5, 8); ln(g, 8.5, 12, 15.5, 12); ln(g, 8.5, 16, 12.5, 16); }
            case USERS -> { circ(g, 9, 8, 3.5); g.draw(new Arc2D.Double(2.5, 13.5, 13, 13, 0, 180, Arc2D.OPEN)); circ(g, 16.5, 8.5, 2.8); g.draw(new Arc2D.Double(14, 14, 8.5, 9, 10, 170, Arc2D.OPEN)); }
            case USER_PLUS -> { circ(g, 9, 8, 3.5); g.draw(new Arc2D.Double(2.5, 13.5, 13, 13, 0, 180, Arc2D.OPEN)); ln(g, 18.5, 7, 18.5, 13); ln(g, 15.5, 10, 21.5, 10); }
            case CASH -> { rr(g, 2.5, 6, 19, 12, 2.5); circ(g, 12, 12, 2.8); dot(g, 6, 12, 0.9); dot(g, 18, 12, 0.9); }
            case LOCK -> { rr(g, 5, 10.5, 14, 10, 2.5); g.draw(new Arc2D.Double(8, 4, 8, 11, 0, 180, Arc2D.OPEN)); ln(g, 8, 9.5, 8, 10.5); ln(g, 16, 9.5, 16, 10.5); }
            case FILTER -> { ln(g, 4, 7, 20, 7); ln(g, 7, 12, 17, 12); ln(g, 10, 17, 14, 17); }
            case SLIDERS -> { ln(g, 4, 7, 20, 7); ln(g, 4, 12, 20, 12); ln(g, 4, 17, 20, 17); dot(g, 9, 7, 2); dot(g, 15, 12, 2); dot(g, 8, 17, 2); }
            case LIST -> { ln(g, 9, 7, 20, 7); ln(g, 9, 12, 20, 12); ln(g, 9, 17, 20, 17); dot(g, 5, 7, 1.1); dot(g, 5, 12, 1.1); dot(g, 5, 17, 1.1); }
            case DOTS -> { dot(g, 6, 12, 1.5); dot(g, 12, 12, 1.5); dot(g, 18, 12, 1.5); }
            case SAVE -> { poly(g, 4, 4, 16.5, 4, 20, 7.5, 20, 20, 4, 20, 4, 4); rr(g, 8, 4, 7, 5, 1); rr(g, 7.5, 13, 9, 7, 1); }
            case EXTERNAL -> { poly(g, 13, 4, 20, 4, 20, 11); ln(g, 20, 4, 11, 13); poly(g, 17, 14, 17, 20, 4, 20, 4, 7, 10, 7); }
            case TRASH -> { ln(g, 4, 6.5, 20, 6.5); poly(g, 9, 6.5, 9, 4, 15, 4, 15, 6.5); poly(g, 6, 6.5, 7, 20, 17, 20, 18, 6.5); }
            case REFRESH -> {
                g.draw(new Arc2D.Double(4, 4, 16, 16, 35, 290, Arc2D.OPEN));
                poly(g, 19.5, 4.5, 19.5, 9.5, 14.5, 9.5);
            }

            case TELEVISION -> { rr(g, 2.5, 4.5, 19, 13, 2.5); ln(g, 8, 20.5, 16, 20.5); ln(g, 12, 17.5, 12, 20.5); }
            case COMPUTER -> { rr(g, 4, 5, 16, 11, 2); ln(g, 2, 19, 22, 19); }
            case AUDIO -> { g.draw(new Arc2D.Double(4, 4, 16, 16, 0, 180, Arc2D.OPEN)); rr(g, 3.5, 12.5, 4.5, 7, 2); rr(g, 16, 12.5, 4.5, 7, 2); }
            case MUSIC -> { poly(g, 9, 18, 9, 5, 19, 3, 19, 16); circ(g, 6.5, 18, 2.5); circ(g, 16.5, 16, 2.5); }
            case GAMING -> { rr(g, 2.5, 7, 19, 11, 5.5); ln(g, 7, 10.5, 7, 14.5); ln(g, 5, 12.5, 9, 12.5); dot(g, 15.5, 11.5, 1); dot(g, 17.5, 13.8, 1); }
            case CLOUD -> {
                Path2D p = new Path2D.Double();
                p.moveTo(7, 18.5); p.curveTo(3.5, 18.5, 2.5, 14, 5.5, 12.6); p.curveTo(5.5, 8.5, 10.5, 6.5, 13, 9.3);
                p.curveTo(15, 6.2, 20.5, 7.8, 19.5, 12.2); p.curveTo(22.5, 13, 21.8, 18.5, 18, 18.5); p.closePath(); g.draw(p);
            }
            case EDUCATION -> { poly(g, 2, 9, 12, 4.5, 22, 9, 12, 13.5, 2, 9); poly(g, 6, 11, 6, 16, 12, 19, 18, 16, 18, 11); ln(g, 22, 9, 22, 14); }
            case WORK -> { rr(g, 3, 7, 18, 13, 2.5); poly(g, 8.5, 7, 8.5, 4.5, 15.5, 4.5, 15.5, 7); ln(g, 3, 12.5, 21, 12.5); }
            case MOVIES -> { rr(g, 3, 6, 18, 14, 2); ln(g, 3, 10, 21, 10); ln(g, 7, 6, 8.5, 10); ln(g, 12, 6, 13.5, 10); ln(g, 17, 6, 18.5, 10); }
            case MOBILE -> { rr(g, 6.5, 2.5, 11, 19, 2.5); ln(g, 10.5, 18.5, 13.5, 18.5); }
            case INTERNET -> {
                g.draw(new Arc2D.Double(2, 6, 20, 20, 45, 90, Arc2D.OPEN));
                g.draw(new Arc2D.Double(5.5, 9.5, 13, 13, 45, 90, Arc2D.OPEN));
                g.draw(new Arc2D.Double(9, 13, 6, 6, 45, 90, Arc2D.OPEN));
                dot(g, 12, 19.3, 1.2);
            }
            case UTILITIES -> { poly(g, 14.5, 3.5, 13, 7.5, 16.5, 11, 20.5, 9.5); g.draw(new Arc2D.Double(12, 3, 9, 9, 100, 250, Arc2D.OPEN)); ln(g, 13, 11, 4.5, 19.5); }
            case DESIGN -> { circ(g, 12, 12, 9); dot(g, 8, 10, 1.2); dot(g, 12, 7.5, 1.2); dot(g, 16, 10, 1.2); dot(g, 9, 14.5, 1.2); }
            case SECURITY -> { poly(g, 12, 2.5, 20, 5.5, 20, 11, 12, 21.5, 4, 11, 4, 5.5, 12, 2.5); poly(g, 8.5, 12, 11, 14.5, 15.5, 9.5); }
            case SHOPPING -> { poly(g, 5, 8, 19, 8, 18, 21, 6, 21, 5, 8); g.draw(new Arc2D.Double(8.5, 3, 7, 9, 0, 180, Arc2D.OPEN)); }
            case OTHER -> { circ(g, 12, 12, 9); g.draw(new Arc2D.Double(9.2, 6.8, 5.6, 5.6, 160, -230, Arc2D.OPEN)); ln(g, 12, 12.4, 12, 13.8); dot(g, 12, 17, 1.1); }
        }
    }
}
