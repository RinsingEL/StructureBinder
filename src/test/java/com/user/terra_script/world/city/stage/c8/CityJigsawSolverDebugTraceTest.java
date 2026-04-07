package com.user.terra_script.world.city.stage.c8;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityJigsawSolverDebugTraceTest {
    @TempDir
    Path tempDir;

    @Test
    void traceContainsSummaryImplementationResultAndPreview() throws Exception {
        CityJigsawSolverDebugTrace trace = CityJigsawSolverDebugTrace.create(null, tempDir, "city_demo", "g_market_02");
        JsonObject evidence = new JsonObject();
        evidence.addProperty("connector_count", 2);
        trace.addStep(
                "02_runtime_jigsaws",
                "扫描 runtime jigsaw",
                "找出真实拼图方块。",
                "扫描当前 parent 模板。",
                "通过 StructureTemplate.filterBlocks(..., Blocks.JIGSAW) 扫描 runtime jigsaw。",
                "共找到 2 个 runtime jigsaw。",
                "ok",
                evidence
        );
        trace.attachPreview("02_runtime_jigsaws", trace.previewImagePath("02_runtime_jigsaws.png"));

        JsonArray steps = trace.toJson();
        JsonObject first = steps.get(0).getAsJsonObject();
        assertEquals("02_runtime_jigsaws", first.get("step_key").getAsString());
        assertTrue(first.has("action_summary_zh"));
        assertTrue(first.has("implementation_zh"));
        assertTrue(first.has("result_zh"));
        assertTrue(first.has("preview_image"));
        assertEquals(2, first.getAsJsonObject("evidence").get("connector_count").getAsInt());
    }

    @Test
    void writeTracePersistsTraceAndPreviewIndex() throws Exception {
        CityJigsawSolverDebugTrace trace = CityJigsawSolverDebugTrace.create(null, tempDir, "city_demo", "g_market_02");
        trace.addStep(
                "01_request_context",
                "装载上下文",
                "确认请求输入。",
                "读取 C8/C6 上下文。",
                "通过已落盘产物恢复 parent placement。",
                "上下文读取完成。",
                "ok",
                new JsonObject()
        );
        trace.attachPreview("01_request_context", trace.previewImagePath("01_request_context.png"));
        trace.writeTrace();

        Path traceFile = trace.artifactDir().resolve("trace.json");
        assertTrue(Files.exists(traceFile));
        String content = Files.readString(traceFile);
        assertTrue(content.contains("\"debug_run_id\""));
        assertTrue(content.contains("\"debug_preview_steps\""));
        assertTrue(content.contains("01_request_context.png"));
    }
}
