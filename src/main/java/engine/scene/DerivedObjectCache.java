package engine.scene;

import java.util.IdentityHashMap;
import objects.GameObject;

public final class DerivedObjectCache {

    private final IdentityHashMap<GameObject, DerivedCacheEntry> entries =
            new IdentityHashMap<>(8192);

    public DerivedCacheEntry get(GameObject object) {
        return entries.get(object);
    }

    public DerivedCacheEntry cacheFor(GameObject object) {
        DerivedCacheEntry cache = entries.get(object);

        if (cache == null) {
            cache = new DerivedCacheEntry();
            entries.put(object, cache);
        }

        return cache;
    }

    public void remove(GameObject object) {
        entries.remove(object);
    }
}