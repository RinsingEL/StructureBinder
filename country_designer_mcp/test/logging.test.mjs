import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { isTimeoutError } from "../dist/src/shared/http.js";
import { beginMcpCall, completeMcpCall } from "../dist/src/shared/logging.js";

test("writes linked start and completion events with timestamps", () => {
  const originalCwd = process.cwd();
  const directory = mkdtempSync(join(tmpdir(), "geomantia-mcp-log-"));
  try {
    process.chdir(directory);
    const call = beginMcpCall("city_query_worldgen_observations", {
      dimensionId: "minecraft:overworld",
      chunkX: 0,
      chunkZ: 0,
    });
    completeMcpCall(call, { content: [{ type: "text", text: "ok" }] }, "success");

    const entries = readFileSync(join(directory, "country_designer_mcp_log.jsonl"), "utf8")
      .trim()
      .split(/\r?\n/)
      .map((line) => JSON.parse(line));
    assert.equal(entries.length, 2);
    assert.equal(entries[0].eventType, "mcp_call_started");
    assert.equal(entries[1].eventType, "mcp_call_completed");
    assert.equal(entries[0].callId, entries[1].callId);
    assert.equal(entries[1].status, "success");
    assert.equal(typeof entries[1].durationMs, "number");
    assert.ok(entries[1].durationMs >= 0);
    assert.ok(entries[0].startedAt);
    assert.ok(entries[1].endedAt);
  } finally {
    process.chdir(originalCwd);
    rmSync(directory, { recursive: true, force: true });
  }
});

test("classifies transport timeouts separately from ordinary failures", () => {
  assert.equal(isTimeoutError({ code: "ECONNABORTED", message: "timeout of 10000ms exceeded" }), true);
  assert.equal(isTimeoutError({ response: { status: 504 }, message: "Request failed with status code 504" }), true);
  assert.equal(isTimeoutError({ response: { status: 408 }, message: "Request failed with status code 408" }), true);
  assert.equal(isTimeoutError(new Error("request timed out")), true);
  assert.equal(isTimeoutError(new Error("connection refused")), false);
});
