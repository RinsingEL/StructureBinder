package jigsawdebug

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"code_process_viewer/internal/session"
)

type ImportOptions struct {
	RepoRoot     string
	DocsRepoRoot string
	ArtifactPath string
	CacheRoot    string
}

type tracePayload struct {
	DebugRunID       string      `json:"debug_run_id"`
	TaskID           string      `json:"task_id"`
	CityID           string      `json:"city_id"`
	GroupID          string      `json:"group_id"`
	DebugArtifactDir string      `json:"debug_artifact_dir"`
	DebugTrace       []traceStep `json:"debug_trace"`
}

type traceStep struct {
	StepKey          string         `json:"step_key"`
	TitleZh          string         `json:"title_zh"`
	GoalZh           string         `json:"goal_zh"`
	ActionSummaryZh  string         `json:"action_summary_zh"`
	ImplementationZh string         `json:"implementation_zh"`
	ResultZh         string         `json:"result_zh"`
	Status           string         `json:"status"`
	Evidence         map[string]any `json:"evidence"`
	PreviewImage     string         `json:"preview_image"`
}

func Import(opts ImportOptions) (session.Bundle, error) {
	runDir, tracePath, err := resolveRunDir(opts.ArtifactPath)
	if err != nil {
		return session.Bundle{}, err
	}
	payload, err := loadTrace(tracePath)
	if err != nil {
		return session.Bundle{}, err
	}
	if payload.DebugRunID == "" {
		return session.Bundle{}, fmt.Errorf("trace.json 缺少 debug_run_id: %s", tracePath)
	}
	if len(payload.DebugTrace) == 0 {
		return session.Bundle{}, fmt.Errorf("trace.json 未包含 debug_trace 步骤: %s", tracePath)
	}

	now := time.Now().Format(time.RFC3339)
	createdAt := deriveCreatedAt(payload.DebugRunID, tracePath)
	branch := gitOutput(opts.RepoRoot, "branch", "--show-current")
	commitID := gitOutput(opts.RepoRoot, "rev-parse", "--short", "HEAD")

	bundle := session.Bundle{
		Session: session.Session{
			ID:           payload.DebugRunID,
			DebugRunID:   payload.DebugRunID,
			Title:        fmt.Sprintf("Jigsaw求解回放 - %s - %s - %s", safeValue(payload.CityID, "city_unknown"), safeValue(payload.GroupID, "group_unknown"), payload.DebugRunID),
			ProjectName:  filepath.Base(opts.RepoRoot),
			Branch:       branch,
			CommitID:     commitID,
			CityID:       payload.CityID,
			GroupID:      payload.GroupID,
			TaskID:       payload.TaskID,
			ArtifactDir:  payload.DebugArtifactDir,
			ArtifactPath: runDir,
			CreatedAt:    createdAt,
			UpdatedAt:    now,
			ImportedAt:   now,
			Tags:         []string{"jigsaw_solver_debug", "city_jigsaw_solve", "read_only"},
		},
		Details: make(map[string]session.NodeDetail),
		Tables:  make(map[string][]session.TableSnapshot),
		Import: session.ImportRecord{
			SessionID:    payload.DebugRunID,
			SourceType:   "jigsaw_solver_debug",
			ArtifactPath: runDir,
			ImportedAt:   now,
		},
	}

	var missingPreviewCount int
	assetDir := filepath.Join(opts.CacheRoot, payload.DebugRunID, "assets")
	if opts.CacheRoot != "" {
		if err := os.MkdirAll(assetDir, 0o755); err != nil {
			return session.Bundle{}, fmt.Errorf("创建 cache assets 目录失败: %w", err)
		}
	}

	for idx, step := range payload.DebugTrace {
		nodeID := fmt.Sprintf("%s:%s", payload.DebugRunID, step.StepKey)
		normalizedStatus := session.NormalizeStatus(step.Status)
		previewURL, notes, legend, err := cachePreview(runDir, assetDir, payload.DebugRunID, step)
		if err != nil {
			return session.Bundle{}, err
		}
		if previewURL == "" {
			missingPreviewCount++
		}

		node := session.Node{
			ID:             nodeID,
			SessionID:      payload.DebugRunID,
			Name:           safeValue(step.TitleZh, step.StepKey),
			Stage:          step.StepKey,
			CheckpointType: session.CheckpointTypeForStatus(normalizedStatus),
			Status:         normalizedStatus,
			Summary:        safeValue(step.ResultZh, step.ActionSummaryZh),
			ReviewState:    "unchecked",
			RiskLevel:      session.RiskLevelForStatus(normalizedStatus, previewURL == ""),
			DetailRef:      nodeID,
			StepKey:        step.StepKey,
			StepIndex:      idx + 1,
			PreviewImage:   previewURL,
		}
		bundle.Nodes = append(bundle.Nodes, node)
		if idx == 0 {
			bundle.Session.EntryNodeIDs = []string{nodeID}
		}
		if idx > 0 {
			prev := bundle.Nodes[idx-1]
			bundle.Edges = append(bundle.Edges, session.Edge{
				ID:         fmt.Sprintf("%s:%s->%s", payload.DebugRunID, prev.StepKey, step.StepKey),
				SessionID:  payload.DebugRunID,
				FromNodeID: prev.ID,
				ToNodeID:   nodeID,
				EdgeType:   "sequence",
				Label:      "next",
			})
		}

		detail := session.NodeDetail{
			NodeID:           nodeID,
			InputSummary:     step.GoalZh,
			OutputSummary:    step.ResultZh,
			ErrorSummary:     buildErrorSummary(normalizedStatus, step.ResultZh),
			GoalZh:           step.GoalZh,
			ActionSummaryZh:  step.ActionSummaryZh,
			ImplementationZh: step.ImplementationZh,
			ResultZh:         step.ResultZh,
			Evidence:         cloneMap(step.Evidence),
			Legend:           legend,
			SourceRefs:       session.SourceRefsForStep(opts.RepoRoot, step.StepKey),
			DecisionNotes:    notes,
			PreviewImage:     previewURL,
		}
		tables := session.BuildTablesForNode(nodeID, detail.Evidence, detail.Legend)
		tableRefs := make([]string, 0, len(tables))
		for _, table := range tables {
			tableRefs = append(tableRefs, table.ID)
		}
		detail.TableRefs = tableRefs
		bundle.Details[nodeID] = detail
		bundle.Tables[nodeID] = tables

		bundle.Checkpoints = append(bundle.Checkpoints, session.ReviewCheckpoint{
			ID:             fmt.Sprintf("%s:%s:checkpoint", payload.DebugRunID, step.StepKey),
			SessionID:      payload.DebugRunID,
			Title:          safeValue(step.TitleZh, step.StepKey),
			TargetNodeIDs:  []string{nodeID},
			Priority:       session.CheckpointPriorityForStatus(normalizedStatus, previewURL == ""),
			ExpectedResult: safeValue(step.GoalZh, "请确认该步骤是否符合预期"),
			ActualResult:   step.ResultZh,
			ReviewState:    "unchecked",
		})
	}

	bundle.Session.Status = session.SessionStatus(bundle.Nodes)
	bundle.Import.ImportSummary = map[string]any{
		"trace_path":            tracePath,
		"artifact_run_dir":      runDir,
		"debug_step_count":      len(bundle.Nodes),
		"edge_count":            len(bundle.Edges),
		"checkpoint_count":      len(bundle.Checkpoints),
		"missing_preview_count": missingPreviewCount,
	}

	return bundle, nil
}

func resolveRunDir(path string) (string, string, error) {
	if strings.TrimSpace(path) == "" {
		return "", "", errors.New("artifact_path 不能为空")
	}
	absPath, err := filepath.Abs(path)
	if err != nil {
		return "", "", fmt.Errorf("解析 artifact_path 失败: %w", err)
	}
	info, err := os.Stat(absPath)
	if err != nil {
		return "", "", fmt.Errorf("读取 artifact_path 失败: %w", err)
	}
	if !info.IsDir() {
		if strings.EqualFold(filepath.Base(absPath), "trace.json") {
			return filepath.Dir(absPath), absPath, nil
		}
		return "", "", fmt.Errorf("artifact_path 不是目录或 trace.json: %s", absPath)
	}
	tracePath := filepath.Join(absPath, "trace.json")
	if _, err := os.Stat(tracePath); err == nil {
		return absPath, tracePath, nil
	}

	var latestTrace string
	var latestTime time.Time
	walkErr := filepath.WalkDir(absPath, func(candidate string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || !strings.EqualFold(entry.Name(), "trace.json") {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if latestTrace == "" || info.ModTime().After(latestTime) {
			latestTrace = candidate
			latestTime = info.ModTime()
		}
		return nil
	})
	if walkErr != nil {
		return "", "", fmt.Errorf("扫描 artifact_path 失败: %w", walkErr)
	}
	if latestTrace == "" {
		return "", "", fmt.Errorf("在目录中没有找到 trace.json: %s", absPath)
	}
	return filepath.Dir(latestTrace), latestTrace, nil
}

func loadTrace(tracePath string) (tracePayload, error) {
	raw, err := os.ReadFile(tracePath)
	if err != nil {
		return tracePayload{}, fmt.Errorf("读取 trace.json 失败: %w", err)
	}
	var payload tracePayload
	if err := json.Unmarshal(raw, &payload); err != nil {
		return tracePayload{}, fmt.Errorf("解析 trace.json 失败: %w", err)
	}
	return payload, nil
}

func cachePreview(runDir, assetDir, sessionID string, step traceStep) (string, []string, map[string]any, error) {
	notes := make([]string, 0, 2)
	legend := readLegend(runDir, step.StepKey)
	if step.PreviewImage == "" {
		notes = append(notes, "当前步骤未附带 preview_image。")
		return "", notes, legend, nil
	}
	src := filepath.Join(runDir, filepath.Base(step.PreviewImage))
	if _, err := os.Stat(src); err != nil {
		notes = append(notes, "预览图在调试目录中缺失，当前仅展示文字与证据表。")
		return "", notes, legend, nil
	}
	if assetDir == "" {
		return step.PreviewImage, notes, legend, nil
	}
	ext := filepath.Ext(src)
	if ext == "" {
		ext = ".png"
	}
	assetName := step.StepKey + ext
	dest := filepath.Join(assetDir, assetName)
	if err := copyFile(src, dest); err != nil {
		return "", nil, nil, fmt.Errorf("复制预览图失败: %w", err)
	}
	return fmt.Sprintf("/api/sessions/%s/assets/%s", sessionID, assetName), notes, legend, nil
}

func readLegend(runDir, stepKey string) map[string]any {
	if stepKey == "" {
		return nil
	}
	path := filepath.Join(runDir, stepKey+".legend.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var legend map[string]any
	if err := json.Unmarshal(raw, &legend); err != nil {
		return map[string]any{"_legend_parse_error": err.Error()}
	}
	return legend
}

func copyFile(src, dest string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	out, err := os.Create(dest)
	if err != nil {
		return err
	}
	defer out.Close()

	if _, err := io.Copy(out, in); err != nil {
		return err
	}
	return out.Close()
}

func deriveCreatedAt(debugRunID, tracePath string) string {
	if parsed, err := time.Parse("20060102_150405_000", debugRunID); err == nil {
		return parsed.Format(time.RFC3339)
	}
	if info, err := os.Stat(tracePath); err == nil {
		return info.ModTime().Format(time.RFC3339)
	}
	return time.Now().Format(time.RFC3339)
}

func buildErrorSummary(status, result string) string {
	switch session.NormalizeStatus(status) {
	case "failed", "error", "invalid":
		return safeValue(result, "当前步骤未通过。")
	default:
		return ""
	}
}

func gitOutput(repoRoot string, args ...string) string {
	if repoRoot == "" {
		return ""
	}
	cmd := exec.Command("git", append([]string{"-C", repoRoot}, args...)...)
	raw, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(raw))
}

func cloneMap(input map[string]any) map[string]any {
	if len(input) == 0 {
		return nil
	}
	keys := make([]string, 0, len(input))
	for key := range input {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	out := make(map[string]any, len(input))
	for _, key := range keys {
		out[key] = input[key]
	}
	return out
}

func safeValue(primary, fallback string) string {
	if strings.TrimSpace(primary) != "" {
		return primary
	}
	return fallback
}
