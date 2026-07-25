package engine.spatial;

import engine.collections.LongBucketMap;
import java.util.List;
import objects.GameObject;

record SpatialHashSnapshot(
        LongBucketMap cells,
        List<GameObject> objects,
        List<GameObject> giants
) {

    static final SpatialHashSnapshot EMPTY =
            new SpatialHashSnapshot(
                    LongBucketMap.EMPTY,
                    List.of(),
                    List.of()
            );
}