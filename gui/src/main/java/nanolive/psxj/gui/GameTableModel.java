package nanolive.psxj.gui;

import nanolive.psxj.i18n.I18n;
import nanolive.psxj.library.GameEntry;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

public final class GameTableModel extends AbstractTableModel {

    private final List<GameEntry> rows = new ArrayList<>();

    public void setRows(List<GameEntry> entries) {
        rows.clear();
        rows.addAll(entries);
        fireTableDataChanged();
    }

    public GameEntry getRow(int index) {
        return rows.get(index);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return 5;
    }

    @Override
    public String getColumnName(int column) {
        return switch (column) {
            case 0 -> I18n.tr("library.title");
            case 1 -> I18n.tr("library.serial");
            case 2 -> I18n.tr("library.region");
            case 3 -> I18n.tr("library.playTime");
            case 4 -> I18n.tr("library.lastPlayed");
            default -> "?";
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        var row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.title();
            case 1 -> row.serial();
            case 2 -> row.region();
            case 3 -> row.totalPlayTimeSeconds();
            case 4 -> row.lastPlayed();
            default -> "";
        };
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return switch (columnIndex) {
            case 3 -> Long.class;
            case 4 -> java.time.Instant.class;
            default -> String.class;
        };
    }
}
