import assert from "node:assert/strict";
import test from "node:test";
import axios from "axios";
import { realmHandlers } from "../dist/src/realm/handlers.js";
import { TIMEOUTS } from "../dist/src/shared/http.js";

test("design-solving submission receives compiler budget without retrying or changing its payload", async () => {
  const original = axios.post;
  const calls = [];
  axios.post = async (...args) => {
    calls.push(args);
    return { data: { ok: true } };
  };
  try {
    const input = { runId: "run", citySeedId: "city", contextId: "context", autoAdvanceAfterD4: false };
    await realmHandlers.city_submit_d4_blueprint(input);
    assert.equal(calls.length, 1);
    assert.match(calls[0][0], /\/submit_d4_blueprint$/);
    assert.deepEqual(calls[0][1], input);
    assert.equal(calls[0][2].timeout, TIMEOUTS.refresh);
    assert.ok(calls[0][2].timeout > TIMEOUTS.quick);
  } finally {
    axios.post = original;
  }
});
