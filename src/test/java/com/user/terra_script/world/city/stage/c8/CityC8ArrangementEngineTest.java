package com.user.terra_script.world.city.stage.c8;

import com.user.terra_script.world.city.stage.CityC35CatalogIO;
import com.user.terra_script.world.city.stage.c6.CityC6Stages;
import com.user.terra_script.world.city.stage.c7.CityC7Stages;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CityC8ArrangementEngineTest {
    @Test
    void preciseConnectorSolveUsesLocalPosAndRotation() throws Exception {
        Object parentMeta = newTemplateMeta("test:parent", "START");
        addConnector(parentMeta, "p_east", 4, 0, "east");

        Object childMeta = newTemplateMeta("test:child", "MIDDLE");
        addConnector(childMeta, "c_north", 0, 1, "north");
        setAllowedRotations(childMeta, List.of(270));

        CityC8Stages.PlacementNode parent = new CityC8Stages.PlacementNode();
        parent.node_id = "p1";
        parent.component_id = "root";
        parent.template_id = "test:parent";
        parent.x = 10;
        parent.z = 10;
        parent.rotation = 0;

        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        CityC8Stages.AreaGeometry geometry = rectangularGeometry(area, 0, 0, 100, 100);

        CityC7Stages.GroupArrangementDecision arrangement = new CityC7Stages.GroupArrangementDecision();
        arrangement.group_id = "port_group";

        Map<String, Object> metaById = new LinkedHashMap<>();
        metaById.put("test:child", childMeta);
        Object ctx = newSolveContext(area, geometry, arrangement, metaById);

        Method resolveConnectorViews = CityC8ArrangementEngine.class.getDeclaredMethod("resolveConnectorViews", parentMeta.getClass(), int.class);
        resolveConnectorViews.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> parentConnectors = (List<Object>) resolveConnectorViews.invoke(null, parentMeta, 0);
        Object parentConnector = parentConnectors.get(0);

        Method choosePreciseCandidate = CityC8ArrangementEngine.class.getDeclaredMethod(
                "choosePreciseCandidate",
                ctx.getClass(),
                CityC8Stages.PlacementNode.class,
                parentConnector.getClass(),
                childMeta.getClass(),
                CityC7Stages.SelectedComponent.class,
                int.class,
                String.class
        );
        choosePreciseCandidate.setAccessible(true);
        Object candidate = choosePreciseCandidate.invoke(null, ctx, parent, parentConnector, childMeta, null, 0, "test");
        assertNotNull(candidate);

        Method nodeAccessor = candidate.getClass().getDeclaredMethod("node");
        nodeAccessor.setAccessible(true);
        CityC8Stages.PlacementNode child = (CityC8Stages.PlacementNode) nodeAccessor.invoke(candidate);

        assertEquals(14, child.x);
        assertEquals(10, child.z);
        assertEquals(270, child.rotation);
        assertEquals("p_east", child.incoming_parent_connector_id);
        assertEquals("c_north", child.incoming_child_connector_id);
    }

    @Test
    void connectorCompatibilityIgnoresLegacyDirectionsWithoutRealConnectors() throws Exception {
        Object candidate = newTemplateMeta("test:legacy_child", "MIDDLE");
        @SuppressWarnings("unchecked")
        List<String> connectorDirs = (List<String>) getField(candidate, "connector_dirs");
        connectorDirs.add("west");

        Class<?> directionType = Class.forName("com.user.terra_script.world.city.stage.c8.CityC8ArrangementEngine$Direction");
        Object east = Enum.valueOf((Class<Enum>) directionType, "EAST");

        Method connectorCompatible = CityC8ArrangementEngine.class.getDeclaredMethod(
                "connectorCompatible",
                candidate.getClass(),
                directionType,
                String.class,
                Set.class
        );
        connectorCompatible.setAccessible(true);

        boolean compatible = (boolean) connectorCompatible.invoke(null, candidate, east, "", Set.of());
        assertFalse(compatible);
    }

    @Test
    void rejectsFootprintThatLeaksOutsidePolygonBlocks() throws Exception {
        Object meta = newTemplateMeta("test:square", "SINGLE");
        setSize(meta, 2, 2);
        CityC8Stages.PlacementNode node = new CityC8Stages.PlacementNode();
        node.node_id = "p1";
        node.template_id = "test:square";
        node.x = 0;
        node.z = 0;
        node.rotation = 0;

        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        CityC8Stages.AreaGeometry geometry = sparseGeometry(area, List.of(
                packBlock(0, 0),
                packBlock(1, 0),
                packBlock(0, 1)
        ));
        CityC7Stages.GroupArrangementDecision arrangement = new CityC7Stages.GroupArrangementDecision();
        Object ctx = newSolveContext(area, geometry, arrangement, Map.of());

        Method applyFootprint = CityC8ArrangementEngine.class.getDeclaredMethod("applyFootprint", CityC8Stages.PlacementNode.class, meta.getClass());
        applyFootprint.setAccessible(true);
        applyFootprint.invoke(null, node, meta);

        Method firstPlacementRejectReason = CityC8ArrangementEngine.class.getDeclaredMethod(
                "firstPlacementRejectReason",
                ctx.getClass(),
                CityC8Stages.PlacementNode.class,
                meta.getClass()
        );
        firstPlacementRejectReason.setAccessible(true);
        String reject = (String) firstPlacementRejectReason.invoke(null, ctx, node, meta);
        assertEquals("out_of_area", reject);
    }

    @Test
    void allowsFootprintWhenEntireRectangleFitsPolygonBlocks() throws Exception {
        Object meta = newTemplateMeta("test:square_ok", "SINGLE");
        setSize(meta, 2, 2);
        CityC8Stages.PlacementNode node = new CityC8Stages.PlacementNode();
        node.node_id = "p1";
        node.template_id = "test:square_ok";
        node.x = 0;
        node.z = 0;
        node.rotation = 0;

        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        CityC8Stages.AreaGeometry geometry = sparseGeometry(area, List.of(
                packBlock(0, 0),
                packBlock(1, 0),
                packBlock(0, 1),
                packBlock(1, 1)
        ));
        CityC7Stages.GroupArrangementDecision arrangement = new CityC7Stages.GroupArrangementDecision();
        Object ctx = newSolveContext(area, geometry, arrangement, Map.of());

        Method applyFootprint = CityC8ArrangementEngine.class.getDeclaredMethod("applyFootprint", CityC8Stages.PlacementNode.class, meta.getClass());
        applyFootprint.setAccessible(true);
        applyFootprint.invoke(null, node, meta);

        Method firstPlacementRejectReason = CityC8ArrangementEngine.class.getDeclaredMethod(
                "firstPlacementRejectReason",
                ctx.getClass(),
                CityC8Stages.PlacementNode.class,
                meta.getClass()
        );
        firstPlacementRejectReason.setAccessible(true);
        String reject = (String) firstPlacementRejectReason.invoke(null, ctx, node, meta);
        assertNull(reject);
    }

    @Test
    void axisHeuristicUsesGeometrySpanInsteadOfLegacyBBox() throws Exception {
        CityC6Stages.BuildAreaSummary area = new CityC6Stages.BuildAreaSummary();
        area.bbox.minX = 0;
        area.bbox.minZ = 0;
        area.bbox.maxX = 4;
        area.bbox.maxZ = 40;
        CityC8Stages.AreaGeometry geometry = rectangularGeometry(area, 0, 0, 20, 4);
        CityC7Stages.GroupArrangementDecision arrangement = new CityC7Stages.GroupArrangementDecision();

        Method preferLongAxisX = CityC8ArrangementEngine.class.getDeclaredMethod(
                "preferLongAxisX",
                CityC7Stages.GroupArrangementDecision.class,
                CityC8Stages.AreaGeometry.class
        );
        preferLongAxisX.setAccessible(true);
        boolean longX = (boolean) preferLongAxisX.invoke(null, arrangement, geometry);
        assertEquals(true, longX);
    }

    private static Object newTemplateMeta(String structureId, String pieceRole) throws Exception {
        Class<?> type = Class.forName("com.user.terra_script.world.city.stage.c8.CityC8ArrangementEngine$TemplateMeta");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object meta = ctor.newInstance();
        setField(meta, "structure_id", structureId);
        setField(meta, "piece_role", pieceRole);
        setField(meta, "path", structureId);
        setField(meta, "preset_pool", "port/main");
        setField(meta, "placement", null);
        CityC35CatalogIO.Size size = (CityC35CatalogIO.Size) getField(meta, "size");
        size.width = 5;
        size.length = 5;
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static void addConnector(Object meta, String id, int x, int z, String facing) throws Exception {
        CityC35CatalogIO.ConnectorSpec connector = new CityC35CatalogIO.ConnectorSpec();
        connector.id = id;
        connector.facing = facing;
        connector.local_pos.x = x;
        connector.local_pos.z = z;
        connector.socket = "dock";
        ((List<CityC35CatalogIO.ConnectorSpec>) getField(meta, "connectors")).add(connector);
    }

    private static void setAllowedRotations(Object meta, List<Integer> rotations) throws Exception {
        CityC35CatalogIO.ConstraintSpec constraints = (CityC35CatalogIO.ConstraintSpec) getField(meta, "constraints");
        constraints.allowed_rotations.clear();
        constraints.allowed_rotations.addAll(rotations);
    }

    private static void setSize(Object meta, int width, int length) throws Exception {
        CityC35CatalogIO.Size size = (CityC35CatalogIO.Size) getField(meta, "size");
        size.width = width;
        size.length = length;
    }

    private static Object newSolveContext(
            CityC6Stages.BuildAreaSummary area,
            CityC8Stages.AreaGeometry geometry,
            CityC7Stages.GroupArrangementDecision arrangement,
            Map<String, Object> metaById
    ) throws Exception {
        Class<?> type = Class.forName("com.user.terra_script.world.city.stage.c8.CityC8ArrangementEngine$SolveContext");
        Constructor<?> ctor = type.getDeclaredConstructor(
                CityC6Stages.BuildAreaSummary.class,
                CityC8Stages.AreaGeometry.class,
                CityC7Stages.GroupArrangementDecision.class,
                Map.class,
                int.class,
                int.class,
                int.class,
                Class.forName("com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO$HeightData"),
                Class.forName("com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO$C2ScanData")
        );
        ctor.setAccessible(true);
        return ctor.newInstance(area, geometry, arrangement, metaById, 8, 8, 4, null, null);
    }

    private static CityC8Stages.AreaGeometry rectangularGeometry(CityC6Stages.BuildAreaSummary area, int minX, int minZ, int maxX, int maxZ) {
        List<Long> keys = new java.util.ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                keys.add(packBlock(x, z));
            }
        }
        return sparseGeometry(area, keys);
    }

    private static CityC8Stages.AreaGeometry sparseGeometry(CityC6Stages.BuildAreaSummary area, List<Long> keys) {
        area.centroid.x = 0;
        area.centroid.z = 0;
        return CityC8Stages.buildAreaGeometry(area, keys);
    }

    private static long packBlock(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        return target.getClass().getField(fieldName).get(target);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        target.getClass().getField(fieldName).set(target, value);
    }
}
