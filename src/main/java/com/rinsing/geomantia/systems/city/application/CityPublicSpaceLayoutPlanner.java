package com.rinsing.geomantia.systems.city.application;

import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry.*;

/** Pure design-space layout. Raw terrain is not a building-placement oracle here. */
public final class CityPublicSpaceLayoutPlanner {
    public enum Kind { PUBLIC_PLAZA, MARKET_INTERIOR, MARKET_EDGE }
    public record Member(String structureRef, CityTemplateCatalog.Template template, String frontageEntranceId) {
        public Member { Objects.requireNonNull(structureRef); Objects.requireNonNull(template); }
    }
    public record Intent(Kind kind, BlockBounds publicSpace, int gap, int aisleWidth, int edgeCount) {
        public Intent {
            Objects.requireNonNull(kind); Objects.requireNonNull(publicSpace);
            if (gap < 0 || gap > 40 || aisleWidth < 3 || aisleWidth > 15 || edgeCount < 1 || edgeCount > 4
                    || publicSpace.widthBlocks() > 512 || publicSpace.heightBlocks() > 512)
                throw new IllegalArgumentException("CITY_SCENE_LAYOUT_PARAMETERS_INVALID");
        }
    }
    public record Placement(Member member, BlockPoint anchor, Rotation rotation, Mirror mirror,
                            String entranceId, BlockBounds footprint, boolean core) {}
    public record Result(BlockBounds publicSpace, List<Placement> placements, int unplacedCount, List<String> failures) {
        public Result { placements = List.copyOf(placements); failures = List.copyOf(failures); }
        public boolean ok() { return failures.isEmpty() && unplacedCount == 0; }
    }
    private record Oriented(Member member, Rotation rotation, Mirror mirror, String entranceId, int width, int depth) {}
    private final CityTemplateOrientationSolver orientation = new CityTemplateOrientationSolver();

    public Result plan(Intent intent, Member core, List<Member> members) {
        Objects.requireNonNull(intent); members = List.copyOf(members);
        if (members.size() > 256) throw new IllegalArgumentException("CITY_SCENE_MEMBER_LIMIT_EXCEEDED");
        List<Placement> placements = new ArrayList<>(); List<String> failures = new ArrayList<>();
        BlockBounds area = intent.publicSpace();
        if (intent.kind() == Kind.PUBLIC_PLAZA && !members.isEmpty())
            return new Result(area, placements, members.size(), List.of("PUBLIC_PLAZA_DOES_NOT_CONSUME_FILL_MEMBERS"));
        try {
            if (core != null) {
                Oriented v = orient(core, Direction.SOUTH);
                int x = area.minX() + (area.widthBlocks() - v.width()) / 2;
                int z = area.minZ() + (area.heightBlocks() - v.depth()) / 2;
                Placement p = placement(v, x, z, true);
                if (!contains(area, expand(p.footprint(), intent.aisleWidth())))
                    return new Result(area, placements, members.size(), List.of("CORE_PUBLIC_PASSAGE_TOO_SMALL"));
                placements.add(p);
            }
            int placed = switch (intent.kind()) {
                case PUBLIC_PLAZA -> 0;
                case MARKET_EDGE -> edge(intent, members, placements);
                case MARKET_INTERIOR -> interior(intent, members, placements);
            };
            if (placed < members.size()) failures.add("PUBLIC_SPACE_CAPACITY_INSUFFICIENT");
            for (int i = 0; i < placements.size(); i++) for (int j = 0; j < i; j++)
                if (placements.get(i).footprint().overlaps(placements.get(j).footprint())) failures.add("SCENE_TEMPLATE_COLLISION");
            return new Result(area, placements, members.size() - placed, failures);
        } catch (IllegalArgumentException e) {
            failures.add(e.getMessage());
            return new Result(area, placements, members.size() - (int) placements.stream().filter(p -> !p.core()).count(), failures);
        }
    }

    private int edge(Intent intent, List<Member> members, List<Placement> placements) {
        BlockBounds a = intent.publicSpace(); int next = 0;
        Direction[] faces = {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};
        for (int side = 0; side < intent.edgeCount() && next < members.size(); side++) {
            boolean horizontal = side % 2 == 0;
            int capacity = horizontal ? a.widthBlocks() : a.heightBlocks(), used = 0;
            List<Oriented> row = new ArrayList<>();
            while (next < members.size()) {
                Oriented v = orient(members.get(next), faces[side]);
                int span = horizontal ? v.width() : v.depth(), gap = row.isEmpty() ? 0 : intent.gap();
                if (used + gap + span > capacity) break;
                row.add(v); used += gap + span; next++;
            }
            int cursor = (horizontal ? a.minX() : a.minZ()) + (capacity - used) / 2;
            for (Oriented v : row) {
                int x = horizontal ? cursor : side == 1 ? a.maxX() + 1 : a.minX() - v.width();
                int z = !horizontal ? cursor : side == 0 ? a.minZ() - v.depth() : a.maxZ() + 1;
                placements.add(placement(v, x, z, false));
                cursor += (horizontal ? v.width() : v.depth()) + intent.gap();
            }
        }
        return next;
    }

    private int interior(Intent intent, List<Member> members, List<Placement> placements) {
        List<Oriented> variants = members.stream().map(m -> orient(m, Direction.SOUTH)).toList();
        int rowDepth = variants.stream().mapToInt(Oriented::depth).max().orElse(1);
        BlockBounds a = intent.publicSpace(); int aisle = intent.aisleWidth(), next = 0;
        int aisleStart = a.minX() + (a.widthBlocks() - aisle) / 2;
        BlockBounds coreKeepout = placements.isEmpty() ? null : expand(placements.get(0).footprint(), aisle);
        for (int z = a.minZ() + aisle; z + rowDepth - 1 <= a.maxZ() - aisle && next < variants.size(); z += rowDepth + aisle) {
            for (int[] interval : new int[][]{{a.minX() + aisle, aisleStart - 1}, {aisleStart + aisle, a.maxX() - aisle}}) {
                int x = interval[0];
                while (next < variants.size()) {
                    Oriented v = variants.get(next); if (x + v.width() - 1 > interval[1]) break;
                    Placement p = placement(v, x, z, false);
                    if (coreKeepout != null && p.footprint().overlaps(coreKeepout)) { x = Math.max(x + 1, coreKeepout.maxX() + 1); continue; }
                    placements.add(p); next++; x += v.width() + intent.gap();
                }
            }
        }
        return next;
    }

    private Oriented orient(Member member, Direction face) {
        for (Mirror mirror : member.template().allowedMirrors()) {
            var scores = orientation.rank(member.template(), mirror, member.frontageEntranceId(), new BlockPoint(0, 0),
                    CityTemplateOrientationSolver.FacingTarget.cardinal("public_space", face));
            for (var score : scores) if (score.alignmentScore() >= 0.999) {
                var size = member.template().geometry(score.rotation(), mirror).transformedSize();
                return new Oriented(member, score.rotation(), mirror, score.entranceId(), size.width(), size.depth());
            }
        }
        throw new IllegalArgumentException("SCENE_AUTHORED_FRONTAGE_UNAVAILABLE:" + member.structureRef());
    }
    private static Placement placement(Oriented v, int x, int z, boolean core) {
        return new Placement(v.member(), new BlockPoint(x, z), v.rotation(), v.mirror(), v.entranceId(),
                new BlockBounds(x, z, x + v.width() - 1, z + v.depth() - 1), core);
    }
    private static BlockBounds expand(BlockBounds b, int n) { return new BlockBounds(b.minX()-n,b.minZ()-n,b.maxX()+n,b.maxZ()+n); }
    private static boolean contains(BlockBounds a, BlockBounds b) { return a.contains(b.minX(),b.minZ()) && a.contains(b.maxX(),b.maxZ()); }
}
