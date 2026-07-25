package com.rinsing.geomantia.systems.city;

import com.rinsing.geomantia.systems.city.application.CityTemplateCatalog;
import com.rinsing.geomantia.systems.city.application.CityTemplateCatalogLoader;
import com.rinsing.geomantia.systems.city.application.CityTemplatePlacementGeometry;
import com.rinsing.geomantia.systems.city.application.CityTemplateTerrainPosePolicy;
import com.rinsing.geomantia.systems.city.domain.model.BlockBounds;
import com.rinsing.geomantia.systems.city.domain.model.BlockPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityTemplateCatalogTest {
    private final CityTemplateCatalogLoader loader = new CityTemplateCatalogLoader();

    @Test
    void parsesCatalogAndTemplateFields() {
        CityTemplateCatalog catalog = loader.load(catalogJson("""
                {"x": 1, "z": 2, "direction": "NORTH"}
                """));

        CityTemplateCatalog.Template template = catalog.requireTemplate("city:house", "oak");
        assertEquals("city_template_catalog.v0.1", catalog.schemaVersion());
        assertEquals("residential", template.buildingSemantic());
        assertEquals("medieval", template.style());
        assertEquals("templates/house_oak.nbt", template.nbtFile());
        assertEquals("sha256:house", template.contentHash());
        assertEquals(4, template.width());
        assertEquals(6, template.height());
        assertEquals(3, template.depth());
        assertEquals(2, template.clearanceBlocks());
        assertEquals(1, template.roadEntrances().size());
        assertEquals(CityTemplateTerrainPosePolicy.STRUCTURE_START_BEARD_THIN,
                template.terrainPosePolicy());
    }

    @Test
    void acceptsCanonicalTemplateRefAndVariantFields() {
        CityTemplateCatalog.Template template = loader.load("""
                {
                  "schemaVersion": "city_template_catalog.v0.1",
                  "templates": [{
                    "buildingSemantic": "market",
                    "style": "coastal_medieval",
                    "templateId": "geomantia:city/market_a",
                    "templateRef": "geomantia:city/market_a",
                    "contentHash": "sha256:market",
                    "variant": "a",
                    "rawSize": {"width": 10, "height": 8, "depth": 12},
                    "allowedRotations": ["NONE"],
                    "allowedMirrors": ["NONE"],
                    "roadEntrances": [],
                    "terrainPosePolicy": "flat_or_small_step",
                    "supportPolicy": "full_footprint_support",
                    "clearanceBlocks": 2
                  }]
                }
                """).requireTemplate("geomantia:city/market_a", "a");

        assertEquals("geomantia:city/market_a", template.templateRef());
        assertEquals("a", template.variant());
        assertEquals(10, template.width());
        assertEquals(12, template.depth());
        assertEquals(CityTemplateTerrainPosePolicy.STRUCTURE_START_BEARD_THIN,
                template.terrainPosePolicy());
    }

    @Test
    void rotationSwapsDimensionsAndUsesClosedBounds() {
        CityTemplateCatalog.Template template = loader.load(catalogJson("[]")).requireTemplate("city:house", "oak");

        CityTemplatePlacementGeometry geometry = template.geometry(
                CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90,
                CityTemplatePlacementGeometry.Mirror.NONE);

        assertEquals(3, geometry.transformedSize().width());
        assertEquals(6, geometry.transformedSize().height());
        assertEquals(4, geometry.transformedSize().depth());
        assertEquals(new BlockBounds(0, 0, 2, 3), geometry.relativeBounds());
    }

    @Test
    void minecraftMirrorAndRotationTransformEntrancePositionAndDirection() {
        CityTemplateCatalog.Template template = loader.load(catalogJson("""
                {"x": 1, "z": 2, "direction": "NORTH"}
                """ )).requireTemplate("city:house", "oak");

        CityTemplatePlacementGeometry geometry = template.geometry(
                CityTemplatePlacementGeometry.Rotation.CLOCKWISE_90,
                CityTemplatePlacementGeometry.Mirror.LEFT_RIGHT);
        CityTemplatePlacementGeometry.TransformedRoadEntrance entrance = geometry.roadEntrances().get(0);

        assertEquals(new BlockPoint(2, 1), entrance.relativePosition());
        assertEquals(CityTemplatePlacementGeometry.Direction.WEST, entrance.direction());
        assertEquals(new BlockPoint(12, 21), entrance.worldPosition(new BlockPoint(10, 20)));
        assertEquals(new BlockBounds(10, 20, 12, 23), geometry.worldBounds(new BlockPoint(10, 20)));
    }

    @Test
    void rejectsMissingHashInvalidSizeOutOfBoundsEntranceAndInvalidTransforms() {
        CityTemplateCatalog.CatalogException missingHash = assertThrows(
                CityTemplateCatalog.CatalogException.class,
                () -> loader.load(catalogJson("[]").replace("sha256:house", "")));
        assertEquals("CITY_TEMPLATE_CATALOG_FIELD_MISSING", missingHash.reasonCode());

        CityTemplateCatalog.CatalogException badSize = assertThrows(
                CityTemplateCatalog.CatalogException.class,
                () -> loader.load(catalogJson("[]").replace("\"width\": 4", "\"width\": 0")));
        assertEquals("CITY_TEMPLATE_CATALOG_SIZE_INVALID", badSize.reasonCode());

        CityTemplateCatalog.CatalogException badEntrance = assertThrows(
                CityTemplateCatalog.CatalogException.class,
                () -> loader.load(catalogJson("""
                        {"x": 4, "z": 2, "direction": "NORTH"}
                        """)));
        assertEquals("CITY_TEMPLATE_CATALOG_ENTRANCE_OUT_OF_BOUNDS", badEntrance.reasonCode());

        CityTemplateCatalog.CatalogException badRotation = assertThrows(
                CityTemplateCatalog.CatalogException.class,
                () -> loader.load(catalogJson("[]").replace("CLOCKWISE_90", "DIAGONAL")));
        assertEquals("CITY_TEMPLATE_CATALOG_ROTATION_INVALID", badRotation.reasonCode());
    }

    @Test
    void variantsAreStableAndRequireExplicitVariantSelection() {
        String first = """
                {"buildingSemantic":"residential","style":"medieval","templateId":"city:house","nbtFile":"templates/house_a.nbt","contentHash":"sha256:a","variantId":"a","width":2,"height":3,"depth":2,"allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],"terrainPosePolicy":"flat","supportPolicy":"foundation","clearanceBlocks":0},
                {"buildingSemantic":"residential","style":"medieval","templateId":"city:house","nbtFile":"templates/house_b.nbt","contentHash":"sha256:b","variantId":"b","width":2,"height":3,"depth":2,"allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],"terrainPosePolicy":"flat","supportPolicy":"foundation","clearanceBlocks":0}
                """;
        String second = """
                {"buildingSemantic":"residential","style":"medieval","templateId":"city:house","nbtFile":"templates/house_b.nbt","contentHash":"sha256:b","variantId":"b","width":2,"height":3,"depth":2,"allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],"terrainPosePolicy":"flat","supportPolicy":"foundation","clearanceBlocks":0},
                {"buildingSemantic":"residential","style":"medieval","templateId":"city:house","nbtFile":"templates/house_a.nbt","contentHash":"sha256:a","variantId":"a","width":2,"height":3,"depth":2,"allowedRotations":["NONE"],"allowedMirrors":["NONE"],"roadEntrances":[],"terrainPosePolicy":"flat","supportPolicy":"foundation","clearanceBlocks":0}
                """;
        CityTemplateCatalog firstCatalog = loader.load(catalogWithTemplates(first));
        CityTemplateCatalog secondCatalog = loader.load(catalogWithTemplates(second));

        assertEquals(List.of("a", "b"), firstCatalog.variants("residential", "medieval").stream()
                .map(CityTemplateCatalog.Template::variantId).toList());
        assertEquals(List.of("a", "b"), secondCatalog.variants("residential", "medieval").stream()
                .map(CityTemplateCatalog.Template::variantId).toList());
    }

    private static String catalogJson(String entrances) {
        String roadEntrances = entrances.equals("[]") ? "[]" : "[" + entrances + "]";
        return """
                {
                  "schemaVersion": "city_template_catalog.v0.1",
                  "templates": [{
                    "buildingSemantic": "residential",
                    "style": "medieval",
                    "templateId": "city:house",
                    "nbtFile": "templates/house_oak.nbt",
                    "contentHash": "sha256:house",
                    "variantId": "oak",
                    "width": 4,
                    "height": 6,
                    "depth": 3,
                    "allowedRotations": ["NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90"],
                    "allowedMirrors": ["NONE", "LEFT_RIGHT", "FRONT_BACK"],
                    "roadEntrances": %s,
                    "terrainPosePolicy": "flat_or_step",
                    "supportPolicy": "foundation",
                    "clearanceBlocks": 2
                  }]
                }
                """.formatted(roadEntrances);
    }

    private static String catalogWithTemplates(String templates) {
        return """
                {
                  "schemaVersion": "city_template_catalog.v0.1",
                  "templates": [%s]
                }
                """.formatted(templates);
    }
}
