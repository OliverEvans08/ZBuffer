package engine.inventory;

import engine.Camera;
import engine.GameEngine;
import engine.event.EventBus;
import engine.event.events.*;
import engine.render.Material;
import objects.GameObject;
import util.AABB;
import util.Vector3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

public final class InventorySystem {

    private final GameEngine engine;
    private final EventBus bus;

    private final Inventory inventory;
    private final InventoryUI ui;

    private final Map<GameObject, DropState> drops =
            java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    private DropState lookedAt = null;

    private GameObject held;
    private GameObject handSocketCached;

    private String cachedHeldId = null;
    private boolean heldDirty = true;

    private double swingT = 0.0;
    private static final double SWING_DUR = 0.18;

    private static final double PICKUP_RANGE = 2.2;
    private static final double LOOK_DOT_MIN = 0.965;

    private static final double HELD_GRIP_FORWARD = 0.08;
    private static final double HELD_GRIP_UP      = 0.00;
    private static final double HELD_GRIP_RIGHT   = 0.00;

    private static final double DROP_GRAVITY = -30.0;
    private static final double DROP_BOB_AMP = 0.030;
    private static final double DROP_BOB_FREQ = 2.0;
    private static final double DROP_SPIN_SPEED = 1.8;
    private static final double DROP_EPS = 1e-6;
    private static final double DROP_XZ_PAD = 0.03;

    private static final double WORLD_GROUND_Y = 0.0;

    private final java.util.ArrayList<GameObject> tmpColliders = new java.util.ArrayList<>(256);

    public InventorySystem(GameEngine engine, EventBus bus) {
        this.engine = engine;
        this.bus = bus;

        this.inventory = new Inventory(9, 32);
        this.ui = new InventoryUI(engine, this);

        this.handSocketCached = resolveBestHandSocket();

        bus.subscribe(InventoryToggleRequestedEvent.class, e -> {
            ui.toggle();
            bus.publish(new InventoryOpenChangedEvent(ui.isOpen()));
        });

        bus.subscribe(HotbarSelectRequestedEvent.class, e -> {
            inventory.setSelectedHotbar(e.index);
            onSelectionOrContentsChanged();
        });

        bus.subscribe(DropHeldItemRequestedEvent.class, e -> dropSelected());
        bus.subscribe(PickupRequestedEvent.class, e -> pickupLookedAt());
        bus.subscribe(UseHeldItemRequestedEvent.class, e -> useSelected());
    }

    public InventoryUI getUI() { return ui; }
    public Inventory getInventory() { return inventory; }

    public void seedStartingInventory() {
        inventory.setHotbar(0, new ItemInstance(ItemRegistry.get("blue_cube")));
        inventory.setHotbar(1, new ItemInstance(ItemRegistry.get("red_cube")));
        inventory.setSelectedHotbar(0);
        onSelectionOrContentsChanged();
    }

    public void spawnItemsOnInit() {
        spawnWorldItem(ItemRegistry.get("blue_cube"), 0.8, 1.2, 1.6);
        spawnWorldItem(ItemRegistry.get("red_cube"), -0.8, 1.2, 2.0);
    }

    public void update(double dt) {
        updateUseSwing(dt);
        updateWorldDrops(dt);
        updateLookedAt();
        updateHeldModel(dt);
    }

    public void onSelectionOrContentsChanged() {
        heldDirty = true;
    }

    public void returnCursorItem(ItemInstance cursor) {
        if (cursor == null) return;

        if (inventory.addItem(cursor)) {
            onSelectionOrContentsChanged();
            return;
        }

        dropItemInstanceNearPlayer(cursor, 1.2);
        onSelectionOrContentsChanged();
    }

    public String getPickupPromptText() {
        if (ui.isOpen()) return null;
        if (lookedAt == null) return null;
        return "F: Pick up " + lookedAt.item.getDef().getDisplayName();
    }

    private static final class DropState {
        final ItemInstance item;
        final GameObject model;

        double baseY;
        double yVel;
        boolean onGround;

        double bottomOffset;

        double t0;

        DropState(ItemInstance item, GameObject model) {
            this.item = item;
            this.model = model;
        }
    }

    private DropState spawnWorldItem(ItemDefinition def, double x, double y, double z) {
        if (def == null) return null;

        GameObject model = def.createWorldModel();
        if (model == null) return null;

        model.setFull(false);
        if (model.getMaterial() == null) {
            model.setMaterial(Material.solid(new java.awt.Color(200, 200, 200)));
        }

        model.getTransform().position.x = x;
        model.getTransform().position.y = y;
        model.getTransform().position.z = z;

        AABB a = model.getWorldAABB();
        double posY = model.getWorldPosition().y;
        double bottomOffset = a.minY - posY;

        double minBaseY = WORLD_GROUND_Y - bottomOffset;
        if (y < minBaseY) {
            y = minBaseY;
            model.getTransform().position.y = y;
        }

        engine.addRootObject(model);

        DropState st = new DropState(new ItemInstance(def), model);
        st.baseY = y;
        st.yVel = 0.0;
        st.bottomOffset = bottomOffset;
        st.t0 = Math.random() * 10.0;

        settleDropAgainstWorld(st);

        drops.put(model, st);
        return st;
    }

    private void dropItemInstanceNearPlayer(ItemInstance it, double tossUpVel) {
        if (it == null) return;

        Camera cam = engine.camera;

        double fx = cam.getForwardX();
        double fy = cam.getForwardY();
        double fz = cam.getForwardZ();

        double sx = cam.x + fx * 0.95;
        double sy = cam.y + Camera.EYE_HEIGHT - 0.25;
        double sz = cam.z + fz * 0.95;

        DropState st = spawnWorldItem(it.getDef(), sx, sy, sz);
        if (st != null) {
            st.yVel = tossUpVel;
            st.onGround = false;
            settleDropAgainstWorld(st);
        }
    }

    private void dropSelected() {
        if (ui.isOpen()) return;

        ItemInstance sel = inventory.getSelectedItem();
        if (sel == null) return;

        inventory.removeSelectedItem();
        onSelectionOrContentsChanged();

        dropItemInstanceNearPlayer(sel, 2.0);
    }

    private void pickupLookedAt() {
        if (ui.isOpen()) return;

        DropState target = lookedAt;
        if (target == null || target.model == null) return;

        // FIX: use gameplay aim origin (player head), not the third-person camera position.
        double ex = engine.camera.getAimX();
        double ey = engine.camera.getAimY();
        double ez = engine.camera.getAimZ();

        Vector3 wp = target.model.getWorldPosition();
        double dx = wp.x - ex;
        double dy = wp.y - ey;
        double dz = wp.z - ez;
        double d2 = dx*dx + dy*dy + dz*dz;
        if (d2 > PICKUP_RANGE * PICKUP_RANGE) return;

        if (!inventory.hasSpace()) return;
        if (!inventory.addItem(target.item)) return;

        engine.removeRootObject(target.model);
        drops.remove(target.model);
        lookedAt = null;

        onSelectionOrContentsChanged();
    }

    private void updateWorldDrops(double dt) {
        if (drops.isEmpty()) return;

        synchronized (drops) {
            for (DropState st : drops.values()) {
                if (st == null || st.model == null) continue;

                double t = st.t0 + (System.nanoTime() / 1_000_000_000.0);
                st.model.getTransform().rotation.y += DROP_SPIN_SPEED * dt;

                double bob = Math.sin(t * DROP_BOB_FREQ) * DROP_BOB_AMP;

                if (st.onGround) {
                    st.model.getTransform().position.y = st.baseY + bob;
                }

                if (!st.onGround) {
                    st.yVel += DROP_GRAVITY * dt;
                    st.model.getTransform().position.y += st.yVel * dt;

                    settleDropAgainstWorld(st);
                }
            }
        }
    }

    private void settleDropAgainstWorld(DropState st) {
        if (st == null || st.model == null) return;

        AABB a = st.model.getWorldAABB();

        double minX = a.minX - DROP_XZ_PAD;
        double maxX = a.maxX + DROP_XZ_PAD;
        double minZ = a.minZ - DROP_XZ_PAD;
        double maxZ = a.maxZ + DROP_XZ_PAD;

        engine.queryNearbyCollidersXZ(minX, maxX, minZ, maxZ, tmpColliders);

        double bestTop = WORLD_GROUND_Y;
        boolean found = false;

        for (GameObject g : tmpColliders) {
            if (g == null) continue;
            if (!g.isFull()) continue;
            if (g == engine.getPlayerBody()) continue;

            AABB b = g.getWorldAABB();

            if (a.maxX <= b.minX || a.minX >= b.maxX) continue;
            if (a.maxZ <= b.minZ || a.minZ >= b.maxZ) continue;

            double top = b.maxY;
            if (top > bestTop) {
                bestTop = top;
                found = true;
            }
        }

        double desiredBaseY = bestTop - st.bottomOffset;

        double minBaseY = WORLD_GROUND_Y - st.bottomOffset;
        if (desiredBaseY < minBaseY) desiredBaseY = minBaseY;

        double bottomY = a.minY;
        if (bottomY <= bestTop + DROP_EPS) {
            st.baseY = desiredBaseY;
            st.model.getTransform().position.y = st.baseY;
            st.yVel = 0.0;
            st.onGround = true;
        } else {
            st.onGround = false;
        }
    }

    private void updateLookedAt() {
        lookedAt = null;
        if (ui.isOpen()) return;
        if (drops.isEmpty()) return;

        // FIX: use gameplay aim origin (player head), not the third-person camera position.
        double ex = engine.camera.getAimX();
        double ey = engine.camera.getAimY();
        double ez = engine.camera.getAimZ();

        double fx = engine.camera.getForwardX();
        double fy = engine.camera.getForwardY();
        double fz = engine.camera.getForwardZ();

        double bestDot = LOOK_DOT_MIN;
        DropState best = null;

        synchronized (drops) {
            for (DropState st : drops.values()) {
                if (st == null || st.model == null) continue;

                Vector3 p = st.model.getWorldPosition();
                double dx = p.x - ex;
                double dy = p.y - ey;
                double dz = p.z - ez;

                double d2 = dx*dx + dy*dy + dz*dz;
                if (d2 > PICKUP_RANGE * PICKUP_RANGE) continue;

                double d = Math.sqrt(Math.max(1e-12, d2));
                double inv = 1.0 / d;
                double nx = dx * inv, ny = dy * inv, nz = dz * inv;

                double dot = nx*fx + ny*fy + nz*fz;
                if (dot > bestDot) {
                    bestDot = dot;
                    best = st;
                }
            }
        }

        lookedAt = best;
    }

    private void useSelected() {
        if (ui.isOpen()) return;

        ItemInstance sel = inventory.getSelectedItem();
        if (sel != null) {
            sel.getDef().onUse(new ItemDefinition.ItemUseContext(this));
        }

        swingT = SWING_DUR;
    }

    private void updateUseSwing(double dt) {
        if (swingT > 0.0) {
            swingT -= dt;
            if (swingT < 0.0) swingT = 0.0;
        }
    }

    private void updateHeldModel(double dt) {
        ItemInstance sel = inventory.getSelectedItem();
        String wantId = (sel == null ? null : sel.getDef().getId());

        if (heldDirty || !Objects.equals(wantId, cachedHeldId)) {
            detach(held);
            held = null;

            cachedHeldId = wantId;
            heldDirty = false;

            if (sel != null) {
                held = sel.getDef().createThirdPersonModel();
                if (held != null) {
                    held.setFull(false);

                    GameObject socket = resolveBestHandSocket();
                    if (socket == null) socket = engine.getPlayerBody();
                    safeAddChild(socket, held);

                    applyDefaultGripToHeld(held);

                    held.setVisible(true);
                }
            }
        }

        if (held != null) {
            held.setVisible(true);

            if (swingT > 0.0) {
                double u = swingT / SWING_DUR;
                double s = Math.sin((1.0 - u) * Math.PI);
                held.getTransform().rotation.x = -0.55 * s;
            } else {
                held.getTransform().rotation.x = 0.0;
            }
        }
    }

    private void applyDefaultGripToHeld(GameObject h) {
        if (h == null) return;

        h.getTransform().position.x = HELD_GRIP_RIGHT;
        h.getTransform().position.y = HELD_GRIP_UP;
        h.getTransform().position.z = HELD_GRIP_FORWARD;

        h.getTransform().rotation.x = 0.0;
        h.getTransform().rotation.y = 0.0;
        h.getTransform().rotation.z = 0.0;
    }

    private GameObject resolveBestHandSocket() {
        GameObject pb = engine.getPlayerBody();
        if (pb == null) return null;

        if (handSocketCached != null && isDescendantOrSelf(handSocketCached, pb)) {
            return handSocketCached;
        }

        Camera cam = engine.camera;

        double baseX = cam.x;
        double baseY = cam.y + Camera.EYE_HEIGHT * 0.62;
        double baseZ = cam.z;

        double rx = cam.getRightX(), ry = cam.getRightY(), rz = cam.getRightZ();
        double fx = cam.getForwardX(), fy = cam.getForwardY(), fz = cam.getForwardZ();

        double tx = baseX + rx * 0.35 + fx * 0.10;
        double ty = baseY + ry * 0.35 + fy * 0.10;
        double tz = baseZ + rz * 0.35 + fz * 0.10;

        GameObject best = null;
        double bestD2 = Double.POSITIVE_INFINITY;

        Deque<GameObject> q = new ArrayDeque<>();
        q.add(pb);

        while (!q.isEmpty()) {
            GameObject g = q.removeFirst();
            if (g == null) continue;

            if (g != pb) {
                Vector3 wp = g.getWorldPosition();
                double dx = wp.x - tx;
                double dy = wp.y - ty;
                double dz = wp.z - tz;
                double d2 = dx*dx + dy*dy + dz*dz;

                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = g;
                }
            }

            java.util.List<GameObject> kids = g.getChildren();
            if (kids != null && !kids.isEmpty()) {
                for (int i = 0; i < kids.size(); i++) q.addLast(kids.get(i));
            }
        }

        handSocketCached = best;
        return handSocketCached;
    }

    private static boolean isDescendantOrSelf(GameObject node, GameObject root) {
        if (node == null || root == null) return false;
        for (GameObject p = node; p != null; p = p.getParent()) {
            if (p == root) return true;
        }
        return false;
    }

    private static void safeAddChild(GameObject parent, GameObject child) {
        if (parent == null || child == null) return;

        GameObject p = child.getParent();
        if (p != null && p != parent) {
            try { p.removeChild(child); } catch (Throwable ignored) {}
        }

        try {
            parent.addChild(child);
        } catch (Throwable t) {
            throw new RuntimeException("GameObject.addChild/removeChild not available or failed.", t);
        }
    }

    private static void detach(GameObject child) {
        if (child == null) return;
        GameObject p = child.getParent();
        if (p != null) {
            try { p.removeChild(child); } catch (Throwable ignored) {}
        }
    }
}
