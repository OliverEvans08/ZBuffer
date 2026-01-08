package engine.inventory;


import engine.render.Material;
import objects.GameObject;
import objects.fixed.Cube;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ItemRegistry {
    private ItemRegistry() {}

    private static final Map<String, ItemDefinition> ITEMS = new LinkedHashMap<>();

    public static ItemDefinition get(String id) {
        return ITEMS.get(id);
    }

    public static void register(ItemDefinition def) {
        if (def == null) return;
        ITEMS.put(def.getId(), def);
    }

    // === Example items (you can add more) ===
    static {
        register(new ItemDefinition("blue_cube", "Blue Cube") {
            @Override public GameObject createWorldModel() {
                Cube c = new Cube(0.32, 0, 0, 0);
                c.setFull(false);
                c.setMaterial(Material.solid(new Color(80, 140, 255)).setAmbient(0.35).setDiffuse(0.65));
                return c;
            }

            @Override public GameObject createFirstPersonModel() {
                Cube c = new Cube(0.22, 0, 0, 0);
                c.setFull(false);
                c.setMaterial(Material.solid(new Color(80, 140, 255)).setAmbient(0.35).setDiffuse(0.65));
                return c;
            }

            @Override public GameObject createThirdPersonModel() {
                Cube c = new Cube(0.18, 0, 0, 0);
                c.setFull(false);
                c.setMaterial(Material.solid(new Color(80, 140, 255)).setAmbient(0.35).setDiffuse(0.65));
                return c;
            }
        });

        register(new ItemDefinition("red_cube", "Red Cube") {
            @Override public GameObject createWorldModel() {
                Cube c = new Cube(0.32, 0, 0, 0);
                c.setFull(false);
                c.setMaterial(Material.solid(new Color(240, 70, 70)).setAmbient(0.35).setDiffuse(0.65));
                return c;
            }

            @Override public GameObject createFirstPersonModel() {
                Cube c = new Cube(0.22, 0, 0, 0);
                c.setFull(false);
                c.setMaterial(Material.solid(new Color(240, 70, 70)).setAmbient(0.35).setDiffuse(0.65));
                return c;
            }

            @Override public GameObject createThirdPersonModel() {
                Cube c = new Cube(0.18, 0, 0, 0);
                c.setFull(false);
                c.setMaterial(Material.solid(new Color(240, 70, 70)).setAmbient(0.35).setDiffuse(0.65));
                return c;
            }
        });
    }
}
