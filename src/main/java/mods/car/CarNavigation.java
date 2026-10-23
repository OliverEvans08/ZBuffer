
package mods.car;

import java.util.*;
import java.util.concurrent.*;

/**
 * Road-first navigation for the simulated car.
 *
 * Connected mapped roads take priority over direct terrain
 * shortcuts. Off-road legs are only used to enter or leave
 * the road network, or when no connected road route exists.
 */
final class CarNavigation implements AutoCloseable {

    record Point(double x, double z) {}

    record Pose(
            double x,
            double z,
            double heading,
            double speed
    ) {}

    record Guidance(
            boolean ready,
            boolean arrived,
            double x,
            double z,
            double remaining,
            String status,
            List<Point> route
    ) {
        Guidance(
                boolean ready,
                boolean arrived,
                double x,
                double z,
                double remaining,
                String status
        ) {
            this(
                    ready,
                    arrived,
                    x,
                    z,
                    remaining,
                    status,
                    List.of()
            );
        }
    }

    static final class Request {
        final double x;
        final double z;

        volatile List<Point> path = List.of();
        volatile String status = "Calculating route...";
        volatile boolean done;

        Request(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class Node {
        final Point point;
        final List<Node> links = new ArrayList<>(4);

        double distance = Double.POSITIVE_INFINITY;
        Node previous;

        Node(Point point) {
            this.point = point;
        }
    }

    private record Visit(Node node, double distance) {}

    private record Plan(
            List<Point> points,
            boolean usesRoads
    ) {}

    private static final double ARRIVAL_RADIUS = 4.0;
    private static final double REROUTE_DISTANCE = 28.0;
    private static final double REROUTE_INTERVAL = 5.0;

    private static final double LOCAL_ROUTE_LENGTH = 130.0;

    private static final double ROAD_SEARCH_MARGIN = 900.0;
    private static final double MAX_ROAD_ACCESS = 250.0;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(
                        task,
                        "car-route-planner"
                );
                thread.setDaemon(true);
                return thread;
            });

    private Future<?> job;
    private volatile Request request;

    private volatile Pose pose =
            new Pose(0, 0, 0, 0);

    private volatile Guidance guidance =
            new Guidance(
                    false,
                    false,
                    0,
                    0,
                    0,
                    "M: select a destination"
            );

    private Request following;
    private int segment;
    private double[] suffix = new double[0];
    private double rerouteCooldown;
    private boolean closed;

    Pose pose() {
        return pose;
    }

    Request request() {
        return request;
    }

    Guidance guidance() {
        return guidance;
    }

    synchronized String setGoal(double x, double z) {
        if (closed) {
            return "Navigation closed";
        }

        Pose start = pose;

        if (!Double.isFinite(x)
                || !Double.isFinite(z)
                || Math.abs(x) > 1e7
                || Math.abs(z) > 1e7) {
            return "Enter finite X/Z coordinates between "
                    + "-10000000 and 10000000";
        }

        if (Math.hypot(
                x - start.x(),
                z - start.z()
        ) > 10000) {
            return "Choose a destination within 10 km of the car";
        }

        if (job != null) {
            job.cancel(true);
        }

        Request next = new Request(x, z);
        request = next;

        job = worker.submit(() -> {
            try {
                Plan result = plan(
                        start.x(),
                        start.z(),
                        x,
                        z
                );

                if (Thread.currentThread().isInterrupted()
                        || request != next) {
                    return;
                }

                next.path = List.copyOf(result.points());

                next.status = result.usesRoads()
                        ? "Following mapped roads"
                        : "Terrain route (no connected road path)";

            } catch (RuntimeException ex) {
                if (!Thread.currentThread().isInterrupted()
                        && request == next) {
                    next.path = List.of(
                            new Point(start.x(), start.z()),
                            new Point(x, z)
                    );

                    next.status =
                            "Terrain route (map unavailable)";
                }
            } finally {
                next.done = true;
            }
        });

        return "Calculating route...";
    }

    synchronized void clear() {
        if (job != null) {
            job.cancel(true);
        }

        request = null;
        following = null;
        segment = 0;
        suffix = new double[0];

        guidance = new Guidance(
                false,
                false,
                pose.x(),
                pose.z(),
                0,
                "M: select a destination"
        );
    }

    Guidance update(CarVehicle car, double dt) {
        Pose current = new Pose(
                car.transform.position.x,
                car.transform.position.z,
                car.getHeading(),
                car.getSpeed()
        );

        pose = current;

        rerouteCooldown = Math.max(
                0,
                rerouteCooldown - Math.max(0, dt)
        );

        Request selected = request;

        if (selected == null
                || !selected.done
                || selected.path.isEmpty()) {
            return guidance = new Guidance(
                    false,
                    false,
                    current.x(),
                    current.z(),
                    0,
                    selected == null
                            ? "M: select a destination"
                            : selected.status
            );
        }

        List<Point> path = selected.path;

        if (following != selected) {
            following = selected;
            segment = 0;

            suffix = new double[path.size()];

            for (int i = path.size() - 2; i >= 0; i--) {
                suffix[i] = suffix[i + 1]
                        + distance(
                        path.get(i),
                        path.get(i + 1)
                );
            }
        }

        Point here = new Point(
                current.x(),
                current.z()
        );

        Point goal = new Point(
                selected.x,
                selected.z
        );

        double straightToGoal = distance(here, goal);

        if (straightToGoal <= ARRIVAL_RADIUS) {
            return guidance = new Guidance(
                    true,
                    true,
                    selected.x,
                    selected.z,
                    straightToGoal,
                    "Arrived",
                    List.of(goal)
            );
        }

        if (path.size() == 1) {
            return guidance = new Guidance(
                    true,
                    false,
                    goal.x(),
                    goal.z(),
                    straightToGoal,
                    selected.status,
                    List.of(goal)
            );
        }

        double best = Double.POSITIVE_INFINITY;
        int bestSegment = Math.min(
                segment,
                path.size() - 2
        );

        Point projection = path.get(bestSegment);

        int searchEnd = Math.min(
                path.size() - 2,
                segment + 12
        );

        for (int i = segment; i <= searchEnd; i++) {
            Point candidate = project(
                    here,
                    path.get(i),
                    path.get(i + 1)
            );

            double error = distance(here, candidate);

            if (error < best) {
                best = error;
                bestSegment = i;
                projection = candidate;
            }
        }

        segment = Math.max(segment, bestSegment);

        if (best > REROUTE_DISTANCE
                && rerouteCooldown <= 0) {
            rerouteCooldown = REROUTE_INTERVAL;
            setGoal(selected.x, selected.z);

            return guidance = new Guidance(
                    false,
                    false,
                    current.x(),
                    current.z(),
                    straightToGoal,
                    "Updating road route..."
            );
        }

        double remaining =
                distance(
                        projection,
                        path.get(segment + 1)
                )
                        + suffix[segment + 1]
                        + best;

        List<Point> local = localRoute(
                path,
                segment,
                projection
        );

        if (local.isEmpty()) {
            local = List.of(here, goal);
        }

        String state = String.format(
                Locale.ROOT,
                "%.0f m remaining | %s",
                remaining,
                selected.status
        );

        Point aim = local.get(local.size() - 1);

        return guidance = new Guidance(
                true,
                false,
                aim.x(),
                aim.z(),
                remaining,
                state,
                local
        );
    }

    private static List<Point> localRoute(
            List<Point> path,
            int segment,
            Point projection
    ) {
        ArrayList<Point> result = new ArrayList<>();
        result.add(projection);

        double length = 0;

        for (int i = segment + 1;
             i < path.size() && length < LOCAL_ROUTE_LENGTH;
             i++) {
            Point a = result.get(result.size() - 1);
            Point b = path.get(i);

            double d = distance(a, b);

            if (d < 0.05) {
                continue;
            }

            length += d;
            result.add(b);
        }

        return List.copyOf(result);
    }

    private static Plan plan(
            double sx,
            double sz,
            double gx,
            double gz
    ) {
        Point start = new Point(sx, sz);
        Point goal = new Point(gx, gz);

        double direct = distance(start, goal);

        List<Point> fallback = direct < 0.01
                ? List.of(goal)
                : List.of(start, goal);

        if (direct < ARRIVAL_RADIUS) {
            return new Plan(fallback, false);
        }

        double margin = Math.max(
                ROAD_SEARCH_MARGIN,
                Math.min(1600, direct * 0.5)
        );

        List<CarRoadMap.Edge> roads = CarRoadMap.roads(
                Math.min(sx, gx) - margin,
                Math.min(sz, gz) - margin,
                Math.max(sx, gx) + margin,
                Math.max(sz, gz) + margin
        );

        if (Thread.currentThread().isInterrupted()
                || roads.isEmpty()) {
            return new Plan(fallback, false);
        }

        Map<CarRoadMap.Id, Node> endpoints =
                new HashMap<>();

        List<Node> nodes = new ArrayList<>();

        for (CarRoadMap.Edge edge : roads) {
            Node previous = null;

            for (int i = 0; i < edge.x.length; i++) {
                Node node;

                if (i == 0 || i == edge.x.length - 1) {
                    CarRoadMap.Id id = i == 0
                            ? edge.a
                            : edge.b;

                    node = endpoints.get(id);

                    if (node == null) {
                        node = new Node(
                                new Point(
                                        edge.x[i],
                                        edge.z[i]
                                )
                        );

                        endpoints.put(id, node);
                        nodes.add(node);
                    }
                } else {
                    node = new Node(
                            new Point(
                                    edge.x[i],
                                    edge.z[i]
                            )
                    );

                    nodes.add(node);
                }

                if (previous != null) {
                    previous.links.add(node);
                    node.links.add(previous);
                }

                previous = node;
            }
        }

        if (nodes.isEmpty()) {
            return new Plan(fallback, false);
        }

        Node entrance = attach(nodes, start);
        Node exit = attach(nodes, goal);

        double access = distance(
                start,
                entrance.point
        );

        double departure = distance(
                goal,
                exit.point
        );

        if (access > MAX_ROAD_ACCESS
                || departure > MAX_ROAD_ACCESS) {
            return new Plan(fallback, false);
        }

        entrance.distance = 0;

        PriorityQueue<Visit> queue =
                new PriorityQueue<>(
                        Comparator.comparingDouble(
                                Visit::distance
                        )
                );

        queue.add(new Visit(entrance, 0));

        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                return new Plan(fallback, false);
            }

            Visit visit = queue.poll();
            Node node = visit.node();

            if (visit.distance() != node.distance) {
                continue;
            }

            if (node == exit) {
                break;
            }

            for (Node next : node.links) {
                double cost = node.distance
                        + distance(
                        node.point,
                        next.point
                );

                if (cost < next.distance) {
                    next.distance = cost;
                    next.previous = node;
                    queue.add(new Visit(next, cost));
                }
            }
        }

        if (!Double.isFinite(exit.distance)) {
            return new Plan(fallback, false);
        }

        /*
         * No maximum distance ratio.
         * An available connected road remains preferable
         * to an arbitrary terrain shortcut.
         */
        LinkedList<Point> path = new LinkedList<>();

        for (Node node = exit;
             node != null;
             node = node.previous) {
            path.addFirst(node.point);
        }

        if (path.isEmpty()) {
            return new Plan(fallback, false);
        }

        if (distance(start, path.getFirst()) > 0.5) {
            path.addFirst(start);
        }

        if (distance(goal, path.getLast()) > 0.5) {
            path.addLast(goal);
        }

        return new Plan(
                List.copyOf(path),
                true
        );
    }

    private static Node attach(
            List<Node> nodes,
            Point point
    ) {
        Node left = null;
        Node right = null;

        Point closest = null;
        double best = Double.POSITIVE_INFINITY;

        // Snapshot: attaching a point modifies the graph.
        List<Node> original = List.copyOf(nodes);

        for (Node a : original) {
            for (Node b : a.links) {
                Point projected = project(
                        point,
                        a.point,
                        b.point
                );

                double error = distance(
                        projected,
                        point
                );

                if (error < best) {
                    best = error;
                    closest = projected;
                    left = a;
                    right = b;
                }
            }
        }

        if (left == null) {
            return nodes.get(0);
        }

        if (distance(closest, left.point) < 0.01) {
            return left;
        }

        if (distance(closest, right.point) < 0.01) {
            return right;
        }

        Node join = new Node(closest);

        left.links.remove(right);
        right.links.remove(left);

        left.links.add(join);
        right.links.add(join);

        join.links.add(left);
        join.links.add(right);

        nodes.add(join);

        return join;
    }

    private static Point project(
            Point p,
            Point a,
            Point b
    ) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();

        double t = clamp(
                ((p.x() - a.x()) * dx
                        + (p.z() - a.z()) * dz)
                        / Math.max(
                        1e-9,
                        dx * dx + dz * dz
                ),
                0,
                1
        );

        return new Point(
                a.x() + t * dx,
                a.z() + t * dz
        );
    }

    private static double distance(
            Point a,
            Point b
    ) {
        return Math.hypot(
                a.x() - b.x(),
                a.z() - b.z()
        );
    }

    private static double clamp(
            double value,
            double low,
            double high
    ) {
        return Math.max(
                low,
                Math.min(high, value)
        );
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
        worker.shutdownNow();
    }
}
