package engine.scene;

import objects.GameObject;

public record RootOperation(RootOperationType type, GameObject object) {}

enum RootOperationType {
    ADD,
    REMOVE,
}