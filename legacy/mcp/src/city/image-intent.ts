import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { postJson } from "../shared/http.js";
import { TIMEOUTS } from "../shared/http/timeouts.js";

const DEFAULT_IMAGE_MODEL = "gpt-image-1";
const MC_API_URL = "http://localhost:5000";

export type ImageIntentGenerateResult = {
  generated: boolean;
  reason?: string;
  model?: string;
  endpoint?: string;
  image_base64?: string;
  image_path?: string;
  bytes?: number;
  error_code?: string;
  error?: string;
  import_result?: unknown;
};

type OpenAIImageRequestResult = {
  model: string;
  endpoint: string;
  image_base64: string;
  bytes: number;
};

type ImageApiMode = "openai_edit" | "generation_image_array";

export async function generateC1IntentImage(args: any, prepared: any): Promise<ImageIntentGenerateResult> {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return {
      generated: false,
      reason: "missing_openai_api_key",
    };
  }

  const prompt = await readPrompt(prepared);
  const baseMapPath = resolveArtifactPath(prepared, "base_map");
  const baseMap = baseMapPath && fs.existsSync(baseMapPath) ? fs.readFileSync(baseMapPath) : null;
  const image = await safeRequestOpenAIImage({
    apiKey,
    baseUrl: args.base_url,
    apiMode: args.api_mode,
    prompt,
    baseMap,
    baseMapName: "C1_base_map.png",
    size: normalizeImageSize(args.size),
    aspectRatio: normalizeAspectRatio(args.aspect_ratio || process.env.OPENAI_IMAGE_ASPECT_RATIO),
    quality: normalizeImageQuality(args.quality || process.env.OPENAI_IMAGE_QUALITY),
    referenceImages: collectArgumentImageRefs(args),
  });
  if (!image.ok) return image.failure;

  const importPayload: any = {
    city_id: args.city_id || prepared?.city_id || prepared?.request?.city_id,
    source_kind: "image2",
    prompt_id: `openai:${image.value.model}`,
    image_base64: image.value.image_base64,
    generated_at: new Date().toISOString(),
  };
  if (Array.isArray(args.color_mapping)) importPayload.color_mapping = args.color_mapping;
  const imported = await postJson(`${MC_API_URL}/city_c1_image_intent_import`, importPayload, TIMEOUTS.workflow);
  return {
    generated: true,
    model: image.value.model,
    endpoint: image.value.endpoint,
    image_base64: image.value.image_base64,
    bytes: image.value.bytes,
    import_result: imported.data,
  };
}

export async function smokeTestC1IntentImage(args: any): Promise<ImageIntentGenerateResult> {
  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    return {
      generated: false,
      reason: "missing_openai_api_key",
    };
  }

  const prompt = typeof args.prompt === "string" && args.prompt.trim().length > 0
    ? args.prompt
    : defaultSmokePrompt();
  const image = await safeRequestOpenAIImage({
    apiKey,
    baseUrl: args.base_url,
    apiMode: args.api_mode,
    prompt,
    size: normalizeImageSize(args.size),
    aspectRatio: normalizeAspectRatio(args.aspect_ratio || process.env.OPENAI_IMAGE_ASPECT_RATIO),
    quality: normalizeImageQuality(args.quality || process.env.OPENAI_IMAGE_QUALITY || "low"),
    referenceImages: collectArgumentImageRefs(args),
  });
  if (!image.ok) return image.failure;
  const imagePath = resolveSmokeSavePath(args);
  const bytes = Buffer.from(image.value.image_base64, "base64");
  fs.mkdirSync(path.dirname(imagePath), { recursive: true });
  fs.writeFileSync(imagePath, bytes);
  return {
    generated: true,
    model: image.value.model,
    endpoint: image.value.endpoint,
    image_path: imagePath,
    bytes: bytes.byteLength,
  };
}

export async function safeRequestOpenAIImage(options: {
  apiKey: string;
  baseUrl?: string;
  apiMode?: string;
  prompt: string;
  baseMap?: Buffer | null;
  baseMapName?: string;
  size?: string;
  aspectRatio?: string;
  quality?: string;
  referenceImages?: string[];
}): Promise<
  | { ok: true; value: OpenAIImageRequestResult }
  | { ok: false; failure: ImageIntentGenerateResult }
> {
  try {
    return { ok: true, value: await requestOpenAIImage(options) };
  } catch (error) {
    const summary = summarizeImageApiError(error);
    return {
      ok: false,
      failure: {
        generated: false,
        reason: "image_api_request_failed",
        error_code: summary.code,
        error: summary.message,
      },
    };
  }
}

async function requestOpenAIImage(options: {
  apiKey: string;
  baseUrl?: string;
  apiMode?: string;
  prompt: string;
  baseMap?: Buffer | null;
  baseMapName?: string;
  size?: string;
  aspectRatio?: string;
  quality?: string;
  referenceImages?: string[];
}): Promise<OpenAIImageRequestResult> {
  const model = process.env.OPENAI_IMAGE_MODEL || DEFAULT_IMAGE_MODEL;
  const size = options.size || "1024x1024";
  const apiBaseUrl = resolveOpenAIBaseUrl(options.baseUrl);
  const apiMode = resolveImageApiMode(options.apiMode, apiBaseUrl);
  const referenceImages = collectRequestImageRefs(options);
  const body: any = {
    model,
    prompt: options.prompt,
    size,
  };
  const aspectRatio = options.aspectRatio || aspectRatioFromSize(size);
  if (aspectRatio) body.aspect_ratio = aspectRatio;
  if (referenceImages.length > 0) body.image = referenceImages;
  if (options.quality) body.quality = options.quality;

  let endpoint = `${apiBaseUrl}/images/generations`;
  let init: RequestInit;
  if (options.baseMap && apiMode === "openai_edit") {
    const form = new FormData();
    form.set("model", model);
    form.set("prompt", options.prompt);
    form.set("size", size);
    if (options.quality) form.set("quality", options.quality);
    form.set("image", new Blob([new Uint8Array(options.baseMap)], { type: "image/png" }), options.baseMapName || "input.png");
    endpoint = `${apiBaseUrl}/images/edits`;
    init = {
      method: "POST",
      headers: { Authorization: `Bearer ${options.apiKey}` },
      body: form,
    };
  } else {
    init = {
      method: "POST",
      headers: {
        Authorization: `Bearer ${options.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    };
  }

  const response = await fetch(endpoint, init);
  const json = await readJsonResponse(response);
  if (!response.ok) {
    const error = new Error(json?.error?.message || `OpenAI image request failed with ${response.status}`) as Error & { status?: number; code?: string };
    error.status = response.status;
    error.code = response.status === 401
      ? "openai_auth_failed"
      : response.status === 429
        ? "openai_rate_limit_or_quota"
        : `openai_http_${response.status}`;
    throw error;
  }

  const imageBase64 = await extractImageBase64(json);
  if (!imageBase64) {
    throw new Error("OpenAI image response did not contain image data.");
  }
  return {
    model,
    endpoint,
    image_base64: imageBase64,
    bytes: Buffer.byteLength(imageBase64, "base64"),
  };
}

async function readPrompt(prepared: any): Promise<string> {
  const promptPath = resolveArtifactPath(prepared, "prompt");
  if (promptPath && fs.existsSync(promptPath)) return fs.readFileSync(promptPath, "utf8");
  return [
    "Create a clean 512x512 top-down city planning intent mask.",
    "Use distinct flat colors for boundary, district polygons, roads and anchors.",
    "Do not include labels, legends, gradients, shadows, or texture.",
  ].join("\n");
}

export function resolveArtifactPath(prepared: any, key: string): string | null {
  const rel = prepared?.artifacts?.[key] || prepared?.manifest?.artifacts?.[key];
  if (!rel || typeof rel !== "string") return null;
  if (path.isAbsolute(rel)) return rel;
  const roots = artifactRoots(prepared);
  for (const root of roots) {
    const candidate = path.resolve(root, "terra_script", rel);
    if (fs.existsSync(candidate)) return candidate;
  }
  for (const root of roots) {
    if (hasPreparedInputArtifact(root, prepared)) {
      return path.resolve(root, "terra_script", rel);
    }
  }
  return path.resolve(roots[0] || path.join(workspaceRoot(), "run"), "terra_script", rel);
}

function resolveSmokeSavePath(args: any): string {
  if (typeof args.save_path === "string" && args.save_path.trim().length > 0) {
    return path.resolve(args.save_path);
  }
  const saveDir = typeof args.save_dir === "string" && args.save_dir.trim().length > 0
    ? path.resolve(args.save_dir)
    : path.join(os.tmpdir(), "structurebinder-c1-image-intent-smoke");
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  return path.join(saveDir, `c1_image_intent_smoke_${stamp}.png`);
}

function workspaceRoot(): string {
  const cwd = process.cwd();
  return path.basename(cwd).toLowerCase() === "country_designer_mcp" ? path.dirname(cwd) : cwd;
}

function artifactRoots(prepared: any): string[] {
  const roots: string[] = [];
  appendRoot(roots, process.env.MC_RUN_DIR);
  appendRoot(roots, process.env.STRUCTUREBINDER_RUN_DIR);
  const root = workspaceRoot();
  const saves = path.join(root, "run", "saves");
  if (fs.existsSync(saves)) {
    for (const entry of fs.readdirSync(saves, { withFileTypes: true })) {
      if (entry.isDirectory()) appendRoot(roots, path.join(saves, entry.name));
    }
  }
  appendRoot(roots, path.join(root, "run"));
  if (prepared?.artifact_root && typeof prepared.artifact_root === "string") appendRoot(roots, prepared.artifact_root);
  return roots;
}

function appendRoot(roots: string[], value: any) {
  if (typeof value !== "string" || value.trim().length === 0) return;
  const resolved = path.resolve(value.trim());
  if (!roots.includes(resolved)) roots.push(resolved);
}

function hasPreparedInputArtifact(root: string, prepared: any): boolean {
  const artifacts = { ...(prepared?.manifest?.artifacts || {}), ...(prepared?.artifacts || {}) };
  for (const key of ["terrain_clean", "terrain_locator", "terrain_locator_json", "manifest", "geometry_prompt", "base_map", "prompt"]) {
    const value = artifacts[key];
    if (typeof value !== "string" || path.isAbsolute(value)) continue;
    if (fs.existsSync(path.resolve(root, "terra_script", value))) return true;
  }
  return false;
}

export function resolveOpenAIBaseUrl(value: any): string {
  const configured = typeof value === "string" && value.trim().length > 0
    ? value.trim()
    : (process.env.OPENAI_IMAGE_BASE_URL || process.env.OPENAI_BASE_URL || process.env.OPENAI_API_BASE_URL || "https://api.openai.com/v1");
  let url: URL;
  try {
    url = new URL(configured);
  } catch {
    return "https://api.openai.com/v1";
  }
  const cleanPath = url.pathname
    .replace(/\/+$/g, "")
    .replace(/\/chat\/completions$/i, "")
    .replace(/\/images\/generations$/i, "")
    .replace(/\/images\/edits$/i, "");
  url.pathname = cleanPath || "/v1";
  url.search = "";
  url.hash = "";
  return url.toString().replace(/\/+$/g, "");
}

function resolveImageApiMode(value: any, apiBaseUrl: string): ImageApiMode {
  if (value === "openai_edit" || value === "generation_image_array") return value;
  const envMode = process.env.OPENAI_IMAGE_API_MODE;
  if (envMode === "openai_edit" || envMode === "generation_image_array") return envMode;
  return apiBaseUrl === "https://api.openai.com/v1" ? "openai_edit" : "generation_image_array";
}

function collectArgumentImageRefs(args: any): string[] {
  const refs: string[] = [];
  appendImageRef(refs, args.reference_image_url);
  appendImageRef(refs, args.reference_image_data_url);
  appendImageRef(refs, args.image);
  appendImageRef(refs, args.images);
  appendImageRef(refs, args.reference_image_urls);
  return refs;
}

function collectRequestImageRefs(options: {
  baseMap?: Buffer | null;
  referenceImages?: string[];
}): string[] {
  const refs = [...(options.referenceImages || [])];
  if (options.baseMap) refs.push(`data:image/png;base64,${options.baseMap.toString("base64")}`);
  return refs;
}

function appendImageRef(refs: string[], value: any) {
  if (Array.isArray(value)) {
    for (const item of value) appendImageRef(refs, item);
    return;
  }
  if (typeof value === "string" && value.trim().length > 0) refs.push(value.trim());
}

function summarizeImageApiError(error: unknown): { code: string; message: string } {
  const err = error as any;
  const code = String(err?.code || err?.cause?.code || "");
  const status = Number(err?.status || err?.response?.status || 0);
  const message = String(err?.message || error || "Unknown image API error.");
  if (code === "UND_ERR_CONNECT_TIMEOUT" || /connect timeout/i.test(message)) {
    return { code: "network_connect_timeout", message };
  }
  if (status === 401 || code === "openai_auth_failed") {
    return { code: "openai_auth_failed", message };
  }
  if (status === 429 || code === "openai_rate_limit_or_quota" || /quota|rate limit/i.test(message)) {
    return { code: "openai_rate_limit_or_quota", message };
  }
  if (status > 0) {
    return { code: `openai_http_${status}`, message };
  }
  if (code) {
    return { code, message };
  }
  return { code: "image_api_request_failed", message };
}

async function readJsonResponse(response: Response): Promise<any> {
  const text = await response.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { raw: text };
  }
}

async function extractImageBase64(json: any): Promise<string | null> {
  const first = Array.isArray(json?.data) ? json.data[0] : null;
  if (!first) return null;
  if (typeof first.b64_json === "string") return first.b64_json;
  if (typeof first.image_base64 === "string") return first.image_base64;
  if (typeof first.url === "string") {
    const response = await fetch(first.url);
    if (!response.ok) throw new Error(`OpenAI image URL download failed with ${response.status}`);
    const buffer = Buffer.from(await response.arrayBuffer());
    return buffer.toString("base64");
  }
  return null;
}

function normalizeImageSize(value: any): string {
  const allowed = new Set(["1024x1024", "1024x1536", "1536x1024", "auto"]);
  return typeof value === "string" && allowed.has(value) ? value : "1024x1024";
}

function normalizeAspectRatio(value: any): string | undefined {
  const allowed = new Set(["21:9", "16:9", "4:3", "3:2", "1:1", "2:3", "3:4", "9:16", "9:21"]);
  return typeof value === "string" && allowed.has(value) ? value : undefined;
}

function normalizeImageQuality(value: any): string | undefined {
  const allowed = new Set(["low", "medium", "high", "auto"]);
  return typeof value === "string" && allowed.has(value) ? value : undefined;
}

function aspectRatioFromSize(size: string): string | undefined {
  switch (size) {
    case "1024x1024":
      return "1:1";
    case "1536x1024":
      return "3:2";
    case "1024x1536":
      return "2:3";
    default:
      return undefined;
  }
}

function defaultSmokePrompt(): string {
  return [
    "Create a clean top-down city planning intent mask for automated testing.",
    "Use flat machine-readable colors only: one dark outer city boundary, three solid district polygons, white road lines, and small magenta anchor dots.",
    "No labels, no legend, no gradients, no shadows, no texture.",
  ].join("\n");
}
