package engine;

import engine.camera.Camera;
import engine.event.EventBus;
import engine.event.events.GuiToggleRequestedEvent;
import engine.event.events.InventoryOpenChangedEvent;
import engine.inventory.InventorySystem;
import engine.inventory.InventoryUI;
import engine.loop.GameLoop;
import engine.render.Renderer;
import engine.ui.GamePanel;
import gui.ClickGUI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import objects.GameObject;
import objects.dynamic.Body;
import sound.SoundEngine;

public class GameEngine extends GamePanel implements Runnable {

    private static final long serialVersionUID = 1L;

    public static final int WIDTH = EngineSettings.WIDTH;
    public static final int HEIGHT = EngineSettings.HEIGHT;

    public final EventBus eventBus;
    public final Camera camera;
    public final InputHandler inputHandler;
    public final Renderer renderer;
    public final SoundEngine soundEngine;
    public final ClickGUI clickGUI;
    public final CopyOnWriteArrayList<GameObject> rootObjects;
    public final InventorySystem inventorySystem;
    public final InventoryUI inventoryUI;
    public final AssetManager assetManager;

    private final EngineContext context;
    private final GameLoop gameLoop;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    public GameEngine() {
        context = new EngineContext(this);

        eventBus = new EventBus();
        context.eventBus = eventBus;

        rootObjects = new CopyOnWriteArrayList<>();

        camera = new Camera(0.0, 0.0, 0.0, 0.0, 0.0, this);
        context.camera = camera;

        final int processors = Runtime.getRuntime().availableProcessors();
        context.maximumUpdateWorkers = Math.max(1, processors - 2);

        final ThreadFactory updateThreadFactory = new ThreadFactory() {
            private final ThreadFactory delegate = Executors.defaultThreadFactory();
            private final AtomicInteger nextId = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable task) {
                final Thread thread = delegate.newThread(task);

                thread.setName("EngineWorker-" + nextId.getAndIncrement());
                thread.setDaemon(true);

                return thread;
            }
        };

        context.updatePool = Executors.newFixedThreadPool(context.maximumUpdateWorkers, updateThreadFactory);

        renderer = new Renderer(camera, this, context.updatePool, context.maximumUpdateWorkers);
        context.renderer = renderer;

        soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        soundEngine.setMasterVolume(0.25);
        context.soundEngine = soundEngine;

        clickGUI = new ClickGUI(this);
        context.clickGUI = clickGUI;

        inputHandler = new InputHandler(eventBus, this);
        context.inputHandler = inputHandler;

        assetManager = new AssetManager();
        assetManager.loadAllMeshes();
        context.assetManager = assetManager;

        GameObject.setPublishedFrameIndex(0);

        context.playerBody = new Body(Camera.WIDTH, Camera.HEIGHT);
        context.playerBody.setFull(false);
        context.playerBody.getTransform().position.x = camera.x;
        context.playerBody.getTransform().position.y = camera.y;
        context.playerBody.getTransform().position.z = camera.z;

        context.scene = new engine.scene.Scene(context, rootObjects);
        context.scene.addRootObjectImmediate(context.playerBody);

        context.playerController = new engine.systems.PlayerController(camera, eventBus, context.playerBody);

        inventorySystem = new InventorySystem(this, eventBus);
        context.inventorySystem = inventorySystem;

        inventoryUI = inventorySystem.getUI();
        context.inventoryUI = inventoryUI;

        initialize(context);

        inputHandler.centerCursor(this);

        context.scene.initializeGameObjects();

        context.addSubscription(
                eventBus.subscribe(GuiToggleRequestedEvent.class, event -> {
                    clickGUI.setOpen(!clickGUI.isOpen());
                    onGuiToggled(clickGUI.isOpen());
                })
        );

        context.addSubscription(eventBus.subscribe(InventoryOpenChangedEvent.class, event -> onInventoryToggled(event.open)));

        gameLoop = new GameLoop(context, this);
        gameLoop.start();
    }

    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        gameLoop.shutdown();
        context.shutdown();
    }

    @Override
    public void run() {
        gameLoop.run();
    }

    public Body getPlayerBody() {
        return context.playerBody;
    }

    public boolean isFirstPerson() {
        return camera.isFirstPerson();
    }

    public double getFovRadians() {
        return context.settings.getFieldOfViewRadians();
    }

    public void setFovDegrees(double degrees) {
        context.settings.setFieldOfViewDegrees(degrees);
    }

    public double getRenderDistance() {
        return context.settings.getRenderDistance();
    }

    public void setRenderDistance(double distance) {
        context.settings.setRenderDistance(distance);
    }

    public void addRootObject(GameObject object) {
        context.scene.addRootObject(object);
    }

    public void removeRootObject(GameObject object) {
        context.scene.removeRootObject(object);
    }

    public void queryNearbyCollidersXZ(double minX, double maxX, double minZ, double maxZ, ArrayList<GameObject> output) {
        context.scene.queryNearbyCollidersXZ(minX, maxX, minZ, maxZ, output);
    }

    public void queryNearbyRenderablesXZ(double minX, double maxX, double minZ, double maxZ, ArrayList<GameObject> output) {
        context.scene.queryNearbyRenderablesXZ(minX, maxX, minZ, maxZ, output);
    }

    public List<GameObject> getColliders() {
        return context.scene.getColliders();
    }
}