package storage_test

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"code_process_viewer/internal/importer/jigsawdebug"
	"code_process_viewer/internal/storage"
)

func TestStoreImportReviewAndReimport(t *testing.T) {
	t.Parallel()

	ctx := context.Background()
	repoRoot := t.TempDir()
	cacheRoot := filepath.Join(t.TempDir(), "cache")
	runDir := filepath.Join(t.TempDir(), "jigsaw_solver_debug", "20260407_150600_001")
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		t.Fatalf("创建 runDir 失败: %v", err)
	}

	trace := map[string]any{
		"debug_run_id":       "20260407_150600_001",
		"task_id":            "task-2",
		"city_id":            "city_beta",
		"group_id":           "group_blue",
		"debug_artifact_dir": runDir,
		"debug_trace": []any{
			map[string]any{
				"step_key":          "01_request_context",
				"title_zh":          "请求上下文",
				"goal_zh":           "确认输入",
				"action_summary_zh": "读取上下文",
				"implementation_zh": "提取请求体",
				"result_zh":         "上下文准备完成",
				"status":            "ok",
				"preview_image":     "01_request_context.png",
				"evidence":          map[string]any{"city_id": "city_beta"},
			},
			map[string]any{
				"step_key":          "02_runtime_jigsaws",
				"title_zh":          "运行时拼图",
				"goal_zh":           "整理运行时拼图",
				"action_summary_zh": "拼装 runtime jigsaws",
				"implementation_zh": "读取运行时拼图列表",
				"result_zh":         "等待人工核验",
				"status":            "warning",
				"evidence":          map[string]any{"runtime_count": 2},
			},
		},
	}
	writeJSONFile(t, filepath.Join(runDir, "trace.json"), trace)
	writeJSONFile(t, filepath.Join(runDir, "01_request_context.legend.json"), map[string]any{
		"request_area": "#00FFAA",
	})
	if err := os.WriteFile(filepath.Join(runDir, "01_request_context.png"), []byte("fake png bytes"), 0o644); err != nil {
		t.Fatalf("写入预览图失败: %v", err)
	}

	bundle, err := jigsawdebug.Import(jigsawdebug.ImportOptions{
		RepoRoot:     repoRoot,
		ArtifactPath: runDir,
		CacheRoot:    cacheRoot,
	})
	if err != nil {
		t.Fatalf("Import 返回错误: %v", err)
	}

	dbPath := filepath.Join(t.TempDir(), "runtime", "db", "viewer.db")
	store, err := storage.NewStore(dbPath, cacheRoot)
	if err != nil {
		t.Fatalf("NewStore 返回错误: %v", err)
	}
	defer store.Close()

	if err := store.ImportBundle(ctx, bundle, false); err != nil {
		t.Fatalf("首次导入失败: %v", err)
	}
	if err := store.ImportBundle(ctx, bundle, false); !errors.Is(err, storage.ErrSessionExists) {
		t.Fatalf("重复导入应返回 ErrSessionExists，实际: %v", err)
	}

	sessions, err := store.ListSessions(ctx)
	if err != nil {
		t.Fatalf("ListSessions 返回错误: %v", err)
	}
	if len(sessions) != 1 {
		t.Fatalf("expected 1 session, got %d", len(sessions))
	}

	sessionView, err := store.GetSession(ctx, bundle.Session.ID)
	if err != nil {
		t.Fatalf("GetSession 返回错误: %v", err)
	}
	if len(sessionView.Checkpoints) != 2 {
		t.Fatalf("expected 2 checkpoints, got %d", len(sessionView.Checkpoints))
	}

	graphView, err := store.GetGraph(ctx, bundle.Session.ID)
	if err != nil {
		t.Fatalf("GetGraph 返回错误: %v", err)
	}
	if len(graphView.Nodes) != 2 || len(graphView.Edges) != 1 {
		t.Fatalf("unexpected graph counts: nodes=%d edges=%d", len(graphView.Nodes), len(graphView.Edges))
	}

	nodeID := graphView.Nodes[0].ID
	nodeView, err := store.GetNode(ctx, nodeID)
	if err != nil {
		t.Fatalf("GetNode 返回错误: %v", err)
	}
	if nodeView.Detail.PreviewImage == "" {
		t.Fatalf("preview image should not be empty")
	}

	tables, err := store.GetNodeTables(ctx, nodeID)
	if err != nil {
		t.Fatalf("GetNodeTables 返回错误: %v", err)
	}
	if len(tables) != 2 {
		t.Fatalf("expected 2 tables for first node, got %d", len(tables))
	}

	review, err := store.SaveReview(ctx, nodeID, "concern", "这里还需要补日志")
	if err != nil {
		t.Fatalf("SaveReview 返回错误: %v", err)
	}
	if review.ReviewState != "concern" {
		t.Fatalf("unexpected review state: %s", review.ReviewState)
	}

	updatedNode, err := store.GetNode(ctx, nodeID)
	if err != nil {
		t.Fatalf("GetNode after review 返回错误: %v", err)
	}
	if updatedNode.Review == nil || updatedNode.Review.ReviewState != "concern" {
		t.Fatalf("review should be persisted on node")
	}

	if err := store.ImportBundle(ctx, bundle, true); err != nil {
		t.Fatalf("replace_existing 导入失败: %v", err)
	}
	reimportedNode, err := store.GetNode(ctx, nodeID)
	if err != nil {
		t.Fatalf("GetNode after reimport 返回错误: %v", err)
	}
	if reimportedNode.Review == nil || reimportedNode.Review.ReviewState != "concern" {
		t.Fatalf("review should survive reimport")
	}

	cacheFile := filepath.Join(cacheRoot, bundle.Session.ID+".json")
	raw, err := os.ReadFile(cacheFile)
	if err != nil {
		t.Fatalf("读取 session cache 失败: %v", err)
	}

	var cachePayload struct {
		Reviews map[string]map[string]any `json:"reviews"`
	}
	if err := json.Unmarshal(raw, &cachePayload); err != nil {
		t.Fatalf("解析 session cache 失败: %v", err)
	}
	if cachePayload.Reviews[nodeID]["review_state"] != "concern" {
		t.Fatalf("session cache 未保留 review 状态")
	}
}

func writeJSONFile(t *testing.T, path string, payload any) {
	t.Helper()
	raw, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		t.Fatalf("序列化 JSON 失败: %v", err)
	}
	if err := os.WriteFile(path, raw, 0o644); err != nil {
		t.Fatalf("写入 JSON 文件失败: %v", err)
	}
}
