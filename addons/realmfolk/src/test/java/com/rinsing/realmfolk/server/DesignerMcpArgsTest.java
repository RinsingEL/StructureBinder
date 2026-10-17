package com.rinsing.realmfolk.server;

import com.google.gson.JsonParser;
import com.rinsing.realmfolk.design.QuickCityPlan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesignerMcpArgsTest {
    @Test void quickCityAcceptsBothBooleanValuesInItsPublishedSchema() {
        var schema=DesignerMcp.schema(QuickCityPlan.Input.class);
        assertDoesNotThrow(()->DesignerMcp.validateArgs(JsonParser.parseString("""
                {"groups":[{"id":"students","requiresWorkplace":false,"servesMeals":false},
                           {"id":"cooks","requiresWorkplace":true,"servesMeals":true}]}
                """),schema));
    }

    @Test void quickCityRejectsStringNumericAndNullBooleanLookalikes() {
        var schema=DesignerMcp.schema(QuickCityPlan.Input.class);
        for(String value:java.util.List.of("\"false\"","0","null"))
            assertThrows(IllegalArgumentException.class,()->DesignerMcp.validateArgs(
                    JsonParser.parseString("{\"groups\":[{\"requiresWorkplace\":"+value+"}]}"),schema));
    }
}
