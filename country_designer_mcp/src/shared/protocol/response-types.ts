import { TaskStatusPayload } from "./task-types.js";

export type McpTaskResponse = TaskStatusPayload | Record<string, unknown>;
