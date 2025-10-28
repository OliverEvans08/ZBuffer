package engine.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lightweight typed event bus.
 */
public class EventBus {
    private final Map<Class<?>, List<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    public <T extends GameEvent> AutoCloseable subscribe(Class<T> type, Consumer<T> consumer) {
        listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(consumer);
        return () -> listeners.getOrDefault(type, List.of()).remove(consumer);
    }

    @SuppressWarnings("unchecked")
    public void publish(GameEvent event) {
        List<Consumer<?>> subs = listeners.get(event.getClass());
        if (subs == null) return;
        for (Consumer<?> raw : subs) {
            Consumer<GameEvent> c = (Consumer<GameEvent>) raw;
            c.accept(event);
        }
    }
}
