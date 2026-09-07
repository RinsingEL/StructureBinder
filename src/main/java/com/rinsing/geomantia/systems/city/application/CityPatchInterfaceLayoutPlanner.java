package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.*;
import static com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry.*;

/** A/B shared contour, not a patch bounding box. Pure geometry; no inferred boat/crane semantics. */
public final class CityPatchInterfaceLayoutPlanner {
    public enum Side { A, B }
    public enum Facing { TOWARD_OTHER, AWAY_FROM_OTHER, ALONG_CONTOUR }
    /** Patch cells are grid coordinates, not block coordinates. */
    public record Intent(int step, Set<BlockPoint> cellsA, Set<BlockPoint> cellsB, Side side,
                         Facing facing, int offset, int gap, double startFraction, double endFraction) {
        public Intent {
            cellsA = Set.copyOf(cellsA); cellsB = Set.copyOf(cellsB);
            Objects.requireNonNull(side); Objects.requireNonNull(facing);
            if (step < 1 || step > 64 || offset < 0 || offset > 40 || gap < 0 || gap > 40
                    || !Double.isFinite(startFraction) || !Double.isFinite(endFraction)
                    || startFraction < 0 || endFraction > 1 || startFraction >= endFraction
                    || cellsA.size() + (long) cellsB.size() > 262144)
                throw new IllegalArgumentException("CITY_INTERFACE_PARAMETERS_INVALID");
            if (!Collections.disjoint(cellsA, cellsB))
                throw new IllegalArgumentException("CITY_INTERFACE_PATCH_SETS_OVERLAP");
        }
    }
    public record Segment(BlockPoint start, BlockPoint end, int normalX, int normalZ, int component) {
        int length() { return Math.abs(end.x() - start.x()) + Math.abs(end.z() - start.z()); }
    }
    public record Result(List<Segment> contour, List<CityPublicSpaceLayoutPlanner.Placement> placements,
                         int unplacedCount, List<String> failures) {
        public Result { contour = List.copyOf(contour); placements = List.copyOf(placements); failures = List.copyOf(failures); }
        public boolean ok() { return failures.isEmpty() && unplacedCount == 0; }
    }
    private static final Comparator<BlockPoint> POINTS = Comparator.comparingInt(BlockPoint::x).thenComparingInt(BlockPoint::z);
    private final CityTemplateOrientationSolver orientation = new CityTemplateOrientationSolver();

    public Result plan(Intent intent, List<CityPublicSpaceLayoutPlanner.Member> members) {
        members = List.copyOf(members);
        if (members.size() > 256) throw new IllegalArgumentException("CITY_SCENE_MEMBER_LIMIT_EXCEEDED");
        List<Segment> contour = contour(intent);
        List<CityPublicSpaceLayoutPlanner.Placement> placed = new ArrayList<>();
        if (contour.isEmpty()) return new Result(contour, placed, members.size(), List.of("CITY_INTERFACE_NOT_ADJACENT"));
        int total = contour.stream().mapToInt(Segment::length).sum();
        double start = intent.startFraction() * total, end = intent.endFraction() * total;
        int accumulated = 0, next = 0;
        Set<BlockPoint> sideCells = intent.side() == Side.A ? intent.cellsA() : intent.cellsB();
        for (Segment segment : contour) {
            int length = segment.length();
            int lower = Math.max(0, (int) Math.ceil(start - accumulated));
            int upper = Math.min(length, (int) Math.floor(end - accumulated));
            accumulated += length;
            if (upper <= lower) continue;
            int dx = Integer.signum(segment.end().x() - segment.start().x());
            int dz = Integer.signum(segment.end().z() - segment.start().z());
            int sideSign = intent.side() == Side.A ? -1 : 1;
            int nx = sideSign * segment.normalX(), nz = sideSign * segment.normalZ();
            Direction face = switch (intent.facing()) {
                case TOWARD_OTHER -> direction(-nx, -nz);
                case AWAY_FROM_OTHER -> direction(nx, nz);
                case ALONG_CONTOUR -> direction(dx, dz);
            };
            int cursor = lower;
            while (cursor < upper && next < members.size()) {
                var member = members.get(next);
                CityPublicSpaceLayoutPlanner.Placement chosen = null;
                for (Mirror mirror : member.template().allowedMirrors()) {
                    List<CityTemplateOrientationSolver.RotationScore> scores;
                    try {
                        scores = orientation.rank(member.template(), mirror, member.frontageEntranceId(),
                                new BlockPoint(0, 0), CityTemplateOrientationSolver.FacingTarget.cardinal("patch_interface", face));
                    } catch (IllegalArgumentException missingAuthoredFront) { continue; }
                    for (var score : scores) {
                        if (score.alignmentScore() < .999) continue;
                        var size = member.template().geometry(score.rotation(), mirror).transformedSize();
                        int along = dx != 0 ? size.width() : size.depth();
                        if (cursor + along > upper) continue;
                        int x = dx != 0 ? segment.start().x() + dx * cursor - (dx < 0 ? size.width() : 0)
                                : segment.start().x() + (nx > 0 ? intent.offset() : -intent.offset() - size.width());
                        int z = dz != 0 ? segment.start().z() + dz * cursor - (dz < 0 ? size.depth() : 0)
                                : segment.start().z() + (nz > 0 ? intent.offset() : -intent.offset() - size.depth());
                        BlockBounds footprint = new BlockBounds(x, z, x + size.width() - 1, z + size.depth() - 1);
                        if (!covered(footprint, intent.step(), sideCells)
                                || placed.stream().anyMatch(p -> separatedCollision(p.footprint(), footprint, intent.gap()))) continue;
                        chosen = new CityPublicSpaceLayoutPlanner.Placement(member, new BlockPoint(x, z),
                                score.rotation(), mirror, score.entranceId(), footprint, false);
                        break;
                    }
                    if (chosen != null) break;
                }
                if (chosen == null) { cursor++; continue; }
                placed.add(chosen); next++;
                cursor += (dx != 0 ? chosen.footprint().widthBlocks() : chosen.footprint().heightBlocks()) + intent.gap();
            }
        }
        return new Result(contour, placed, members.size() - next,
                next == members.size() ? List.of() : List.of("CITY_INTERFACE_CAPACITY_OR_AUTHORED_FRONTAGE_UNAVAILABLE"));
    }

    static List<Segment> contour(Intent intent) {
        int step = intent.step(); List<Segment> edges = new ArrayList<>();
        for (BlockPoint cell : intent.cellsA().stream().sorted(POINTS).toList()) {
            int x = Math.multiplyExact(cell.x(), step), z = Math.multiplyExact(cell.z(), step);
            for (int[] n : new int[][]{{1,0},{0,1},{-1,0},{0,-1}}) {
                if (!intent.cellsB().contains(new BlockPoint(cell.x()+n[0], cell.z()+n[1]))) continue;
                BlockPoint a = new BlockPoint(x + (n[0] > 0 ? step : 0), z + (n[1] > 0 ? step : 0));
                BlockPoint b = new BlockPoint(a.x() + (n[0] == 0 ? step : 0), a.z() + (n[1] == 0 ? step : 0));
                edges.add(new Segment(a, b, n[0], n[1], 0));
            }
        }
        Map<BlockPoint, List<Integer>> adjacency = new TreeMap<>(POINTS);
        for (int i=0; i<edges.size(); i++) {
            adjacency.computeIfAbsent(edges.get(i).start(), ignored -> new ArrayList<>()).add(i);
            adjacency.computeIfAbsent(edges.get(i).end(), ignored -> new ArrayList<>()).add(i);
        }
        Set<Integer> remaining = new LinkedHashSet<>(); for(int i=0;i<edges.size();i++) remaining.add(i);
        List<Segment> result = new ArrayList<>(); int component = 0;
        while (!remaining.isEmpty()) {
            BlockPoint cursor = adjacency.entrySet().stream().filter(e -> e.getValue().stream().filter(remaining::contains).count()==1)
                    .map(Map.Entry::getKey).findFirst().orElseGet(() -> edges.get(remaining.iterator().next()).start());
            while (true) {
                int index = adjacency.get(cursor).stream().filter(remaining::contains).findFirst().orElse(-1);
                if (index < 0) break;
                remaining.remove(index); Segment e = edges.get(index);
                BlockPoint end = e.start().equals(cursor) ? e.end() : e.start();
                Segment oriented = new Segment(cursor, end, e.normalX(), e.normalZ(), component);
                if (!result.isEmpty()) {
                    Segment last = result.get(result.size()-1);
                    if (last.component()==component && last.end().equals(cursor)
                            && last.normalX()==oriented.normalX() && last.normalZ()==oriented.normalZ()) {
                        result.set(result.size()-1, new Segment(last.start(), end, last.normalX(), last.normalZ(), component));
                    } else result.add(oriented);
                } else result.add(oriented);
                cursor = end;
            }
            component++;
        }
        return List.copyOf(result);
    }
    private static boolean covered(BlockBounds b, int step, Set<BlockPoint> cells) {
        for (int z=Math.floorDiv(b.minZ(),step);z<=Math.floorDiv(b.maxZ(),step);z++)
            for(int x=Math.floorDiv(b.minX(),step);x<=Math.floorDiv(b.maxX(),step);x++)
                if(!cells.contains(new BlockPoint(x,z))) return false;
        return true;
    }
    private static boolean separatedCollision(BlockBounds a, BlockBounds b, int gap) {
        return new BlockBounds(a.minX()-gap,a.minZ()-gap,a.maxX()+gap,a.maxZ()+gap).overlaps(b);
    }
    private static Direction direction(int x, int z) {
        return x>0 ? Direction.EAST : x<0 ? Direction.WEST : z>0 ? Direction.SOUTH : Direction.NORTH;
    }
}
