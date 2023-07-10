package main;

import javax.swing.*;
import java.awt.*;

class ColorfulCellRenderer extends DefaultListCellRenderer {
    private final Color evenColor = new Color(230, 242, 255);
    private final Color oddColor = new Color(230, 255, 230);

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
        Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        // Set the background color based on the index
        if (index % 2 == 0) {
            component.setBackground(evenColor);
        } else {
            component.setBackground(oddColor);
        }

        return component;
    }
}
