package jigsawdebug

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestImportBuildsBundleFromJigsawDebugArtifact(t *testing.T) {
	t.Parallel()

	repoRoot := t.TempDir()
	cacheRoot := filepath.Join(t.TempDir(), "cache")
	runDir := filepath.Join(t.TempDir(), "jigsaw_solver_debug", "20260407_150600_001")
	if err := os.MkdirAll(runDir, 0o755); err != nil {
		t.Fatalf("创建 runDir 失败: %v", err)
	}

	trace := map[string]any{
		"debug_run_id":       "20260407_150600_001",
		"task_id":            "task-1",
		"city_id":            "city_alpha",
		"group_id":           "group_red",
		"debug_artifact_dir": runDir,
		"debug_trace": []any{
			map[string]any{
				"step_key":          "01_request_context",
				"title_zh":          "请求上下文",
				"goal_zh":           "确认求解输入",
				"action_summary_zh": "读取运行参数",
				"implementation_zh": "从 MCP 请求中提取上下文",
				"result_zh":         "上下文已准备",
				"status":            "ok",
				"preview_image":     "01_request_context.png",
				"evidence":          map[string]any{"city_id": "city_alpha", "group_id": "group_red"},
			},
			map[string]any{
				"step_key":          "02_runtime_jigsaws",
				"title_zh":          "运行时拼图",
				"goal_zh":           "确认运行时 jigsaw 输入",
				"action_summary_zh": "整理 runtime jigsaws",
				"implementation_zh": "生成待求解拼图列表",
				"result_zh":         "缺少预览图但节点仍可查看",
				"status":            "warning",
				"evidence":          map[string]any{"runtime_count": 4},
			},
		},
	}
	writeJSONFile(t, filepath.Join(runDir, "trace.json"), trace)
	writeJSONFile(t, filepath.Join(runDir, "01_request_context.legend.json"), map[string]any{
		"request_area": "#FFAA00",
	})
	if err := os.WriteFile(filepath.Join(runDir, "01_request_context.png"), []byte("fake png bytes"), 0o644); err != nil {
		t.Fatalf("写入预览图失败: %v", err)
	}

	bundle, err := Import(ImportOptions{
		RepoRoot:     repoRoot,
		ArtifactPath: runDir,
		CacheRoot:    cacheRoot,
	})
	if err != nil {
		t.Fatalf("Import 返回错误: %v", err)
	}

	if bundle.Session.ID != "20260407_150600_001" {
		t.Fatalf("unexpected session id: %s", bundle.Session.ID)
	}
	if len(bundle.Nodes) != 2 {
		t.Fatalf("expected 2 nodes, got %d", len(bundle.Nodes))
	}
	if len(bundle.Edges) != 1 {
		t.Fatalf("expected 1 edge, got %d", len(bundle.Edges))
	}
	if bundle.Nodes[0].PreviewImage == "" {
		t.Fatalf("first node preview image should not be empty")
	}
	if bundle.Nodes[1].PreviewImage != "" {
		t.Fatalf("second node preview image should be empty when source preview is missing")
	}

	firstDetail := bundle.Details[bundle.Nodes[0].ID]
	if firstDetail.Legend["request_area"] != "#FFAA00" {
		t.Fatalf("legend was not loaded correctly")
	}
	if len(bundle.Tables[bundle.Nodes[0].ID]) != 2 {
		t.Fatalf("first node should have evidence and legend tables")
	}
	if len(bundle.Tables[bundle.Nodes[1].ID]) != 1 {
		t.Fatalf("second node should only have evidence table")
	}

	summary := bundle.Import.ImportSummary
	if summary["missing_preview_count"] != 1 {
		t.Fatalf("expected missing_preview_count = 1, got %#v", summary["missing_preview_count"])
	}

	copiedPreview := filepath.Join(cacheRoot, bundle.Session.ID, "assets", "01_request_context.png")
	if _, err := os.Stat(copiedPreview); err != nil {
		t.Fatalf("expected copied preview to exist: %v", err)
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
