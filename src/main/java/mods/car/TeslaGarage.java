
package mods.car;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Non-modal Tesla vehicle configuration selector.
 * F9 opens or reopens the garage.
 */
final class TeslaGarage {

    private final CarVehicle vehicle;

    private JFrame window;
    private JComboBox<TeslaSpec.Model> models;
    private JComboBox<TeslaSpec.Type> types;
    private JComboBox<TeslaSpec> variants;
    private JLabel details;

    TeslaGarage(CarVehicle vehicle) {
        this.vehicle = vehicle;

        SwingUtilities.invokeLater(this::build);

        KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(event -> {
                    if (event.getID() == KeyEvent.KEY_PRESSED
                            && event.getKeyCode() == KeyEvent.VK_F9) {

                        SwingUtilities.invokeLater(() -> {
                            if (window != null) {
                                window.setVisible(true);
                                window.toFront();
                            }
                        });

                        return true;
                    }

                    return false;
                });
    }

    private void build() {
        window = new JFrame("Tesla Garage - F9 to reopen");

        window.setDefaultCloseOperation(
                WindowConstants.HIDE_ON_CLOSE
        );

        models = new JComboBox<>(TeslaSpec.Model.values());
        types = new JComboBox<>(TeslaSpec.Type.values());
        variants = new JComboBox<>();

        details = new JLabel();
        details.setVerticalAlignment(SwingConstants.TOP);

        JPanel controls = new JPanel(
                new GridLayout(3, 2, 8, 8)
        );

        controls.add(new JLabel("Model"));
        controls.add(models);

        controls.add(new JLabel("Category"));
        controls.add(types);

        controls.add(new JLabel("Actual Tesla configuration"));
        controls.add(variants);

        JLabel message = new JLabel(
                "Select a published configuration. Press F9 to reopen."
        );

        JButton apply = new JButton("Apply selected Tesla");

        apply.addActionListener(e -> {
            TeslaSpec selected =
                    (TeslaSpec) variants.getSelectedItem();

            if (selected != null) {
                vehicle.requestConfiguration(selected);

                message.setText(
                        "Selected: " + selected.fullName()
                                + " (applies when the car has stopped)"
                );

                window.setVisible(false);
            }
        });

        models.addActionListener(e -> updateVariants());
        types.addActionListener(e -> updateVariants());
        variants.addActionListener(e -> updateDetails());

        JPanel panel = new JPanel(
                new BorderLayout(10, 10)
        );

        panel.setBorder(
                BorderFactory.createEmptyBorder(16, 16, 16, 16)
        );

        panel.add(controls, BorderLayout.NORTH);
        panel.add(details, BorderLayout.CENTER);

        JPanel footer = new JPanel(
                new BorderLayout(6, 6)
        );

        footer.add(message, BorderLayout.NORTH);
        footer.add(apply, BorderLayout.SOUTH);

        panel.add(footer, BorderLayout.SOUTH);

        window.setContentPane(panel);
        window.setMinimumSize(new Dimension(490, 415));

        window.pack();
        window.setLocationByPlatform(true);

        models.setSelectedItem(
                vehicle.getConfiguration().model
        );

        types.setSelectedItem(
                vehicle.getConfiguration().type
        );

        updateVariants();

        variants.setSelectedItem(
                vehicle.getConfiguration()
        );

        updateDetails();
        window.setVisible(true);
    }

    private void updateVariants() {
        TeslaSpec.Model model =
                (TeslaSpec.Model) models.getSelectedItem();

        TeslaSpec.Type type =
                (TeslaSpec.Type) types.getSelectedItem();

        if (model == null || type == null) {
            return;
        }

        List<TeslaSpec> options =
                TeslaSpec.options(model, type);

        variants.removeAllItems();

        for (TeslaSpec spec : options) {
            variants.addItem(spec);
        }

        variants.setEnabled(!options.isEmpty());

        if (options.isEmpty()) {
            details.setText(
                    "<html><b>No Tesla production configuration</b>"
                            + "<br>" + model.label
                            + " has no published " + type.label
                            + " configuration in this data set."
                            + "<br>Try Long Range or Performance instead."
                            + "</html>"
            );
        } else {
            updateDetails();
        }
    }

    private void updateDetails() {
        TeslaSpec spec =
                (TeslaSpec) variants.getSelectedItem();

        if (spec != null) {
            details.setText(spec.details());
        }
    }
}
