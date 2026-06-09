import fs from "node:fs";
import path from "node:path";
import { MC_API_URL, postJson } from "../shared/http.js";
import { TIMEOUTS } from "../shared/http/timeouts.js";
import { resolveArtifactPath, resolveOpenAIBaseUrl, safeRequestOpenAIImage } from "./image-intent.js";

const DEFAULT_GEOMETRY_MODEL = "gpt-5.5";

export async function generateC1GeometryIntent(args: any, prepared: any) {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return {
      generated: false,
      reason: "missing_openai_api_key",
      prepared,
      next_action: "Set OPENAI_API_KEY or call city_c1_geometry_import with geometry_json/geometry_path.",
    };
  }

  const concept = await generateConceptImage(args, prepared, apiKey);
  if (!concept.generated) {
    return {
      generated: false,
      reason: concept.reason,
      error_code: concept.error_code,
      error: concept.error,
      prepared,
      next_action: "Fix image API settings or import image2_concept.png manually, then rerun geometry generation/import.",
    };
  }
  if (!concept.image_path) {
    return {
      generated: false,
      reason: "missing_concept_image_path",
      prepared,
      next_action: "Rerun concept generation or import image2_concept.png manually.",
    };
  }

  const geometryResponse = await requestGeometryJson(args, prepared, apiKey, concept.image_path, "geometry");
  if (!geometryResponse.ok) return geometryResponse.failure;

  const importRes = await postJson(`${MC_API_URL}/city_c1_geometry_import`, {
    city_id: prepared.city_id,
    geometry_json: geometryResponse.json,
  }, TIMEOUTS.workflow);

  const reviewResponse = await requestGeometryJson(args, prepared, apiKey, concept.image_path, "review");
  let patchRes: any = null;
  if (reviewResponse.ok) {
    patchRes = await postJson(`${MC_API_URL}/city_c1_geometry_patch`, {
      city_id: prepared.city_id,
      patch_json: reviewResponse.json,
    }, TIMEOUTS.workflow);
  }

  const dataRes = await postJson(`${MC_API_URL}/city_c1_geometry_data`, { city_id: prepared.city_id }, TIMEOUTS.workflow);
  return {
    generated: true,
    city_id: prepared.city_id,
    concept,
    geometry_model: geometryResponse.model,
    geometry_import_result: importRes.data,
    review_model: reviewResponse.ok ? reviewResponse.model : null,
    review_error: reviewResponse.ok ? null : reviewResponse.failure,
    patch_result: patchRes?.data || null,
    data: dataRes.data,
  };
}

async function generateConceptImage(args: any, prepared: any, apiKey: string) {
  const prompt = conceptPrompt(prepared);
  const cleanPath = resolveArtifactPath(prepared, "terrain_clean");
  const locatorPath = resolveArtifactPath(prepared, "terrain_locator");
  const references: string[] = [];
  appendFileAsDataUrl(references, cleanPath);
  appendFileAsDataUrl(references, locatorPath);
  const image = await safeRequestOpenAIImage({
    apiKey,
    baseUrl: args.base_url,
    apiMode: args.api_mode,
    prompt,
    size: args.size,
    aspectRatio: args.aspect_ratio || "1:1",
    quality: args.quality,
    referenceImages: references,
  });
  if (!image.ok) return image.failure;
  const conceptPath = resolveArtifactAbsolute(prepared, "image2_concept");
  fs.mkdirSync(path.dirname(conceptPath), { recursive: true });
  fs.writeFileSync(conceptPath, Buffer.from(image.value.image_base64, "base64"));
  return {
    generated: true,
    model: image.value.model,
    endpoint: image.value.endpoint,
    image_path: conceptPath,
    bytes: image.value.bytes,
  };
}

async function requestGeometryJson(args: any, prepared: any, apiKey: string, conceptPath: string, mode: "geometry" | "review"): Promise<
  | { ok: true; model: string; json: any; raw_path: string }
  | { ok: false; failure: any }
> {
  const model = mode === "review"
    ? (process.env.OPENAI_REVIEW_MODEL || process.env.OPENAI_GEOMETRY_MODEL || DEFAULT_GEOMETRY_MODEL)
    : (process.env.OPENAI_GEOMETRY_MODEL || DEFAULT_GEOMETRY_MODEL);
  const endpoint = resolveChatCompletionsUrl(args.base_url);
  const prompt = mode === "review" ? reviewPrompt(prepared) : geometryPrompt(prepared);
  const images = [
    resolveArtifactAbsolute(prepared, "terrain_clean"),
    resolveArtifactAbsolute(prepared, "terrain_locator"),
    conceptPath,
  ];
  if (mode === "review") images.push(resolveArtifactAbsolute(prepared, "geometry_overlay"));

  try {
    const body = {
      model,
      messages: [
        {
          role: "user",
          content: [
            { type: "text", text: prompt },
            ...images.filter((p) => fs.existsSync(p)).map((p) => ({
              type: "image_url",
              image_url: { url: fileToDataUrl(p) },
            })),
          ],
        },
      ],
      response_format: { type: "json_object" },
    };
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    const rawPath = resolveArtifactAbsolute(prepared, mode === "review" ? "geometry_review" : "geometry_design");
    fs.mkdirSync(path.dirname(rawPath), { recursive: true });
    if (!response.ok) {
      fs.writeFileSync(rawPath, text);
      return { ok: false, failure: { reason: "chat_completion_failed", status: response.status, error: text, raw_path: rawPath } };
    }
    const parsed = JSON.parse(text);
    const content = parsed?.choices?.[0]?.message?.content ?? text;
    const json = extractJson(content);
    fs.writeFileSync(rawPath, JSON.stringify(json, null, 2));
    return { ok: true, model, json, raw_path: rawPath };
  } catch (error: any) {
    return { ok: false, failure: { reason: "chat_completion_request_failed", error: error?.message || String(error) } };
  }
}

function geometryPrompt(prepared: any): string {
  const locator = readJsonArtifact(prepared, "terrain_locator_json");
  return [
    "Output JSON only for C1_geometry_design.json.",
    "Use terrain_clean for true terrain, terrain_locator for coordinates, and image2_concept only as design inspiration.",
    "Required schema: coordinate_space, city_boundary.polygon, district_polygons, road_sketch.paths, anchor_points.",
    "Use 4 to 7 large district polygons. Keep roads connected. Avoid ordinary districts/roads over water unless marked port/bridge/waterfront.",
    "Locator JSON:",
    JSON.stringify(locator || prepared.coordinate || {}, null, 2),
  ].join("\n");
}

function reviewPrompt(prepared: any): string {
  const locator = readJsonArtifact(prepared, "terrain_locator_json");
  return [
    "Review C1_geometry_overlay against terrain_clean, terrain_locator, and image2_concept.",
    "Output JSON only with {\"operations\": []}.",
    "Allowed op values: replace_city_boundary, upsert_district, remove_district, upsert_road_path, remove_road_path, upsert_anchor, remove_anchor.",
    "Return an empty operations array if no patch is needed.",
    "Fix only clear coordinate drift, river/sea conflicts, disconnected roads, or unreasonable district semantics.",
    "Locator JSON:",
    JSON.stringify(locator || prepared.coordinate || {}, null, 2),
  ].join("\n");
}

function conceptPrompt(prepared: any): string {
  return [
    "Create a 1:1 top-down city planning concept image.",
    "Use the clean terrain and locator grid as references, but this is only a visual concept, not a machine mask.",
    "Draw a plausible city boundary, 4 to 7 large district polygons, connected main roads, and anchors for gates/plaza/port/bridge/landmark.",
    "Keep the plan readable and avoid excessive small fragments.",
    `City context: ${JSON.stringify(prepared?.request || {}, null, 2)}`,
  ].join("\n");
}

function resolveChatCompletionsUrl(value: any): string {
  const raw = typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : (process.env.OPENAI_BASE_URL || process.env.OPENAI_API_BASE_URL || "https://api.openai.com/v1/chat/completions");
  if (/\/chat\/completions\/?$/i.test(raw)) return raw.replace(/\/+$/g, "");
  const base = resolveOpenAIBaseUrl(raw);
  return `${base}/chat/completions`;
}

function resolveArtifactAbsolute(prepared: any, key: string): string {
  const resolved = resolveArtifactPath(prepared, key);
  if (resolved) return resolved;
  throw new Error(`Missing artifact path: ${key}`);
}

function readJsonArtifact(prepared: any, key: string): any {
  const p = resolveArtifactPath(prepared, key);
  if (!p || !fs.existsSync(p)) return null;
  try {
    return JSON.parse(fs.readFileSync(p, "utf8"));
  } catch {
    return null;
  }
}

function appendFileAsDataUrl(refs: string[], filePath: string | null) {
  if (!filePath || !fs.existsSync(filePath)) return;
  refs.push(fileToDataUrl(filePath));
}

function fileToDataUrl(filePath: string): string {
  const data = fs.readFileSync(filePath).toString("base64");
  return `data:image/png;base64,${data}`;
}

function extractJson(value: any): any {
  if (typeof value !== "string") return value;
  const trimmed = value.trim();
  try {
    return JSON.parse(trimmed);
  } catch {
    const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
    if (fenced) return JSON.parse(fenced[1].trim());
    const first = trimmed.indexOf("{");
    const last = trimmed.lastIndexOf("}");
    if (first >= 0 && last > first) return JSON.parse(trimmed.slice(first, last + 1));
    throw new Error("Chat completion did not contain parseable JSON.");
  }
}
