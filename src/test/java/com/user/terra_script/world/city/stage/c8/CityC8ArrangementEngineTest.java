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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        area.bbox.minX = 0;
        area.bbox.minZ = 0;
        area.bbox.maxX = 100;
        area.bbox.maxZ = 100;

        CityC7Stages.GroupArrangementDecision arrangement = new CityC7Stages.GroupArrangementDecision();
        arrangement.group_id = "port_group";

        Map<String, Object> metaById = new LinkedHashMap<>();
        metaById.put("test:child", childMeta);
        Object ctx = newSolveContext(area, arrangement, metaById);

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

    private static Object newTemplateMeta(String structureId, String pieceRole) throws Exception {
        Class<?> type = Class.forName("com.user.terra_script.world.city.stage.c8.CityC8ArrangementEngine$TemplateMeta");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object meta = ctor.newInstance();
        setField(meta, "structure_id", structureId);
        setField(meta, "piece_role", pieceRole);
        setField(meta, "path", structureId);
        setField(meta, "preset_pool", "port/main");
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

    private static Object newSolveContext(
            CityC6Stages.BuildAreaSummary area,
            CityC7Stages.GroupArrangementDecision arrangement,
            Map<String, Object> metaById
    ) throws Exception {
        Class<?> type = Class.forName("com.user.terra_script.world.city.stage.c8.CityC8ArrangementEngine$SolveContext");
        Constructor<?> ctor = type.getDeclaredConstructor(
                CityC6Stages.BuildAreaSummary.class,
                CityC7Stages.GroupArrangementDecision.class,
                Map.class,
                int.class,
                int.class,
                int.class,
                Class.forName("com.user.terra_script.world.city.stage.c1.CityStage1BinaryIO$HeightData"),
                Class.forName("com.user.terra_script.world.city.stage.c2.CityC2ScanBinaryIO$C2ScanData")
        );
        ctor.setAccessible(true);
        return ctor.newInstance(area, arrangement, metaById, 8, 8, 4, null, null);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        return target.getClass().getField(fieldName).get(target);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        target.getClass().getField(fieldName).set(target, value);
    }
}
