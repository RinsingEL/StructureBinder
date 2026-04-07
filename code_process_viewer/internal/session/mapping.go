package session

import (
	"fmt"
	"path/filepath"
	"slices"
	"sort"
	"strings"
)

type sourceTemplate struct {
	path   string
	symbol string
	start  int
	end    int
}

var stepSourceTemplates = map[string][]sourceTemplate{
	"01_request_context": {
		{path: "country_designer_mcp/src/city/handlers.ts", symbol: "city_jigsaw_solve", start: 118, end: 126},
		{path: "country_designer_mcp/src/city/tools.ts", symbol: "city_jigsaw_solve", start: 100, end: 121},
		{path: "src/main/java/com/user/terra_script/server/mcp/CityController.java", symbol: "handleCityJigsawSolve", start: 1311, end: 1647},
	},
	"02_runtime_jigsaws": {
		{path: "src/main/java/com/user/terra_script/server/mcp/CityController.java", symbol: "handleCityJigsawSolve", start: 1408, end: 1415},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityVanillaJigsawAdapterService.java", symbol: "solve", start: 56, end: 234},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityJigsawSolverPreviewExporter.java", symbol: "export", start: 22, end: 58},
	},
	"03_parent_connector_resolution": {
		{path: "src/main/java/com/user/terra_script/server/mcp/CityController.java", symbol: "buildParentResolutionEvidence", start: 2786, end: 2820},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityVanillaJigsawAdapterService.java", symbol: "solve", start: 128, end: 189},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityJigsawSolverPreviewExporter.java", symbol: "buildResolutionMarkers", start: 196, end: 221},
	},
	"04_vanilla_piece_result": {
		{path: "src/main/java/com/user/terra_script/server/mcp/CityController.java", symbol: "buildVanillaPieceEvidence", start: 2824, end: 2878},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityVanillaJigsawAdapterService.java", symbol: "solve", start: 234, end: 615},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityJigsawSolverPreviewExporter.java", symbol: "buildVanillaResultRects", start: 223, end: 319},
	},
	"05_validation_result": {
		{path: "src/main/java/com/user/terra_script/server/mcp/CityController.java", symbol: "buildValidationEvidence", start: 2880, end: 2910},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityVanillaJigsawAdapterService.java", symbol: "solve", start: 863, end: 921},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityJigsawSolverPreviewExporter.java", symbol: "buildValidationRects", start: 296, end: 319},
	},
	"06_apply_result": {
		{path: "src/main/java/com/user/terra_script/server/mcp/CityController.java", symbol: "buildApplyFailureEvidence", start: 2912, end: 2932},
		{path: "src/main/java/com/user/terra_script/world/city/execution/SolvedPlacementExecutionService.java", symbol: "execute", start: 23, end: 139},
		{path: "src/main/java/com/user/terra_script/world/city/stage/c8/CityJigsawSolverPreviewExporter.java", symbol: "buildApplyRects", start: 321, end: 346},
	},
}

func SourceRefsForStep(repoRoot, stepKey string) []SourceRef {
	templates := stepSourceTemplates[stepKey]
	if len(templates) == 0 {
		return nil
	}
	out := make([]SourceRef, 0, len(templates))
	for _, tpl := range templates {
		out = append(out, SourceRef{
			Path:      filepath.Join(repoRoot, filepath.FromSlash(tpl.path)),
			Symbol:    tpl.symbol,
			LineStart: tpl.start,
			LineEnd:   tpl.end,
		})
	}
	return out
}

func CheckpointTypeForStatus(status string) string {
	switch NormalizeStatus(status) {
	case "invalid", "warning", "failed", "error":
		return "issue"
	default:
		return "verify"
	}
}

func RiskLevelForStatus(status string, missingPreview bool) string {
	switch NormalizeStatus(status) {
	case "failed", "error", "invalid":
		return "high"
	case "warning":
		return "medium"
	default:
		if missingPreview {
			return "medium"
		}
		return "low"
	}
}

func CheckpointPriorityForStatus(status string, missingPreview bool) string {
	switch NormalizeStatus(status) {
	case "failed", "error", "invalid":
		return "high"
	case "warning":
		return "high"
	default:
		if missingPreview {
			return "high"
		}
		return "medium"
	}
}

func NormalizeStatus(status string) string {
	normalized := strings.TrimSpace(strings.ToLower(status))
	if normalized == "" {
		return "ok"
	}
	return normalized
}

func SessionStatus(nodes []Node) string {
	status := "success"
	for _, node := range nodes {
		switch NormalizeStatus(node.Status) {
		case "failed", "error", "invalid":
			return "failed"
		case "warning":
			status = "warning"
		}
	}
	return status
}

func BuildTablesForNode(nodeID string, evidence map[string]any, legend map[string]any) []TableSnapshot {
	tables := make([]TableSnapshot, 0, 2)
	if len(evidence) > 0 {
		tables = append(tables, buildKVTable(nodeID, nodeID+":evidence", "证据明细", evidence))
	}
	if len(legend) > 0 {
		tables = append(tables, buildKVTable(nodeID, nodeID+":legend", "预览图例", legend))
	}
	return tables
}

func buildKVTable(nodeID, tableID, title string, data map[string]any) TableSnapshot {
	rows := flatten(data)
	return TableSnapshot{
		ID:     tableID,
		NodeID: nodeID,
		Title:  title,
		Columns: []TableColumn{
			{Key: "field", Label: "字段"},
			{Key: "value", Label: "值"},
		},
		Rows: rows,
	}
}

func flatten(data map[string]any) []map[string]any {
	rows := make([]map[string]any, 0)
	keys := make([]string, 0, len(data))
	for key := range data {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		flattenValue(key, data[key], &rows)
	}
	return rows
}

func flattenValue(prefix string, value any, rows *[]map[string]any) {
	switch typed := value.(type) {
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			next := key
			if prefix != "" {
				next = prefix + "." + key
			}
			flattenValue(next, typed[key], rows)
		}
	case []any:
		if len(typed) == 0 {
			*rows = append(*rows, map[string]any{"field": prefix, "value": "[]"})
			return
		}
		for idx, item := range typed {
			flattenValue(fmt.Sprintf("%s[%d]", prefix, idx), item, rows)
		}
	default:
		*rows = append(*rows, map[string]any{
			"field": prefix,
			"value": stringifyValue(typed),
		})
	}
}

func stringifyValue(value any) string {
	switch typed := value.(type) {
	case nil:
		return "null"
	case string:
		return typed
	case bool:
		if typed {
			return "true"
		}
		return "false"
	case float64:
		return fmt.Sprintf("%v", typed)
	case float32:
		return fmt.Sprintf("%v", typed)
	case int, int8, int16, int32, int64:
		return fmt.Sprintf("%v", typed)
	case uint, uint8, uint16, uint32, uint64:
		return fmt.Sprintf("%v", typed)
	default:
		return fmt.Sprintf("%v", typed)
	}
}

func BuildMarkdownReport(view ReportView) string {
	var builder strings.Builder
	builder.WriteString("# Jigsaw 求解器回放报告\n\n")
	builder.WriteString("## 会话信息\n\n")
	builder.WriteString(fmt.Sprintf("- 标题：%s\n", view.Session.Title))
	builder.WriteString(fmt.Sprintf("- 会话 ID：`%s`\n", view.Session.ID))
	builder.WriteString(fmt.Sprintf("- 调试批次：`%s`\n", view.Session.DebugRunID))
	builder.WriteString(fmt.Sprintf("- 城市 / 组：`%s` / `%s`\n", view.Session.CityID, view.Session.GroupID))
	builder.WriteString(fmt.Sprintf("- 分支 / 提交：`%s` / `%s`\n", safe(view.Session.Branch), safe(view.Session.CommitID)))
	builder.WriteString(fmt.Sprintf("- 状态：`%s`\n", view.Session.Status))
	builder.WriteString(fmt.Sprintf("- 产物目录：`%s`\n\n", view.Session.ArtifactPath))

	builder.WriteString("## 步骤概览\n\n")
	for _, node := range view.Nodes {
		builder.WriteString(fmt.Sprintf("- `%02d` `%s` `%s`：%s\n", node.StepIndex, node.StepKey, node.Status, node.Name))
	}
	builder.WriteString("\n## 检查点\n\n")
	for _, checkpoint := range view.Checkpoints {
		builder.WriteString(fmt.Sprintf("- `%s` [%s]：%s\n", checkpoint.Priority, checkpoint.ReviewState, checkpoint.Title))
	}
	builder.WriteString("\n## 详细步骤\n\n")
	for _, node := range view.Nodes {
		detail, ok := view.Details[node.ID]
		if !ok {
			continue
		}
		builder.WriteString(fmt.Sprintf("### %02d. %s\n\n", node.StepIndex, node.Name))
		builder.WriteString(fmt.Sprintf("- 步骤键：`%s`\n", node.StepKey))
		builder.WriteString(fmt.Sprintf("- 状态：`%s`\n", node.Status))
		builder.WriteString(fmt.Sprintf("- 风险：`%s`\n", node.RiskLevel))
		if review, ok := view.Reviews[node.ID]; ok {
			builder.WriteString(fmt.Sprintf("- 审查结论：`%s`\n", review.ReviewState))
			if review.Comment != "" {
				builder.WriteString(fmt.Sprintf("- 审查备注：%s\n", review.Comment))
			}
		}
		if detail.GoalZh != "" {
			builder.WriteString(fmt.Sprintf("- 目标：%s\n", detail.GoalZh))
		}
		if detail.ActionSummaryZh != "" {
			builder.WriteString(fmt.Sprintf("- 动作摘要：%s\n", detail.ActionSummaryZh))
		}
		if detail.ResultZh != "" {
			builder.WriteString(fmt.Sprintf("- 结果：%s\n", detail.ResultZh))
		}
		if len(detail.SourceRefs) > 0 {
			builder.WriteString("- 源码入口：\n")
			refs := slices.Clone(detail.SourceRefs)
			sort.Slice(refs, func(i, j int) bool { return refs[i].Path < refs[j].Path })
			for _, ref := range refs {
				builder.WriteString(fmt.Sprintf("  - `%s`", ref.Path))
				if ref.LineStart > 0 {
					builder.WriteString(fmt.Sprintf(":%d", ref.LineStart))
				}
				builder.WriteString("\n")
			}
		}
		builder.WriteString("\n")
	}

	return builder.String()
}

func safe(value string) string {
	if value == "" {
		return "-"
	}
	return value
}
