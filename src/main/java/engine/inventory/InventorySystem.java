package engine.inventory;

import engine.GameEngine;
import engine.camera.Camera;
import engine.event.EventBus;
import engine.event.events.DropHeldItemRequestedEvent;
import engine.event.events.HotbarSelectRequestedEvent;
import engine.event.events.InventoryOpenChangedEvent;
import engine.event.events.InventoryToggleRequestedEvent;
import engine.event.events.PickupRequestedEvent;
import engine.event.events.ToggleViewRequestedEvent;
import engine.event.events.UseHeldItemRequestedEvent;
import engine.render.Material;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import objects.GameObject;
import util.AABB;

public final class InventorySystem implements AutoCloseable {

    private static final double SWING_DURATION = 0.18;

    private static final double PICKUP_RANGE = 2.2;
    private static final double LOOK_DOT_MINIMUM = 0.965;

    private static final double HELD_GRIP_FORWARD = 0.10;
    private static final double HELD_GRIP_UP = 0.02;
    private static final double HELD_GRIP_RIGHT = 0.00;

    private static final double DROP_GRAVITY = -30.0;
    private static final double DROP_BOB_AMPLITUDE = 0.030;
    private static final double DROP_BOB_FREQUENCY = 2.0;
    private static final double DROP_SPIN_SPEED = 1.8;
    private static final double DROP_EPSILON = 1.0e-6;
    private static final double DROP_XZ_PADDING = 0.03;

    private static final double WORLD_GROUND_Y = 0.0;
    private static final double DROP_TRIANGLE_PADDING = 0.02;
    private static final double DROP_FLOOR_NORMAL_Y = 0.65;
    private static final double DROP_SUPPORT_EPSILON = 0.06;

    private final GameEngine engine;
    private final EventBus eventBus;
    private final Inventory inventory;
    private final InventoryUI ui;

    private final Map<GameObject, DropState> drops = Collections.synchronizedMap(new IdentityHashMap<>());
    private final ArrayDeque<GameObject> retireQueue = new ArrayDeque<>();
    private final ArrayList<GameObject> temporaryColliders = new ArrayList<>(256);
    private final List<EventBus.Subscription> subscriptions = new ArrayList<>(6);

    private volatile DropState lookedAt;
    private volatile String pickupPromptText;
    private volatile boolean heldDirty = true;

    private GameObject held;
    private GameObject cachedHandSocket;
    private String cachedHeldId;

    private double swingTime;
    private double dropTimeSeconds;

    public InventorySystem(GameEngine engine, EventBus eventBus) {
        this.engine = engine;
        this.eventBus = eventBus;
        this.inventory = new Inventory(9, 32);
        this.ui = new InventoryUI(engine, this);

        cachedHandSocket = resolveBestHandSocket();

        subscriptions.add(
                eventBus.subscribe(InventoryToggleRequestedEvent.class, event -> {
                    ui.toggle();
                    eventBus.publish(new InventoryOpenChangedEvent(ui.isOpen()));
                })
        );

        subscriptions.add(
                eventBus.subscribe(HotbarSelectRequestedEvent.class, event -> {
                    inventory.setSelectedHotbar(event.index);
                    onSelectionOrContentsChanged();
                })
        );

        subscriptions.add(eventBus.subscribe(DropHeldItemRequestedEvent.class, event -> dropSelected()));
        subscriptions.add(eventBus.subscribe(PickupRequestedEvent.class, event -> pickupLookedAt()));
        subscriptions.add(eventBus.subscribe(UseHeldItemRequestedEvent.class, event -> useSelected()));
        subscriptions.add(eventBus.subscribe(ToggleViewRequestedEvent.class, event -> onSelectionOrContentsChanged()));
    }

    public InventoryUI getUI() {
        return ui;
    }

    public Inventory getInventory() {
        return inventory;
    }

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

    public void update(double deltaSeconds) {
        dropTimeSeconds += deltaSeconds;

        flushRetiredHeld();
        updateUseSwing(deltaSeconds);
        updateWorldDrops(deltaSeconds);
        updateLookedAt();
        updateHeldModel();
    }

    public void onSelectionOrContentsChanged() {
        heldDirty = true;
    }

    public void returnCursorItem(ItemInstance cursor) {
        if (cursor == null) {
            return;
        }

        if (inventory.addItem(cursor)) {
            onSelectionOrContentsChanged();
            return;
        }

        dropItemInstanceNearPlayer(cursor, 1.2);
        onSelectionOrContentsChanged();
    }

    public String getPickupPromptText() {
        if (ui.isOpen()) {
            return null;
        }

        return pickupPromptText;
    }

    private DropState spawnWorldItem(ItemDefinition definition, double x, double y, double z) {
        if (definition == null) {
            return null;
        }

        final GameObject model = definition.createWorldModel();

        if (model == null) {
            return null;
        }

        model.setFull(false);
        model.setIgnorePlayerCollisions(true);

        if (model.getMaterial() == null) {
            model.setMaterial(Material.solid(new Color(200, 200, 200)));
        }

        model.getTransform().position.x = x;
        model.getTransform().position.y = y;
        model.getTransform().position.z = z;

        final AABB bounds = model.getWorldAABB();
        final double positionY = model.getWorldY();
        final double bottomOffset = bounds.minY - positionY;
        final double minimumBaseY = WORLD_GROUND_Y - bottomOffset;

        if (y < minimumBaseY) {
            y = minimumBaseY;
            model.getTransform().position.y = y;
        }

        engine.addRootObject(model);

        final DropState state = new DropState(new ItemInstance(definition), model);

        state.baseY = y;
        state.yVelocity = 0.0;
        state.bottomOffset = bottomOffset;
        state.timeOffset = ThreadLocalRandom.current().nextDouble(10.0);
        state.previousY = y;

        settleDropAgainstWorld(state);
        drops.put(model, state);

        return state;
    }

    private void dropItemInstanceNearPlayer(ItemInstance item, double upwardVelocity) {
        if (item == null) {
            return;
        }

        final Camera camera = engine.camera;

        final double forwardX = camera.getForwardX();
        final double forwardY = camera.getForwardY();
        final double forwardZ = camera.getForwardZ();

        final double spawnX = camera.x + forwardX * 0.95;
        final double spawnY = camera.y + Camera.EYE_HEIGHT - 0.25;
        final double spawnZ = camera.z + forwardZ * 0.95;

        final DropState state = spawnWorldItem(item.getDef(), spawnX, spawnY, spawnZ);

        if (state != null) {
            state.yVelocity = upwardVelocity;
            state.onGround = false;
            settleDropAgainstWorld(state);
        }
    }

    private void dropSelected() {
        if (ui.isOpen()) {
            return;
        }

        final ItemInstance selected = inventory.getSelectedItem();

        if (selected == null) {
            return;
        }

        inventory.removeSelectedItem();
        onSelectionOrContentsChanged();
        dropItemInstanceNearPlayer(selected, 2.0);
    }

    private void pickupLookedAt() {
        if (ui.isOpen()) {
            return;
        }

        final DropState target = lookedAt;

        if (target == null || target.model == null) {
            return;
        }

        final double eyeX = engine.camera.getAimX();
        final double eyeY = engine.camera.getAimY();
        final double eyeZ = engine.camera.getAimZ();

        final double deltaX = target.model.getWorldX() - eyeX;
        final double deltaY = target.model.getWorldY() - eyeY;
        final double deltaZ = target.model.getWorldZ() - eyeZ;
        final double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;

        if (distanceSquared > PICKUP_RANGE * PICKUP_RANGE) {
            return;
        }

        if (!inventory.hasSpace() || !inventory.addItem(target.item)) {
            return;
        }

        engine.removeRootObject(target.model);
        drops.remove(target.model);

        lookedAt = null;
        pickupPromptText = null;

        onSelectionOrContentsChanged();
    }

    private void updateWorldDrops(double deltaSeconds) {
        if (drops.isEmpty()) {
            return;
        }

        final double timeBase = dropTimeSeconds;

        synchronized (drops) {
            for (DropState state : drops.values()) {
                if (state == null || state.model == null) {
                    continue;
                }

                state.previousY = state.model.getTransform().position.y;

                final double time = state.timeOffset + timeBase;

                state.model.getTransform().rotation.y += DROP_SPIN_SPEED * deltaSeconds;

                final double bob = Math.sin(time * DROP_BOB_FREQUENCY) * DROP_BOB_AMPLITUDE;

                if (state.onGround) {
                    state.model.getTransform().position.y = state.baseY + bob;
                    continue;
                }

                state.yVelocity += DROP_GRAVITY * deltaSeconds;
                state.model.getTransform().position.y += state.yVelocity * deltaSeconds;

                settleDropAgainstWorld(state);
            }
        }
    }

    private void settleDropAgainstWorld(DropState state) {
        if (state == null || state.model == null) {
            return;
        }

        final AABB bounds = state.model.getWorldAABB();

        final double dropMinX = bounds.minX - DROP_XZ_PADDING;
        final double dropMaxX = bounds.maxX + DROP_XZ_PADDING;
        final double dropMinZ = bounds.minZ - DROP_XZ_PADDING;
        final double dropMaxZ = bounds.maxZ + DROP_XZ_PADDING;

        engine.queryNearbyCollidersXZ(dropMinX, dropMaxX, dropMinZ, dropMaxZ, temporaryColliders);

        final double previousBottom = state.previousY + state.bottomOffset;
        final double supportMaximumY = Math.max(previousBottom, bounds.minY) + DROP_SUPPORT_EPSILON;

        double bestFloorY = WORLD_GROUND_Y;

        for (GameObject object : temporaryColliders) {
            if (object == null || !object.isFull() || object == engine.getPlayerBody()) {
                continue;
            }

            final AABB colliderBounds = object.getWorldAABB();

            if (dropMaxX <= colliderBounds.minX || dropMinX >= colliderBounds.maxX || dropMaxZ <= colliderBounds.minZ || dropMinZ >= colliderBounds.maxZ) {
                continue;
            }

            final int[][] faces = object.getFacesArray();
            final double[][] worldVertices = object.getTransformedVertices();

            if (faces != null && worldVertices != null && faces.length > 0 && worldVertices.length > 0) {
                final double floor = findBestMeshFloorYForDrop(faces, worldVertices, dropMinX, dropMaxX, dropMinZ, dropMaxZ, supportMaximumY);

                if (floor > bestFloorY) {
                    bestFloorY = floor;
                }
            } else {
                final double top = colliderBounds.maxY;

                if (top <= supportMaximumY && top > bestFloorY) {
                    bestFloorY = top;
                }
            }
        }

        double desiredBaseY = bestFloorY - state.bottomOffset;

        final double minimumBaseY = WORLD_GROUND_Y - state.bottomOffset;

        if (desiredBaseY < minimumBaseY) {
            desiredBaseY = minimumBaseY;
        }

        if (bounds.minY <= bestFloorY + DROP_EPSILON) {
            state.baseY = desiredBaseY;
            state.model.getTransform().position.y = state.baseY;
            state.yVelocity = 0.0;
            state.onGround = true;
        } else {
            state.onGround = false;
        }
    }

    private static double findBestMeshFloorYForDrop(
            int[][] faces,
            double[][] worldVertices,
            double dropMinX,
            double dropMaxX,
            double dropMinZ,
            double dropMaxZ,
            double supportMaximumY
    ) {
        double best = Double.NEGATIVE_INFINITY;

        for (int[] face : faces) {
            if (face == null || face.length != 3) {
                continue;
            }

            final int index0 = face[0];
            final int index1 = face[1];
            final int index2 = face[2];

            if (index0 < 0 || index1 < 0 || index2 < 0 || index0 >= worldVertices.length || index1 >= worldVertices.length || index2 >= worldVertices.length) {
                continue;
            }

            final double[] a = worldVertices[index0];
            final double[] b = worldVertices[index1];
            final double[] c = worldVertices[index2];

            if (a == null || b == null || c == null) {
                continue;
            }

            final double ax = a[0];
            final double ay = a[1];
            final double az = a[2];

            final double bx = b[0];
            final double by = b[1];
            final double bz = b[2];

            final double cx = c[0];
            final double cy = c[1];
            final double cz = c[2];

            final double edge1X = bx - ax;
            final double edge1Y = by - ay;
            final double edge1Z = bz - az;

            final double edge2X = cx - ax;
            final double edge2Y = cy - ay;
            final double edge2Z = cz - az;

            final double normalX = edge1Y * edge2Z - edge1Z * edge2Y;
            final double normalY = edge1Z * edge2X - edge1X * edge2Z;
            final double normalZ = edge1X * edge2Y - edge1Y * edge2X;
            final double normalLengthSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;

            if (normalLengthSquared < 1.0e-24) {
                continue;
            }

            if (normalY * normalY < DROP_FLOOR_NORMAL_Y * DROP_FLOOR_NORMAL_Y * normalLengthSquared) {
                continue;
            }

            final double triangleMaximumY = max3(ay, by, cy);

            if (triangleMaximumY > supportMaximumY) {
                continue;
            }

            final double triangleMinX = min3(ax, bx, cx) - DROP_TRIANGLE_PADDING;
            final double triangleMaxX = max3(ax, bx, cx) + DROP_TRIANGLE_PADDING;
            final double triangleMinZ = min3(az, bz, cz) - DROP_TRIANGLE_PADDING;
            final double triangleMaxZ = max3(az, bz, cz) + DROP_TRIANGLE_PADDING;

            if (dropMaxX <= triangleMinX || dropMinX >= triangleMaxX || dropMaxZ <= triangleMinZ || dropMinZ >= triangleMaxZ) {
                continue;
            }

            if (triangleMaximumY > best) {
                best = triangleMaximumY;
            }
        }

        return best == Double.NEGATIVE_INFINITY ? WORLD_GROUND_Y : best;
    }

    private void updateLookedAt() {
        if (ui.isOpen()) {
            lookedAt = null;
            pickupPromptText = null;
            return;
        }

        final Camera camera = engine.camera;

        final double eyeX = camera.getAimX();
        final double eyeY = camera.getAimY();
        final double eyeZ = camera.getAimZ();

        final double forwardX = camera.getForwardX();
        final double forwardY = camera.getForwardY();
        final double forwardZ = camera.getForwardZ();

        final double maximumDistanceSquared = PICKUP_RANGE * PICKUP_RANGE;

        DropState best = null;
        double bestDistanceSquared = Double.POSITIVE_INFINITY;

        synchronized (drops) {
            for (DropState state : drops.values()) {
                if (state == null || state.model == null) {
                    continue;
                }

                final double vectorX = state.model.getWorldX() - eyeX;
                final double vectorY = state.model.getWorldY() - eyeY;
                final double vectorZ = state.model.getWorldZ() - eyeZ;

                double distanceSquared = vectorX * vectorX + vectorY * vectorY + vectorZ * vectorZ;

                if (distanceSquared > maximumDistanceSquared) {
                    continue;
                }

                if (distanceSquared < 1.0e-12) {
                    distanceSquared = 1.0e-12;
                }

                final double inverseDistance = 1.0 / Math.sqrt(distanceSquared);
                final double dot = (vectorX * forwardX + vectorY * forwardY + vectorZ * forwardZ) * inverseDistance;

                if (dot < LOOK_DOT_MINIMUM) {
                    continue;
                }

                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                    best = state;
                }
            }
        }

        if (lookedAt != best) {
            lookedAt = best;
            pickupPromptText = best == null ? null : "F: Pickup " + best.item.getDef().getDisplayName();
        }
    }

    private void useSelected() {
        if (ui.isOpen()) {
            return;
        }

        final ItemInstance selected = inventory.getSelectedItem();

        if (selected == null || selected.getDef() == null) {
            return;
        }

        selected.getDef().onUse(new ItemDefinition.ItemUseContext(this));
        swingTime = SWING_DURATION;
    }

    private void updateUseSwing(double deltaSeconds) {
        if (swingTime <= 0.0) {
            return;
        }

        swingTime -= deltaSeconds;

        if (swingTime < 0.0) {
            swingTime = 0.0;
        }
    }

    private void updateHeldModel() {
        final ItemInstance selected = inventory.getSelectedItem();
        final String wantedId = selected == null || selected.getDef() == null ? null : selected.getDef().getId();
        final boolean firstPerson = engine.isFirstPerson();

        if (heldDirty || (wantedId == null ? cachedHeldId != null : !wantedId.equals(cachedHeldId))) {
            rebuildHeldModel(selected, wantedId, firstPerson);
            heldDirty = false;
        }

        if (held == null) {
            return;
        }

        held.getTransform().position.x = HELD_GRIP_RIGHT;
        held.getTransform().position.y = HELD_GRIP_UP;
        held.getTransform().position.z = HELD_GRIP_FORWARD;

        if (swingTime > 0.0) {
            final double progress = swingTime / SWING_DURATION;
            final double swing = Math.sin(progress * Math.PI);

            held.getTransform().rotation.x = -0.55 * swing;
        } else {
            held.getTransform().rotation.x = 0.0;
        }
    }

    private void rebuildHeldModel(ItemInstance selected, String wantedId, boolean firstPerson) {
        if (held != null) {
            retireHeld(held);
            held = null;
        }

        cachedHeldId = wantedId;

        if (selected == null || selected.getDef() == null || firstPerson) {
            return;
        }

        if (cachedHandSocket == null) {
            cachedHandSocket = resolveBestHandSocket();
        }

        final GameObject socket = cachedHandSocket != null ? cachedHandSocket : engine.getPlayerBody();

        if (socket == null) {
            return;
        }

        final ItemDefinition definition = selected.getDef();

        GameObject model = definition.createThirdPersonModel();

        if (model == null) {
            model = definition.createWorldModel();
        }

        if (model == null) {
            return;
        }

        model.setFull(false);
        model.setSolid(false);
        model.setIgnorePlayerCollisions(true);

        socket.addChild(model);
        held = model;
    }

    private void retireHeld(GameObject object) {
        if (object == null) {
            return;
        }

        object.setSolid(false);
        object.setFull(false);
        object.setIgnorePlayerCollisions(true);
        object.setVisible(false);
        object.setActive(false);

        retireQueue.add(object);
    }

    private void flushRetiredHeld() {
        while (!retireQueue.isEmpty()) {
            final GameObject object = retireQueue.poll();

            if (object == null) {
                continue;
            }

            final GameObject parent = object.getParent();

            if (parent != null) {
                parent.removeChild(object);
            }
        }
    }

    private GameObject resolveBestHandSocket() {
        final GameObject playerBody = engine.getPlayerBody();

        if (playerBody == null) {
            return null;
        }

        final ArrayDeque<GameObject> queue = new ArrayDeque<>();

        queue.add(playerBody);

        while (!queue.isEmpty()) {
            final GameObject object = queue.poll();

            if (object == null) {
                continue;
            }

            final String name = object.getName();

            if (name != null) {
                final String normalized = name.toLowerCase(Locale.ROOT);

                if (normalized.contains("hand_socket") || normalized.contains("handsocket") || normalized.equals("hand")) {
                    return object;
                }
            }

            final List<GameObject> children = object.getChildren();

            if (children != null) {
                for (GameObject child : children) {
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        }

        return playerBody;
    }

    @Override
    public void close() {
        for (EventBus.Subscription subscription : subscriptions) {
            subscription.close();
        }

        subscriptions.clear();

        lookedAt = null;
        pickupPromptText = null;

        if (held != null) {
            retireHeld(held);
            held = null;
        }

        flushRetiredHeld();
        drops.clear();
    }

    private static double min3(double a, double b, double c) {
        return Math.min(a, Math.min(b, c));
    }

    private static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    private static final class DropState {

        private final ItemInstance item;
        private final GameObject model;

        private double baseY;
        private double yVelocity;
        private boolean onGround;

        private double bottomOffset;
        private double timeOffset;
        private double previousY;

        private DropState(ItemInstance item, GameObject model) {
            this.item = item;
            this.model = model;
        }
    }
}