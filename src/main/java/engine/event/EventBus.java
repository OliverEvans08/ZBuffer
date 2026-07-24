package engine.event;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Exact-type event bus optimized for many publishes and relatively rare
 * subscription changes.
 */
public final class EventBus {

    private final ConcurrentHashMap<
            Class<? extends GameEvent>,
            CopyOnWriteArrayList<Consumer<GameEvent>>
            > listeners = new ConcurrentHashMap<>();

    public <T extends GameEvent> Subscription subscribe(
            Class<T> eventType,
            Consumer<? super T> consumer
    ) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(consumer, "consumer");

        final Consumer<GameEvent> adapted =
                event -> consumer.accept(eventType.cast(event));

        final CopyOnWriteArrayList<Consumer<GameEvent>> subscribers =
                listeners.computeIfAbsent(
                        eventType,
                        ignored -> new CopyOnWriteArrayList<>()
                );

        subscribers.add(adapted);

        return new Subscription(() ->
                listeners.computeIfPresent(
                        eventType,
                        (ignored, current) -> {
                            current.remove(adapted);
                            return current.isEmpty() ? null : current;
                        }
                )
        );
    }

    public void publish(GameEvent event) {
        Objects.requireNonNull(event, "event");

        final CopyOnWriteArrayList<Consumer<GameEvent>> subscribers =
                listeners.get(event.getClass());

        if (subscribers == null) {
            return;
        }

        RuntimeException firstFailure = null;

        for (Consumer<GameEvent> consumer : subscribers) {
            try {
                consumer.accept(event);
            } catch (RuntimeException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public int subscriberCount(
            Class<? extends GameEvent> eventType
    ) {
        final CopyOnWriteArrayList<Consumer<GameEvent>> subscribers =
                listeners.get(
                        Objects.requireNonNull(eventType, "eventType")
                );

        return subscribers == null ? 0 : subscribers.size();
    }

    public void clear() {
        listeners.clear();
    }

    /** Idempotent subscription handle. */
    public static final class Subscription implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable unsubscribe;

        private Subscription(Runnable unsubscribe) {
            this.unsubscribe = unsubscribe;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                unsubscribe.run();
            }
        }
    }
}