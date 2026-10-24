package mods.car;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Incremental hybrid-A* for the game car. Candidate geometry is checked by Clearance. */
final class CarManeuverPlanner {
    private static final double WHEELBASE = 2.875;
    private static final double ARC = 1.0;
    private static final double SAMPLE = .25;
    private static final double[] STEERS = {0, -.25, .25, -.42, .42, -.50, .50};
    private static final int MAX_EXPANSIONS = 2600;
    private static final double MAX_RADIUS = 32;

    interface Clearance {
        boolean safe(double x, double z, double heading);
    }

    record Pose(double x, double z, double heading) {}
    record Sample(double x, double z, double heading, double steer, int direction) {}
    record Command(double steer, double remaining, double error) {}
    private record Key(int x, int z, int yaw, int direction, int rack) {}

    private static final class Node {
        final Pose pose;
        final Node parent;
        final int direction, changes;
        final double steer, length, cost, rank;

        Node(Pose pose, Node parent, int direction, double steer,
             double length, int changes, double cost, double rank) {
            this.pose = pose;
            this.parent = parent;
            this.direction = direction;
            this.steer = steer;
            this.length = length;
            this.changes = changes;
            this.cost = cost;
            this.rank = rank;
        }
    }

    static final class Leg {
        final int direction;
        final List<Sample> points = new ArrayList<>();
        int nearest;

        Leg(int direction) { this.direction = direction; }

        /**
         * Project the car onto the travelled portion of this leg. Distance to
         * the last SAMPLE alone is not reliable: it gets stuck if the car
         * passes a waypoint without ever landing precisely on it.
         */
        Command follow(double x, double z, double heading) {
            int lastIndex = points.size() - 1;
            int bestIndex = Math.max(0, nearest);
            double bestT = 0;
            double bestDistance2 = Double.POSITIVE_INFINITY;
            int from = Math.max(0, nearest - 3);
            int to = Math.min(lastIndex - 1, nearest + 24);
            for (int i = from; i <= to; i++) {
                Sample a = points.get(i), b = points.get(i + 1);
                double dx = b.x() - a.x(), dz = b.z() - a.z();
                double length2 = dx * dx + dz * dz;
                double t = length2 < 1e-9 ? 0 : clamp(
                        ((x - a.x()) * dx + (z - a.z()) * dz) / length2, 0, 1);
                double ex = x - a.x() - t * dx;
                double ez = z - a.z() - t * dz;
                double distance2 = ex * ex + ez * ez;
                if (distance2 < bestDistance2 - 1e-7
                        || (Math.abs(distance2 - bestDistance2) < 1e-7
                        && i > bestIndex)) {
                    bestDistance2 = distance2;
                    bestIndex = i;
                    bestT = t;
                }
            }
            nearest = Math.max(nearest, bestIndex);

            double remaining = 0;
            Sample a = points.get(bestIndex), b = points.get(bestIndex + 1);
            remaining += (1 - bestT) * Math.hypot(b.x() - a.x(), b.z() - a.z());
            for (int i = bestIndex + 1; i < lastIndex; i++) {
                a = points.get(i);
                b = points.get(i + 1);
                remaining += Math.hypot(b.x() - a.x(), b.z() - a.z());
            }

            Sample last = points.get(lastIndex);
            double endX = last.x() - x;
            double endZ = last.z() - z;
            double distanceToEnd = Math.hypot(endX, endZ);
            double forward = direction * (Math.sin(last.heading()) * endX
                    + Math.cos(last.heading()) * endZ);
            double lateral = Math.abs(Math.cos(last.heading()) * endX
                    - Math.sin(last.heading()) * endZ);
            double headingError = Math.abs(wrap(last.heading() - heading));

            // Crossing the endpoint, or being close enough with the correct
            // orientation, finishes the leg even when its exact endpoint is
            // inaccessible to the physics model.
            if ((distanceToEnd < .85 && headingError < .8)
                    || (bestIndex >= lastIndex - 5 && forward < .25
                    && lateral < 1.15 && headingError < .85)) {
                remaining = 0;
                nearest = lastIndex - 1;
            }

            double lookAhead = direction < 0 ? 1.35 : 2.0;
            double accumulated = 0;
            Sample target = last;
            for (int i = nearest + 1; i <= lastIndex; i++) {
                Sample point = points.get(i);
                accumulated += Math.hypot(point.x() - points.get(i - 1).x(),
                        point.z() - points.get(i - 1).z());
                if (accumulated >= lookAhead) {
                    target = point;
                    break;
                }
            }
            double dx = target.x() - x, dz = target.z() - z;
            double side = Math.cos(heading) * dx - Math.sin(heading) * dz;
            double d2 = dx * dx + dz * dz;
            double pursuit = d2 > .30
                    ? Math.atan2(2 * WHEELBASE * side, d2)
                    : last.steer() + direction * .6 * wrap(last.heading() - heading);
            double steering = clamp(.82 * pursuit + .18 * target.steer(), -.50, .50);
            return new Command(remaining == 0 ? 0 : steering,
                    remaining, Math.sqrt(Math.max(0, bestDistance2)));
        }
    }

    static final class Plan {
        final List<Leg> legs;
        final boolean complete;
        int index;

        Plan(List<Leg> legs, boolean complete) {
            this.legs = legs;
            this.complete = complete;
        }
        Leg current() { return legs.get(index); }
        boolean advance() { return ++index < legs.size(); }
    }

    static final class Search {
        private final Pose start, goal;
        private final boolean reverseGoal;
        private final boolean allowReverse;
        private final Clearance clearance;
        private final PriorityQueue<Node> open = new PriorityQueue<>(
                (a, b) -> Double.compare(a.rank, b.rank));
        private final Map<Key, Double> costs = new HashMap<>();
        private final double initialDistance;
        private Node closest;
        private double closestRank = Double.POSITIVE_INFINITY;
        private int expanded;
        boolean done;
        Plan result;

        Search(Pose start, Pose goal, int initialDirection,
               boolean reverseGoal, Clearance clearance) {
            this(start, goal, initialDirection, reverseGoal, true, clearance);
        }

        Search(Pose start, Pose goal, int initialDirection,
               boolean reverseGoal, boolean allowReverse,
               Clearance clearance) {
            this.start = start;
            this.goal = goal;
            this.reverseGoal = reverseGoal;
            this.allowReverse = allowReverse;
            this.clearance = clearance;
            initialDistance = Math.hypot(start.x() - goal.x(), start.z() - goal.z());
            Node root = new Node(start, null, initialDirection, 0, 0, 0,
                    0, heuristic(start, goal, reverseGoal));
            open.add(root);
            costs.put(key(root), 0.0);
        }

        void step() {
            if (done) return;
            long deadline = System.nanoTime() + 6_000_000L;
            int batch = 0;
            while (!open.isEmpty() && expanded < MAX_EXPANSIONS) {
                if (batch >= 280 || System.nanoTime() >= deadline) return;
                Node current = open.poll();
                if (current.cost > costs.getOrDefault(key(current),
                        Double.POSITIVE_INFINITY) + 1e-7) continue;
                expanded++;
                batch++;
                recordPartial(current);
                if (current.parent != null && reached(current.pose, current.direction)) {
                    result = reconstruct(current, true);
                    done = true;
                    return;
                }

                if (Math.hypot(current.pose.x() - goal.x(),
                        current.pose.z() - goal.z()) < 8.0) {
                    Node shot = connect(current);
                    if (shot != null) {
                        result = reconstruct(shot, true);
                        done = true;
                        return;
                    }
                }

                for (int dir : new int[]{1, -1}) {
                    if (dir < 0 && !allowReverse) continue;
                    int changes = current.changes + (dir == current.direction ? 0 : 1);
                    if (changes > 3) continue;
                    for (double steer : STEERS) {
                        if (current.parent != null && dir == current.direction
                                && Math.abs(steer - current.steer) > .52) continue;
                        Pose next = current.pose;
                        boolean safe = true;
                        for (int i = 0; i < 4; i++) {
                            next = move(next, steer, dir, SAMPLE);
                            if (!clearance.safe(next.x(), next.z(), next.heading())) {
                                safe = false;
                                break;
                            }
                        }
                        if (!safe || Math.hypot(next.x() - start.x(),
                                next.z() - start.z()) > MAX_RADIUS) continue;
                        double cost = current.cost + ARC * (dir < 0 ? 1.16 : 1)
                                + (dir == current.direction ? 0 : 3)
                                + .12 * Math.abs(steer)
                                + .42 * Math.abs(steer - current.steer);
                        Node node = new Node(next, current, dir, steer, ARC,
                                changes, cost,
                                cost + 1.65 * heuristic(next, goal, reverseGoal));
                        Key key = key(node);
                        if (cost + 1e-7 < costs.getOrDefault(key,
                                Double.POSITIVE_INFINITY)) {
                            costs.put(key, cost);
                            open.add(node);
                        }
                    }
                }
            }

            // A fully verified goal may be unreachable in this short horizon.
            // Retain a USEFUL, obstacle-checked partial manoeuvre rather than
            // declaring every search a total failure. The driver resumes
            // normal route following at its end unless truly still blocked.
            if (closest != null) result = reconstruct(closest, false);
            done = true;
        }

        private void recordPartial(Node node) {
            if (node.parent == null) return;
            double remaining = Math.hypot(node.pose.x() - goal.x(),
                    node.pose.z() - goal.z());
            double progress = initialDistance - remaining;
            if (progress < 3.5 || node.cost < 2.0 || node.changes > 2) return;
            double score = remaining
                    + (reverseGoal ? 0 : .7 * Math.abs(wrap(
                    node.pose.heading() - goal.heading())))
                    + .025 * node.cost + .35 * node.changes;
            if (score < closestRank) {
                closestRank = score;
                closest = node;
            }
        }

        private boolean reached(Pose p, int dir) {
            if (Math.hypot(p.x() - goal.x(), p.z() - goal.z()) > 1.35)
                return false;
            return reverseGoal || (dir > 0
                    && Math.abs(wrap(p.heading() - goal.heading())) < .80);
        }

        private Node connect(Node from) {
            Pose p = from.pose;
            Node previous = from;
            int dir = from.direction;
            double rack = from.steer;
            for (int i = 0; i < 32; i++) {
                if (reached(p, dir)) return previous;
                double dx = goal.x() - p.x(), dz = goal.z() - p.z();
                double length = Math.hypot(dx, dz);
                double ahead = dir * (Math.sin(p.heading()) * dx
                        + Math.cos(p.heading()) * dz);
                if (ahead < .12 || length > 8.8) return null;
                double side = Math.cos(p.heading()) * dx - Math.sin(p.heading()) * dz;
                double requested = Math.atan2(2 * WHEELBASE * side,
                        Math.max(1.5, length * length));
                rack = clamp(rack + clamp(requested - rack, -.16, .16), -.50, .50);
                double distance = Math.min(.30, length);
                Pose next = move(p, rack, dir, distance);
                if (!clearance.safe(next.x(), next.z(), next.heading())) return null;
                double cost = previous.cost + distance * (dir < 0 ? 1.16 : 1);
                previous = new Node(next, previous, dir, rack, distance,
                        previous.changes, cost, cost);
                p = next;
            }
            return reached(p, dir) ? previous : null;
        }

        private Key key(Node node) {
            return new Key((int) Math.round((node.pose.x() - start.x()) / .90),
                    (int) Math.round((node.pose.z() - start.z()) / .90),
                    Math.floorMod((int) Math.round(wrap(node.pose.heading())
                            / (Math.PI / 12)), 24),
                    node.direction, (int) Math.round(node.steer * 8));
        }
    }

    private static Plan reconstruct(Node finish, boolean complete) {
        List<Node> nodes = new ArrayList<>();
        for (Node n = finish; n.parent != null; n = n.parent) nodes.add(n);
        Collections.reverse(nodes);
        List<Leg> legs = new ArrayList<>();
        Leg leg = null;
        for (Node node : nodes) {
            Pose p = node.parent.pose;
            if (leg == null || leg.direction != node.direction) {
                leg = new Leg(node.direction);
                legs.add(leg);
                leg.points.add(new Sample(p.x(), p.z(), p.heading(),
                        node.steer, node.direction));
            }
            int count = Math.max(1, (int) Math.ceil(node.length / .25));
            for (int i = 0; i < count; i++) {
                p = move(p, node.steer, node.direction, node.length / count);
                leg.points.add(new Sample(p.x(), p.z(), p.heading(),
                        node.steer, node.direction));
            }
        }
        return legs.isEmpty() ? null : new Plan(legs, complete);
    }

    private static Pose move(Pose p, double steer, int direction, double distance) {
        double curvature = Math.tan(steer) / WHEELBASE;
        double yaw = direction * distance * curvature;
        if (Math.abs(curvature) < 1e-9) {
            return new Pose(p.x() + direction * distance * Math.sin(p.heading()),
                    p.z() + direction * distance * Math.cos(p.heading()), p.heading());
        }
        return new Pose(p.x() + (Math.cos(p.heading())
                - Math.cos(p.heading() + yaw)) / curvature,
                p.z() + (Math.sin(p.heading() + yaw)
                        - Math.sin(p.heading())) / curvature,
                wrap(p.heading() + yaw));
    }

    private static double heuristic(Pose a, Pose b, boolean reverseGoal) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z())
                + (reverseGoal ? 0 : 2.0 * Math.abs(wrap(a.heading() - b.heading())));
    }
    private static double wrap(double a) { return Math.atan2(Math.sin(a), Math.cos(a)); }
    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}