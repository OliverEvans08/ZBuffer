package objects.dynamic;

import objects.GameObject;

final class HandSocketNode
        extends GameObject {

    @Override
    public void update(double delta) {
    }

    @Override
    public double[][] getVertices() {
        return Body.EMPTY_VERTICES;
    }

    @Override
    public int[][] getFacesArray() {
        return null;
    }
}