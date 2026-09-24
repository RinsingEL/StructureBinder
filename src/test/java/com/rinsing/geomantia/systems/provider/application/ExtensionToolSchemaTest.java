package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionToolSchemaTest {
    private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
    @Test void rejectsUnsupportedSchemaAndChecksNestedArgumentsBeforeMutation() {
        assertThrows(IllegalArgumentException.class,()->ExtensionToolSchema.validateSchema(json("{\"type\":\"object\",\"oneOf\":[]}")));
        var schema=json("{\"type\":\"object\",\"required\":[\"goods\"],\"additionalProperties\":false,\"properties\":{\"goods\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"object\",\"required\":[\"count\"],\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10}}}}}}");
        ExtensionToolSchema.validateSchema(schema);
        ExtensionToolSchema.validate(schema,json("{\"goods\":[{\"count\":3}]}"));
        for(String value:new String[]{"{}","{\"goods\":[]}","{\"goods\":[{\"count\":0}]}","{\"goods\":[{\"count\":1.5}]}","{\"goods\":[{\"count\":\"2\"}]}","{\"goods\":[{\"count\":2}],\"scope\":\"other_city\"}"})
            assertThrows(IllegalArgumentException.class,()->ExtensionToolSchema.validate(schema,json(value)),value);
    }
}
