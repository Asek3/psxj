package nanolive.psxj.gui;

import nanolive.psxj.emu.EmulationState;
import nanolive.psxj.i18n.I18n;
import nanolive.psxj.library.GameEntry;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

public final class GameDetailsPanel extends JPanel {

    private final JLabel titleLabel = new JLabel(I18n.tr("details.emptyTitle"));
    private final JLabel subtitleLabel = new JLabel(I18n.tr("details.placeholder"));
    private final JLabel pathTitle = new JLabel(I18n.tr("details.path"));
    private final JLabel serialValue = new JLabel("-");
    private final JLabel regionValue = new JLabel("-");
    private final JLabel formatValue = new JLabel("-");
    private final JLabel stateValue = new JLabel(UiFormatters.humanizeEnum(EmulationState.STOPPED));
    private final JLabel playTimeValue = new JLabel("0 min");
    private final JLabel lastPlayedValue = new JLabel("-");
    private final JLabel artwork = new JLabel();
    private final JPanel artworkHost = new JPanel(new GridBagLayout());
    private final Component artworkGap = Box.createVerticalStrut(10);
    private final JTextArea pathValue = new JTextArea();
    private final JButton bootButton = new JButton(I18n.tr("toolbar.boot"));
    private final JButton pauseButton = new JButton(I18n.tr("toolbar.pause"));
    private final JButton stopButton = new JButton(I18n.tr("toolbar.stop"));
    private BufferedImage displayedArtwork;
    public GameDetailsPanel(Runnable bootAction, Runnable pauseAction, Runnable stopAction) {
        setOpaque(false);
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setPreferredSize(new Dimension(330, 100));

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 22f));
        subtitleLabel.setForeground(secondaryTextColor());

        pathValue.setEditable(false);
        pathValue.setLineWrap(true);
        pathValue.setWrapStyleWord(true);
        pathValue.setOpaque(false);
        pathValue.setBorder(BorderFactory.createEmptyBorder());
        pathValue.setFont(pathValue.getFont().deriveFont(13f));

        bootButton.addActionListener(event -> bootAction.run());
        pauseButton.addActionListener(event -> pauseAction.run());
        stopButton.addActionListener(event -> stopAction.run());
        ModernUi.styleButton(bootButton, true);
        ModernUi.styleButton(pauseButton, false);
        ModernUi.styleButton(stopButton, false);
        regionValue.setIconTextGap(8);
        artwork.setHorizontalAlignment(JLabel.CENTER);
        artwork.setVerticalAlignment(JLabel.CENTER);

        artworkHost.setOpaque(false);
        artworkHost.setPreferredSize(new Dimension(298, 168));
        artworkHost.setMaximumSize(new Dimension(Integer.MAX_VALUE, 168));
        artworkHost.setAlignmentX(CENTER_ALIGNMENT);
        artworkHost.add(artwork);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(titleLabel);
        top.add(Box.createVerticalStrut(8));
        top.add(subtitleLabel);

        JPanel metrics = new JPanel(new java.awt.GridLayout(3, 2, 8, 8));
        metrics.setOpaque(false);
        metrics.add(createMetricCard(I18n.tr("library.serial"), serialValue));
        metrics.add(createMetricCard(I18n.tr("library.region"), regionValue));
        metrics.add(createMetricCard(I18n.tr("details.format"), formatValue));
        metrics.add(createMetricCard(I18n.tr("details.state"), stateValue));
        metrics.add(createMetricCard(I18n.tr("library.playTime"), playTimeValue));
        metrics.add(createMetricCard(I18n.tr("library.lastPlayed"), lastPlayedValue));

        JPanel pathCard = new JPanel(new BorderLayout());
        pathCard.setOpaque(false);
        pathCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(cardOutlineColor(), 1, true),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        pathCard.add(pathTitle, BorderLayout.NORTH);
        pathCard.add(pathValue, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.GridLayout(1, 3, 10, 0));
        actions.setOpaque(false);
        actions.add(bootButton);
        actions.add(pauseButton);
        actions.add(stopButton);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(artworkHost);
        center.add(artworkGap);
        center.add(metrics);
        center.add(Box.createVerticalStrut(10));
        center.add(pathCard);
        center.add(Box.createVerticalGlue());

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setOpaque(false);
        content.add(top, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);

        JScrollPane scrollPane = new JScrollPane(content,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(scrollPane, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);

        showGame(null, EmulationState.STOPPED, false);
    }

    public void showGame(GameEntry entry, EmulationState emulationState, boolean runningSelectedGame) {
        subtitleLabel.setForeground(secondaryTextColor());
        pathTitle.setForeground(secondaryTextColor());
        pathValue.setForeground(primaryTextColor());
        if (entry == null) {
            setArtwork(null, null);
            titleLabel.setText(I18n.tr("details.emptyTitle"));
            subtitleLabel.setText(I18n.tr("details.placeholder"));
            serialValue.setText("-");
            regionValue.setText("-");
            regionValue.setIcon(new RegionFlagIcon(null));
            formatValue.setText("-");
            playTimeValue.setText("0 min");
            lastPlayedValue.setText("-");
            pathValue.setText(I18n.tr("details.emptyPath"));
            stateValue.setText(UiFormatters.humanizeEnum(emulationState));
            bootButton.setText(I18n.tr("toolbar.boot"));
            bootButton.setEnabled(false);
            pauseButton.setEnabled(false);
            stopButton.setEnabled(emulationState != EmulationState.STOPPED);
            return;
        }

        titleLabel.setText(entry.title());
        subtitleLabel.setText(I18n.tr("details.summary", entry.serial(), entry.region(), entry.path().getFileName()));
        serialValue.setText(entry.serial());
        regionValue.setText(entry.region());
        regionValue.setIcon(new RegionFlagIcon(entry.region()));
        formatValue.setText(entry.sourceExtension().toUpperCase());
        playTimeValue.setText(UiFormatters.formatDuration(entry.totalPlayTimeSeconds()));
        lastPlayedValue.setText(UiFormatters.formatInstant(entry.lastPlayed()));
        pathValue.setText(entry.path().toString());
        stateValue.setText(runningSelectedGame ? UiFormatters.humanizeEnum(emulationState) : I18n.tr("details.ready"));
        bootButton.setText(runningSelectedGame ? I18n.tr("details.reboot") : I18n.tr("details.bootSelected"));
        bootButton.setEnabled(true);
        pauseButton.setEnabled(runningSelectedGame && emulationState != EmulationState.STOPPED);
        stopButton.setEnabled(emulationState != EmulationState.STOPPED);
    }

    public void setArtwork(BufferedImage cover, BufferedImage logo) {
        BufferedImage image = logo != null ? logo : cover;
        if (image == null) {
            displayedArtwork = null;
            artwork.setIcon(null);
            artwork.setVisible(false);
            artworkHost.setVisible(false);
            artworkGap.setVisible(false);
            return;
        }
        artworkHost.setVisible(true);
        artworkGap.setVisible(true);
        artwork.setVisible(true);
        if (image == displayedArtwork) return;
        displayedArtwork = image;
        int maxWidth = 298;
        int maxHeight = 168;
        double scale = Math.min(1.0, Math.min(maxWidth / (double) image.getWidth(),
            maxHeight / (double) image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        artwork.setIcon(new ImageIcon(image.getScaledInstance(width, height,
            java.awt.Image.SCALE_SMOOTH)));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int height = getHeight();

        GradientPaint base = new GradientPaint(
            0, 0, panelStartColor(),
            width, height, panelEndColor()
        );
        g2.setPaint(base);
        g2.fillRoundRect(0, 0, width, height, 28, 28);

        g2.setColor(borderGlowColor(42));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawRoundRect(0, 0, width - 1, height - 1, 28, 28);
        g2.dispose();
        super.paintComponent(graphics);
    }

    private JPanel createMetricCard(String labelText, JLabel valueLabel) {
        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(cardOutlineColor(), 1, true),
            BorderFactory.createEmptyBorder(9, 9, 9, 9)
        ));

        JLabel label = new JLabel(labelText);
        label.setForeground(secondaryTextColor());
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 14f));

        card.add(label);
        card.add(Box.createVerticalStrut(5));
        card.add(valueLabel);
        return card;
    }

    private Color panelStartColor() {
        return ThemeManager.isDarkTheme() ? new Color(22, 28, 40) : new Color(250, 252, 255);
    }

    private Color panelEndColor() {
        return ThemeManager.isDarkTheme() ? new Color(14, 18, 28) : new Color(232, 240, 255);
    }

    private Color borderGlowColor(int alpha) {
        return ThemeManager.isDarkTheme() ? new Color(255, 255, 255, alpha) : new Color(70, 110, 170, Math.min(180, alpha + 30));
    }

    private Color cardOutlineColor() {
        return ThemeManager.isDarkTheme() ? new Color(255, 255, 255, 24) : new Color(80, 115, 168, 52);
    }

    private Color secondaryTextColor() {
        return ThemeManager.isDarkTheme() ? new Color(150, 160, 176) : new Color(92, 104, 126);
    }

    private Color primaryTextColor() {
        return ThemeManager.isDarkTheme() ? new Color(232, 236, 244) : new Color(38, 44, 56);
    }
}
