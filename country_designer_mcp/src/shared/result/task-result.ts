import { textResult } from "./text-result.js";

export function taskResult(payload: unknown) {
  return textResult(JSON.stringify(payload, null, 2));
}
