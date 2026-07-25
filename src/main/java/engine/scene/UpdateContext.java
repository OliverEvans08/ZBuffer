package engine.scene;

import engine.ColliderIndex;
import engine.SpatialIndex;

public final class UpdateContext {

    public final ColliderIndex.SyncBuffer colliders =
            new ColliderIndex.SyncBuffer();

    public final SpatialIndex.SyncBuffer renderables =
            new SpatialIndex.SyncBuffer();

    public final double[] temporaryPoint = new double[3];

    public int writeFrame;
}