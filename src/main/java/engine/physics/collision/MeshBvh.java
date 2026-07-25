package engine.physics.collision;

public final class MeshBvh {

    public final int[] order;

    public final double[] minX;
    public final double[] maxX;
    public final double[] minY;
    public final double[] maxY;
    public final double[] minZ;
    public final double[] maxZ;

    public final int[] left;
    public final int[] right;
    public final int[] start;
    public final int[] count;

    public int nodeCount;

    MeshBvh(int[] order) {
        this.order = order;

        int capacity = Math.max(1, order.length * 2);

        minX = new double[capacity];
        maxX = new double[capacity];
        minY = new double[capacity];
        maxY = new double[capacity];
        minZ = new double[capacity];
        maxZ = new double[capacity];

        left = new int[capacity];
        right = new int[capacity];
        start = new int[capacity];
        count = new int[capacity];
    }
}