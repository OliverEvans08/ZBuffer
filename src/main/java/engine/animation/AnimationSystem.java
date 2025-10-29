// File: src/main/java/engine/animation/AnimationSystem.java
package engine.animation;

import objects.GameObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Traverses the scene graph and updates any attached Animator.
 */
public final class AnimationSystem {

    private AnimationSystem() {}

    public static void updateAll(List<GameObject> roots, double dt) {
        if (roots == null || roots.isEmpty()) return;
        Deque<GameObject> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            GameObject g = stack.pop();
            if (g.getAnimator() != null) g.getAnimator().update(dt);
            List<GameObject> kids = g.getChildren();
            if (kids != null && !kids.isEmpty()) {
                for (int i = 0; i < kids.size(); i++) stack.push(kids.get(i));
            }
        }
    }
}
