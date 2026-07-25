package engine;

import engine.event.EventBus;
import engine.inventory.InventorySystem;
import engine.inventory.InventoryUI;
import engine.render.Renderer;
import engine.scene.Scene;
import engine.systems.PlayerController;
import gui.ClickGUI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import objects.dynamic.Body;
import sound.SoundEngine;

public final class EngineContext {

    public final GameEngine engine;
    public final EngineSettings settings;
    public EventBus eventBus;
    public engine.camera.Camera camera;
    public InputHandler inputHandler;
    public Renderer renderer;
    public SoundEngine soundEngine;
    public ClickGUI clickGUI;
    public InventorySystem inventorySystem;
    public InventoryUI inventoryUI;
    public AssetManager assetManager;
    public PlayerController playerController;
    public Body playerBody;
    public ExecutorService updatePool;
    public int maximumUpdateWorkers;
    public Scene scene;

    private final List<EventBus.Subscription> engineSubscriptions = new ArrayList<>(2);

    public EngineContext(GameEngine engine) {
        this.engine = engine;
        settings = new EngineSettings();
    }

    public void addSubscription(EventBus.Subscription subscription) {
        engineSubscriptions.add(subscription);
    }

    public void shutdown() {
        inputHandler.close();
        inventorySystem.close();
        playerController.close();

        for (EventBus.Subscription subscription : engineSubscriptions) {
            subscription.close();
        }

        engineSubscriptions.clear();

        renderer.shutdown();
        soundEngine.shutdown();
        updatePool.shutdownNow();
        eventBus.clear();
    }
}