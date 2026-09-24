package com.rinsing.geomantia.systems.provider.application;

import com.google.gson.*;
import java.util.*;

/** Deliberately small, validated JSON Schema subset for addon tool arguments. */
final class ExtensionToolSchema {
    private static final Set<String> KEYS = Set.of("type", "properties", "required", "additionalProperties",
            "items", "enum", "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength",
            "description", "title", "default");
    static void validateSchema(JsonObject schema) {
        if (schema.toString().length() > 256_000) throw invalid("schema too large");
        checkSchema(schema, 0);
        if (!"object".equals(schema.get("type").getAsString())) throw invalid("root type must be object");
    }
    private static void checkSchema(JsonObject schema, int depth) {
        if (depth > 20 || !KEYS.containsAll(schema.keySet())) throw invalid("unsupported schema keyword or depth");
        if (!schema.has("type") || !schema.get("type").isJsonPrimitive()
                || !Set.of("object","array","string","integer","number","boolean","null").contains(schema.get("type").getAsString()))
            throw invalid("type is required");
        if (schema.has("properties")) {
            if (!schema.get("properties").isJsonObject()) throw invalid("properties must be an object");
            for (var value : schema.getAsJsonObject("properties").asMap().values()) {
                if (!value.isJsonObject()) throw invalid("property schema required");
                checkSchema(value.getAsJsonObject(), depth + 1);
            }
        }
        if (schema.has("required")) {
            if (!schema.get("required").isJsonArray()) throw invalid("required must be an array");
            for (var value : schema.getAsJsonArray("required"))
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("required names must be strings");
        }
        if (schema.has("items")) {
            if (!schema.get("items").isJsonObject()) throw invalid("items schema required");
            checkSchema(schema.getAsJsonObject("items"), depth + 1);
        }
        if ("array".equals(schema.get("type").getAsString()) && !schema.has("items")) throw invalid("array items required");
        if (schema.has("additionalProperties")) {
            var extra = schema.get("additionalProperties");
            if (extra.isJsonObject()) checkSchema(extra.getAsJsonObject(), depth + 1);
            else if (!extra.isJsonPrimitive() || !extra.getAsJsonPrimitive().isBoolean()) throw invalid("invalid additionalProperties");
        }
        if (schema.has("enum") && (!schema.get("enum").isJsonArray() || schema.getAsJsonArray("enum").isEmpty()))
            throw invalid("nonempty enum required");
        for (String key : List.of("minimum","maximum","minItems","maxItems","minLength","maxLength"))
            if (schema.has(key)) {
                var value = schema.get(key);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
                    throw invalid("invalid bound " + key);
                if (!key.equals("minimum") && !key.equals("maximum") &&
                        (value.getAsDouble() < 0 || value.getAsDouble() != Math.rint(value.getAsDouble())))
                    throw invalid("invalid size " + key);
            }
    }
    static void validate(JsonObject schema, JsonObject args) {
        if (args == null || args.toString().length() > 2 * 1024 * 1024) throw bad("$");
        check(schema, args, "$", 0);
    }
    private static void check(JsonObject schema, JsonElement value, String path, int depth) {
        if (depth > 20) throw bad(path);
        String type = schema.get("type").getAsString();
        boolean valid = switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "null" -> value.isJsonNull();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number", "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && Double.isFinite(value.getAsDouble())
                    && (!type.equals("integer") || value.getAsBigDecimal().stripTrailingZeros().scale() <= 0);
            default -> false;
        };
        if (!valid || schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) throw bad(path);
        if (type.equals("object")) {
            var object = value.getAsJsonObject();
            var properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
            if (schema.has("required")) for (var name : schema.getAsJsonArray("required"))
                if (!object.has(name.getAsString())) throw bad(path + "." + name.getAsString());
            for (var property : object.entrySet()) {
                if (properties.has(property.getKey())) check(properties.getAsJsonObject(property.getKey()), property.getValue(), path+"."+property.getKey(), depth+1);
                else if (schema.has("additionalProperties")) {
                    var extra = schema.get("additionalProperties");
                    if (extra.isJsonObject()) check(extra.getAsJsonObject(), property.getValue(), path+"."+property.getKey(), depth+1);
                    else if (!extra.getAsBoolean()) throw bad(path+"."+property.getKey());
                }
            }
        } else if (type.equals("array")) {
            bounds(schema, value.getAsJsonArray().size(), "minItems", "maxItems", path);
            for (var child : value.getAsJsonArray()) check(schema.getAsJsonObject("items"), child, path+"[]", depth+1);
        } else if (type.equals("string")) bounds(schema, value.getAsString().length(), "minLength", "maxLength", path);
        else if (type.equals("integer") || type.equals("number")) bounds(schema, value.getAsDouble(), "minimum", "maximum", path);
    }
    private static void bounds(JsonObject schema, double value, String min, String max, String path) {
        if (schema.has(min) && value < schema.get(min).getAsDouble() || schema.has(max) && value > schema.get(max).getAsDouble()) throw bad(path);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("PLANNING_EXTENSION_SCHEMA_INVALID: " + message); }
    private static IllegalArgumentException bad(String path) { return new IllegalArgumentException("PLANNING_EXTENSION_ARGUMENT_INVALID: " + path); }
}
