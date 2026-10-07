package com.rinsing.geomantia.systems.city.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CityMaterialCatalogBrowserTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private JsonObject catalog() {
        JsonArray profiles = JsonParser.parseString("""
                [{semanticProfileId:'a',functionTerms:['果蔬销售','家庭居住'],planningRoleTerms:['planning_role.key'],styleTerms:['中式'],terrainModes:['SURFACE']},
                 {semanticProfileId:'b',functionTerms:['零售','烘焙'],planningRoleTerms:['planning_role.fill'],styleTerms:['中世纪']},
                 {semanticProfileId:'c',functionTerms:['商业'],planningRoleTerms:['planning_role.anchor'],styleTerms:['中世纪']},
                 {semanticProfileId:'d',functionTerms:['新用途','新用途'],planningRoleTerms:[],styleTerms:['东方']},
                 {semanticProfileId:'e',functionTerms:['工具销售','果蔬销售'],planningRoleTerms:['planning_role.fill','planning_role.key'],styleTerms:['中式','东方']},
                 {semanticProfileId:'f',functionTerms:['育苗设施遗存'],planningRoleTerms:['planning_role.structure'],styleTerms:['奇幻']},
                 {semanticProfileId:'SR-F01-v01',functionTerms:['零售','烘焙'],planningRoleTerms:['planning_role.fill'],styleTerms:['蒸汽朋克']}]
                """).getAsJsonArray();
        JsonObject catalog = json("{structureCatalog:{},referenceCatalog:{}}");
        catalog.getAsJsonObject("structureCatalog").add("semanticProfiles", profiles);
        JsonArray refs = new JsonArray();
        for (JsonElement profile : profiles) { JsonObject ref = new JsonObject();
            ref.add("structureRef", profile.getAsJsonObject().get("semanticProfileId")); refs.add(ref); }
        catalog.getAsJsonObject("referenceCatalog").add("structureRefs", refs);
        return catalog;
    }
    private JsonObject query(String request) { return CityMaterialCatalogBrowser.browse(catalog(), json(request)); }
    private JsonObject facet(JsonObject result, String type, String field, String value) {
        return result.getAsJsonObject("facets").getAsJsonArray(type).asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> row.get(field).getAsString().equals(value)).findFirst().orElseThrow();
    }
    @Test void authoredCategoriesAndTagsIntersectWithoutInventingCityRoles() {
        var snapshot=catalog();var profiles=snapshot.getAsJsonObject("structureCatalog").getAsJsonArray("semanticProfiles");
        profiles.get(0).getAsJsonObject().addProperty("category","common");
        profiles.get(0).getAsJsonObject().add("assetTags",JsonParser.parseString("['infrastructure','landscape']"));
        profiles.get(1).getAsJsonObject().addProperty("category","specialty");
        profiles.get(1).getAsJsonObject().add("assetTags",JsonParser.parseString("['landscape']"));
        var result=CityMaterialCatalogBrowser.browse(snapshot,json("{filters:{categories:['common'],assetTags:['infrastructure','landscape'],styles:['中式']},limit:0}"));
        assertEquals(1,result.get("matchedCount").getAsInt());assertEquals(0,result.getAsJsonArray("candidates").size());
        assertEquals(1,facet(result,"categories","term","common").get("count").getAsInt());
        assertEquals(1,facet(result,"assetTags","term","landscape").get("count").getAsInt());
        result=CityMaterialCatalogBrowser.browse(snapshot,json("{filters:{categories:['common']}}"));
        var candidate=result.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals("common",candidate.getAsJsonObject("classification").get("category").getAsString());
        assertFalse(candidate.getAsJsonObject("classification").has("cityRole"));
        assertEquals(0,CityMaterialCatalogBrowser.browse(snapshot,json("{filters:{categories:['common'],assetTags:['infrastructure'],styles:['中世纪']}}")).get("matchedCount").getAsInt());
        assertThrows(IllegalArgumentException.class,()->CityMaterialCatalogBrowser.browse(snapshot,json("{filters:{categories:['core']}}")));
    }
    @Test void coreFunctionChildAndStyleNarrowInEitherOrder() {
        var core = query("{filters:{roles:['core']},limit:0}");
        assertEquals(3, core.get("matchedCount").getAsInt());
        assertEquals(3, facet(core,"functions","id","commerce").get("count").getAsInt());
        assertEquals(2, facet(core,"styles","term","中式").get("count").getAsInt());
        var retail = query("{filters:{roles:['core'],functionIds:['retail']},limit:1}");
        assertEquals(2, retail.get("matchedCount").getAsInt());
        assertEquals(2, facet(retail,"functions","id","commerce").get("count").getAsInt());
        assertEquals(2, facet(retail,"functions","id","retail.food.produce").get("count").getAsInt());
        assertEquals(1, facet(retail,"styles","term","东方").get("count").getAsInt());
        var child = query("{filters:{roles:['core'],functionIds:['retail.tools.work'],styles:['中式']}}");
        assertEquals(1, child.get("matchedCount").getAsInt());
        assertEquals("e", child.getAsJsonArray("candidates").get(0).getAsJsonObject().get("structureRef").getAsString());
        assertEquals(0, query("{filters:{roles:['fill'],functionIds:['retail.tools.work']}}").get("matchedCount").getAsInt());
    }
    @Test void parentsDoNotInventChildrenAndSpecificRulesDoNotGeneralize() {
        assertEquals(0, query("{filters:{rawFunctionTerms:['商业'],functionIds:['retail']}}").get("matchedCount").getAsInt());
        assertEquals(1, query("{filters:{functionIds:['retail.food.bread']}}").get("matchedCount").getAsInt());
        assertEquals(0, query("{filters:{functionIds:['retail.food.bread'],styles:['中世纪']}}").get("matchedCount").getAsInt());
        assertEquals(0, query("{filters:{rawFunctionTerms:['育苗设施遗存'],functionIds:['farming']}}").get("matchedCount").getAsInt());
        var commerce = query("{filters:{functionIds:['commerce']}}");
        assertEquals(1, facet(commerce,"functions","id","commerce").get("directCount").getAsInt());
    }
    @Test void anyOnlyChangesFunctionsOtherDimensionsStillIntersectAndUnknownTermsRemainUsable() {
        assertEquals(1, query("{filters:{functionIds:['retail','housing'],functionMode:'all'}}").get("matchedCount").getAsInt());
        assertEquals(2, query("{filters:{roles:['fill'],functionIds:['retail','housing'],functionMode:'any'}}").get("matchedCount").getAsInt());
        var unknown = query("{filters:{rawFunctionTerms:['新用途'],roles:['unknown']}}");
        assertEquals(1, unknown.get("matchedCount").getAsInt());
        assertEquals(1, unknown.getAsJsonObject("facets").get("unclassifiedCount").getAsInt());
        assertFalse(facet(unknown,"rawFunctionTerms","term","新用途").get("mapped").getAsBoolean());
        assertEquals(1, query("{query:'新用途'}").get("matchedCount").getAsInt());
    }
    @Test void pagesAreStableCountsAreUnpagedAndBrowsingDoesNotMutateAuthorData() {
        var snapshot = catalog(); var before = snapshot.deepCopy();
        var first = CityMaterialCatalogBrowser.browse(snapshot, json("{limit:1}"));
        var second = CityMaterialCatalogBrowser.browse(snapshot, json("{limit:1,offset:1}"));
        assertEquals(7, first.get("matchedCount").getAsInt());
        assertEquals(first.get("facets"), second.get("facets"));
        assertEquals(1, first.get("nextOffset").getAsInt());
        assertNotEquals(first.get("candidates"), second.get("candidates"));
        assertTrue(query("{offset:100}").getAsJsonArray("candidates").isEmpty());
        assertFalse(query("{offset:2147483647}").get("hasMore").getAsBoolean());
        assertTrue(query("{limit:0}").getAsJsonArray("candidates").isEmpty());
        var candidate = second.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertEquals(JsonParser.parseString("['SURFACE']"), candidate.getAsJsonObject("authoredMetadata").get("terrainModes"));
        assertEquals(before, snapshot);
    }
    @Test void studioExportIdsRetainOnlyExplicitBreadShopRules() {
        var snapshot = catalog();
        var profiles = snapshot.getAsJsonObject("structureCatalog").getAsJsonArray("semanticProfiles");
        var refs = snapshot.getAsJsonObject("referenceCatalog").getAsJsonArray("structureRefs");
        for (String id : new String[]{"studio:ds-f01-v01", "studio:aa-f01-v01", "studio:ds-f01-v02"}) {
            profiles.add(json("{semanticProfileId:'" + id + "',functionTerms:['零售','烘焙'],planningRoleTerms:['planning_role.fill'],styleTerms:['沙漠']}"));
            refs.add(json("{structureRef:'" + id + "'}"));
        }
        var result = CityMaterialCatalogBrowser.browse(snapshot, json("{filters:{functionIds:['retail.food.bread'],styles:['沙漠']}}"));
        assertEquals(2, result.get("matchedCount").getAsInt());
        assertFalse(result.getAsJsonArray("candidates").toString().contains("studio:ds-f01-v02"));
    }
    @Test void badFiltersAndPaginationFailClearlyRatherThanBeingIgnored() {
        for (String request : new String[]{"{filters:null}","{filters:{functionIds:['missing']}}","{filters:{roles:['key']}}",
                "{filters:{functionMode:'either'}}","{filters:{style:'中式'}}","{filters:{functionIds:'retail'}}",
                "{filters:{styles:[42]}}","{filters:{rawFunctionTerms:['']}}","{limit:101}","{limit:-1}","{limit:1.5}",
                "{offset:-1}","{offset:'1'}","{offset:999999999999}","{query:null}"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> query(request), request);
            assertTrue(error.getMessage().startsWith("CITY_DESIGN_SESSION_INVALID"), error.getMessage());
        }
    }
}
