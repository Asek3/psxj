package nanolive.psxj.gui;

import nanolive.psxj.i18n.I18n;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

public final class AboutDialog extends JDialog {

    public AboutDialog(MainFrame owner) {
        super(owner, I18n.tr("about.title"), true);
        setPreferredSize(new Dimension(600, 360));
        setResizable(false);

        JPanel content = new JPanel(new BorderLayout(20, 20));
        content.setBorder(BorderFactory.createCompoundBorder(
            ModernUi.windowContentBorder(),
            BorderFactory.createEmptyBorder(28, 30, 24, 30)));

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        var title = new JLabel("PSXJ", SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 30f));
        title.setAlignmentX(0f);
        var version = new JLabel(I18n.tr("about.version"));
        version.setForeground(ModernUi.accent());
        version.setAlignmentX(0f);
        heading.add(title);
        heading.add(Box.createVerticalStrut(5));
        heading.add(version);

        var body = new JTextArea();
        body.setEditable(false);
        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setText(I18n.tr("about.body"));
        body.setOpaque(false);
        body.setBorder(ModernUi.cardBorder(18, 18));
        body.setFont(body.getFont().deriveFont(14f));
        body.setForeground(ModernUi.secondaryText());

        content.add(heading, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        setContentPane(content);

        pack();
        setLocationRelativeTo(owner);
    }
}
