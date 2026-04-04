export type TaskState =
  | "ACCEPTED"
  | "RUNNING"
  | "WAITING_FOR_AI"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "TIMED_OUT";

export type TaskStatusPayload = {
  task_id: string;
  state: TaskState;
  message?: string;
  progress_percent?: number;
  progress_current?: number;
  progress_total?: number;
  active_operation?: string;
  waiting_for_ai?: boolean;
  next_action?: string;
  started_at?: number;
  heartbeat_at?: number;
  finished_at?: number | null;
  result?: unknown;
  error?: string | null;
};
