package nanolive.psxj.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import javax.swing.Icon;

public final class RegionFlagIcon implements Icon {

    private enum Region { USA, JAPAN, EUROPE, UNKNOWN }

    private static final int WIDTH = 28;
    private static final int HEIGHT = 18;
    private final Region region;

    public RegionFlagIcon(String region) {
        String value = region == null ? "" : region.toUpperCase(Locale.ROOT);
        this.region = switch (value) {
            case "NTSC-U", "USA", "US" -> Region.USA;
            case "NTSC-J", "JAPAN", "JP" -> Region.JAPAN;
            case "PAL", "EUROPE", "EU" -> Region.EUROPE;
            default -> Region.UNKNOWN;
        };
    }

    @Override
    public int getIconWidth() {
        return WIDTH;
    }

    @Override
    public int getIconHeight() {
        return HEIGHT;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape flag = new RoundRectangle2D.Float(0, 0, WIDTH, HEIGHT, 5, 5);
            g.clip(flag);
            switch (region) {
                case USA -> paintUsa(g);
                case JAPAN -> paintJapan(g);
                case EUROPE -> paintEurope(g);
                case UNKNOWN -> paintUnknown(g);
            }
            g.setClip(null);
            g.setColor(new Color(0, 0, 0, 72));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f,
                WIDTH - 1f, HEIGHT - 1f, 5, 5));
        } finally {
            g.dispose();
        }
    }

    private static void paintUsa(Graphics2D g) {
        g.setColor(new Color(250, 250, 250));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(194, 46, 58));
        for (int stripe = 0; stripe < 7; stripe++) {
            g.fillRect(0, stripe * 3, WIDTH, 2);
        }
        g.setColor(new Color(35, 55, 112));
        g.fillRect(0, 0, 13, 10);
        g.setColor(Color.WHITE);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                g.fillOval(2 + column * 3, 2 + row * 3, 1, 1);
            }
        }
    }

    private static void paintJapan(Graphics2D g) {
        g.setColor(new Color(250, 250, 248));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(190, 26, 48));
        g.fillOval(9, 4, 10, 10);
    }

    private static void paintEurope(Graphics2D g) {
        g.setColor(new Color(22, 55, 128));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(255, 205, 35));
        double centerX = WIDTH / 2.0;
        double centerY = HEIGHT / 2.0;
        for (int star = 0; star < 12; star++) {
            double angle = Math.PI * 2 * star / 12.0;
            int sx = (int) Math.round(centerX + Math.cos(angle) * 6.0);
            int sy = (int) Math.round(centerY + Math.sin(angle) * 5.0);
            g.fillOval(sx - 1, sy - 1, 2, 2);
        }
    }

    private static void paintUnknown(Graphics2D g) {
        g.setColor(new Color(85, 96, 116));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(255, 255, 255, 225));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString("?", 10, 14);
    }
}
