package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"

	"code_process_viewer/internal/session"
)

var (
	ErrNotFound      = errors.New("记录不存在")
	ErrSessionExists = errors.New("会话已存在")
)

type Store struct {
	db       *sql.DB
	cacheDir string
}

type CachedSession struct {
	Session     session.Session                    `json:"session"`
	Nodes       []session.Node                     `json:"nodes"`
	Edges       []session.Edge                     `json:"edges"`
	Details     map[string]session.NodeDetail      `json:"details"`
	Tables      map[string][]session.TableSnapshot `json:"tables"`
	Checkpoints []session.ReviewCheckpoint         `json:"checkpoints"`
	Reviews     map[string]session.Review          `json:"reviews"`
	Import      session.ImportRecord               `json:"import"`
}

func NewStore(dbPath, cacheDir string) (*Store, error) {
	if strings.TrimSpace(dbPath) == "" {
		return nil, fmt.Errorf("dbPath 不能为空")
	}
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o755); err != nil {
		return nil, fmt.Errorf("创建数据库目录失败: %w", err)
	}
	if strings.TrimSpace(cacheDir) != "" {
		if err := os.MkdirAll(cacheDir, 0o755); err != nil {
			return nil, fmt.Errorf("创建缓存目录失败: %w", err)
		}
	}

	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, fmt.Errorf("打开 SQLite 失败: %w", err)
	}

	store := &Store{db: db, cacheDir: cacheDir}
	if err := store.init(context.Background()); err != nil {
		_ = db.Close()
		return nil, err
	}
	return store, nil
}

func (s *Store) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

func (s *Store) init(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, `PRAGMA foreign_keys = ON`); err != nil {
		return fmt.Errorf("开启 foreign_keys 失败: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `PRAGMA busy_timeout = 5000`); err != nil {
		return fmt.Errorf("设置 busy_timeout 失败: %w", err)
	}

	statements := []string{
		`CREATE TABLE IF NOT EXISTS sessions (
			id TEXT PRIMARY KEY,
			debug_run_id TEXT NOT NULL,
			title TEXT NOT NULL,
			project_name TEXT NOT NULL,
			branch TEXT,
			commit_id TEXT,
			status TEXT NOT NULL,
			city_id TEXT,
			group_id TEXT,
			task_id TEXT,
			artifact_dir TEXT,
			artifact_path TEXT,
			created_at TEXT NOT NULL,
			updated_at TEXT NOT NULL,
			imported_at TEXT NOT NULL,
			entry_node_ids_json TEXT,
			tags_json TEXT
		)`,
		`CREATE TABLE IF NOT EXISTS nodes (
			id TEXT PRIMARY KEY,
			session_id TEXT NOT NULL,
			name TEXT NOT NULL,
			stage TEXT NOT NULL,
			checkpoint_type TEXT NOT NULL,
			status TEXT NOT NULL,
			summary TEXT NOT NULL,
			duration_ms INTEGER,
			start_at TEXT,
			end_at TEXT,
			review_state TEXT,
			risk_level TEXT,
			detail_ref TEXT,
			step_key TEXT NOT NULL,
			step_index INTEGER NOT NULL,
			preview_image TEXT,
			input_summary TEXT,
			output_summary TEXT,
			error_summary TEXT,
			goal_zh TEXT,
			action_summary_zh TEXT,
			implementation_zh TEXT,
			result_zh TEXT,
			evidence_json TEXT,
			legend_json TEXT,
			table_refs_json TEXT,
			decision_notes_json TEXT,
			FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_nodes_session_step ON nodes(session_id, step_index)`,
		`CREATE TABLE IF NOT EXISTS edges (
			id TEXT PRIMARY KEY,
			session_id TEXT NOT NULL,
			from_node_id TEXT NOT NULL,
			to_node_id TEXT NOT NULL,
			edge_type TEXT NOT NULL,
			label TEXT,
			FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
			FOREIGN KEY(from_node_id) REFERENCES nodes(id) ON DELETE CASCADE,
			FOREIGN KEY(to_node_id) REFERENCES nodes(id) ON DELETE CASCADE
		)`,
		`CREATE TABLE IF NOT EXISTS table_snapshots (
			id TEXT PRIMARY KEY,
			node_id TEXT NOT NULL,
			title TEXT NOT NULL,
			columns_json TEXT NOT NULL,
			rows_json TEXT NOT NULL,
			diff_state TEXT,
			FOREIGN KEY(node_id) REFERENCES nodes(id) ON DELETE CASCADE
		)`,
		`CREATE TABLE IF NOT EXISTS source_refs (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			node_id TEXT NOT NULL,
			path TEXT NOT NULL,
			symbol TEXT,
			line_start INTEGER,
			line_end INTEGER,
			snippet TEXT,
			FOREIGN KEY(node_id) REFERENCES nodes(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_source_refs_node ON source_refs(node_id)`,
		`CREATE TABLE IF NOT EXISTS review_checkpoints (
			id TEXT PRIMARY KEY,
			session_id TEXT NOT NULL,
			title TEXT NOT NULL,
			target_node_ids_json TEXT NOT NULL,
			priority TEXT NOT NULL,
			expected_result TEXT NOT NULL,
			actual_result TEXT,
			review_state TEXT,
			FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
		)`,
		`CREATE TABLE IF NOT EXISTS reviews (
			node_id TEXT PRIMARY KEY,
			session_id TEXT NOT NULL,
			review_state TEXT NOT NULL,
			comment TEXT,
			updated_at TEXT NOT NULL,
			FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
			FOREIGN KEY(node_id) REFERENCES nodes(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_reviews_session ON reviews(session_id)`,
		`CREATE TABLE IF NOT EXISTS imports (
			session_id TEXT PRIMARY KEY,
			source_type TEXT NOT NULL,
			artifact_path TEXT NOT NULL,
			imported_at TEXT NOT NULL,
			import_summary_json TEXT,
			FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
		)`,
	}

	for _, statement := range statements {
		if _, err := s.db.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("初始化 SQLite schema 失败: %w", err)
		}
	}
	return nil
}

func (s *Store) ImportBundle(ctx context.Context, bundle session.Bundle, replaceExisting bool) error {
	if bundle.Session.ID == "" {
		return fmt.Errorf("bundle session_id 不能为空")
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("开启事务失败: %w", err)
	}
	defer tx.Rollback()

	exists, err := s.sessionExistsTx(ctx, tx, bundle.Session.ID)
	if err != nil {
		return err
	}

	preservedReviews := map[string]session.Review{}
	if exists {
		if !replaceExisting {
			return ErrSessionExists
		}
		preservedReviews, err = s.loadReviewsBySessionTx(ctx, tx, bundle.Session.ID)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM sessions WHERE id = ?`, bundle.Session.ID); err != nil {
			return fmt.Errorf("删除旧会话失败: %w", err)
		}
	}

	nodeSet := make(map[string]struct{}, len(bundle.Nodes))
	if err := insertSession(ctx, tx, bundle.Session); err != nil {
		return err
	}
	for _, node := range bundle.Nodes {
		nodeSet[node.ID] = struct{}{}
		if review, ok := preservedReviews[node.ID]; ok {
			node.ReviewState = review.ReviewState
		}
		detail := bundle.Details[node.ID]
		if err := insertNode(ctx, tx, node, detail); err != nil {
			return err
		}
		for _, ref := range detail.SourceRefs {
			if err := insertSourceRef(ctx, tx, node.ID, ref); err != nil {
				return err
			}
		}
		for _, table := range bundle.Tables[node.ID] {
			if err := insertTable(ctx, tx, table); err != nil {
				return err
			}
		}
	}
	for _, edge := range bundle.Edges {
		if err := insertEdge(ctx, tx, edge); err != nil {
			return err
		}
	}
	for _, checkpoint := range bundle.Checkpoints {
		if len(checkpoint.TargetNodeIDs) == 1 {
			if review, ok := preservedReviews[checkpoint.TargetNodeIDs[0]]; ok {
				checkpoint.ReviewState = review.ReviewState
			}
		}
		if err := insertCheckpoint(ctx, tx, checkpoint); err != nil {
			return err
		}
	}
	if err := insertImport(ctx, tx, bundle.Import); err != nil {
		return err
	}
	for nodeID, review := range preservedReviews {
		if _, ok := nodeSet[nodeID]; !ok {
			continue
		}
		if err := insertReview(ctx, tx, review); err != nil {
			return err
		}
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("提交事务失败: %w", err)
	}
	return s.exportSessionCache(ctx, bundle.Session.ID)
}

func (s *Store) ListSessions(ctx context.Context) ([]session.Session, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, debug_run_id, title, project_name, branch, commit_id, status, city_id, group_id,
		       task_id, artifact_dir, artifact_path, created_at, updated_at, imported_at,
		       entry_node_ids_json, tags_json
		FROM sessions
		ORDER BY imported_at DESC, created_at DESC`)
	if err != nil {
		return nil, fmt.Errorf("查询 sessions 失败: %w", err)
	}
	defer rows.Close()

	items := make([]session.Session, 0)
	for rows.Next() {
		item, err := scanSession(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历 sessions 失败: %w", err)
	}
	return items, nil
}

func (s *Store) GetSession(ctx context.Context, sessionID string) (session.SessionView, error) {
	item, err := s.getSessionRow(ctx, sessionID)
	if err != nil {
		return session.SessionView{}, err
	}
	checkpoints, err := s.getCheckpoints(ctx, sessionID)
	if err != nil {
		return session.SessionView{}, err
	}
	importRecord, err := s.getImport(ctx, sessionID)
	if err != nil {
		return session.SessionView{}, err
	}
	return session.SessionView{
		Session:     item,
		Checkpoints: checkpoints,
		Import:      importRecord,
	}, nil
}

func (s *Store) GetGraph(ctx context.Context, sessionID string) (session.GraphView, error) {
	item, err := s.getSessionRow(ctx, sessionID)
	if err != nil {
		return session.GraphView{}, err
	}

	rows, err := s.db.QueryContext(ctx, `
		SELECT id, session_id, name, stage, checkpoint_type, status, summary, duration_ms, start_at, end_at,
		       review_state, risk_level, detail_ref, step_key, step_index, preview_image
		FROM nodes
		WHERE session_id = ?
		ORDER BY step_index ASC`, sessionID)
	if err != nil {
		return session.GraphView{}, fmt.Errorf("查询节点失败: %w", err)
	}
	defer rows.Close()

	nodes := make([]session.Node, 0)
	for rows.Next() {
		node, err := scanNode(rows)
		if err != nil {
			return session.GraphView{}, err
		}
		nodes = append(nodes, node)
	}
	if err := rows.Err(); err != nil {
		return session.GraphView{}, fmt.Errorf("遍历节点失败: %w", err)
	}

	edgeRows, err := s.db.QueryContext(ctx, `
		SELECT id, session_id, from_node_id, to_node_id, edge_type, label
		FROM edges
		WHERE session_id = ?
		ORDER BY id ASC`, sessionID)
	if err != nil {
		return session.GraphView{}, fmt.Errorf("查询边失败: %w", err)
	}
	defer edgeRows.Close()

	edges := make([]session.Edge, 0)
	for edgeRows.Next() {
		var edge session.Edge
		if err := edgeRows.Scan(&edge.ID, &edge.SessionID, &edge.FromNodeID, &edge.ToNodeID, &edge.EdgeType, &edge.Label); err != nil {
			return session.GraphView{}, fmt.Errorf("读取边失败: %w", err)
		}
		edges = append(edges, edge)
	}
	if err := edgeRows.Err(); err != nil {
		return session.GraphView{}, fmt.Errorf("遍历边失败: %w", err)
	}

	return session.GraphView{
		Session: item,
		Nodes:   nodes,
		Edges:   edges,
	}, nil
}

func (s *Store) GetNode(ctx context.Context, nodeID string) (session.NodeView, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, session_id, name, stage, checkpoint_type, status, summary, duration_ms, start_at, end_at,
		       review_state, risk_level, detail_ref, step_key, step_index, preview_image,
		       input_summary, output_summary, error_summary, goal_zh, action_summary_zh, implementation_zh,
		       result_zh, evidence_json, legend_json, table_refs_json, decision_notes_json
		FROM nodes
		WHERE id = ?`, nodeID)

	view, sessionID, err := scanNodeView(row)
	if err != nil {
		return session.NodeView{}, err
	}
	view.Detail.SourceRefs, err = s.getSourceRefs(ctx, nodeID)
	if err != nil {
		return session.NodeView{}, err
	}
	review, err := s.getReview(ctx, nodeID, sessionID)
	if err != nil && !errors.Is(err, ErrNotFound) {
		return session.NodeView{}, err
	}
	if err == nil {
		view.Review = &review
	}
	return view, nil
}

func (s *Store) GetNodeTables(ctx context.Context, nodeID string) ([]session.TableSnapshot, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, node_id, title, columns_json, rows_json, diff_state
		FROM table_snapshots
		WHERE node_id = ?
		ORDER BY id ASC`, nodeID)
	if err != nil {
		return nil, fmt.Errorf("查询表格失败: %w", err)
	}
	defer rows.Close()

	items := make([]session.TableSnapshot, 0)
	for rows.Next() {
		var item session.TableSnapshot
		var columnsJSON string
		var rowsJSON string
		if err := rows.Scan(&item.ID, &item.NodeID, &item.Title, &columnsJSON, &rowsJSON, &item.DiffState); err != nil {
			return nil, fmt.Errorf("读取表格失败: %w", err)
		}
		if err := decodeJSON(columnsJSON, &item.Columns); err != nil {
			return nil, err
		}
		if err := decodeJSON(rowsJSON, &item.Rows); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历表格失败: %w", err)
	}
	return items, nil
}

func (s *Store) SaveReview(ctx context.Context, nodeID, reviewState, comment string) (session.Review, error) {
	normalized := strings.TrimSpace(reviewState)
	if normalized == "" {
		return session.Review{}, fmt.Errorf("review_state 不能为空")
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return session.Review{}, fmt.Errorf("开启事务失败: %w", err)
	}
	defer tx.Rollback()

	var sessionID string
	if err := tx.QueryRowContext(ctx, `SELECT session_id FROM nodes WHERE id = ?`, nodeID).Scan(&sessionID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return session.Review{}, ErrNotFound
		}
		return session.Review{}, fmt.Errorf("查询节点所属会话失败: %w", err)
	}

	review := session.Review{
		NodeID:      nodeID,
		SessionID:   sessionID,
		ReviewState: normalized,
		Comment:     strings.TrimSpace(comment),
		UpdatedAt:   time.Now().Format(time.RFC3339),
	}

	if _, err := tx.ExecContext(ctx, `
		INSERT INTO reviews(node_id, session_id, review_state, comment, updated_at)
		VALUES (?, ?, ?, ?, ?)
		ON CONFLICT(node_id) DO UPDATE SET
			session_id = excluded.session_id,
			review_state = excluded.review_state,
			comment = excluded.comment,
			updated_at = excluded.updated_at`,
		review.NodeID, review.SessionID, review.ReviewState, review.Comment, review.UpdatedAt); err != nil {
		return session.Review{}, fmt.Errorf("写入 review 失败: %w", err)
	}

	if _, err := tx.ExecContext(ctx, `UPDATE nodes SET review_state = ? WHERE id = ?`, review.ReviewState, nodeID); err != nil {
		return session.Review{}, fmt.Errorf("更新节点审查状态失败: %w", err)
	}

	needle := fmt.Sprintf("\"%s\"", nodeID)
	if _, err := tx.ExecContext(ctx, `
		UPDATE review_checkpoints
		SET review_state = ?
		WHERE session_id = ? AND instr(target_node_ids_json, ?) > 0`,
		review.ReviewState, sessionID, needle); err != nil {
		return session.Review{}, fmt.Errorf("更新检查点审查状态失败: %w", err)
	}

	if _, err := tx.ExecContext(ctx, `UPDATE sessions SET updated_at = ? WHERE id = ?`, review.UpdatedAt, sessionID); err != nil {
		return session.Review{}, fmt.Errorf("更新会话时间失败: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return session.Review{}, fmt.Errorf("提交 review 事务失败: %w", err)
	}
	if err := s.exportSessionCache(ctx, sessionID); err != nil {
		return session.Review{}, err
	}
	return review, nil
}

func (s *Store) GetReport(ctx context.Context, sessionID string) (session.ReportView, error) {
	graph, err := s.GetGraph(ctx, sessionID)
	if err != nil {
		return session.ReportView{}, err
	}
	checkpoints, err := s.getCheckpoints(ctx, sessionID)
	if err != nil {
		return session.ReportView{}, err
	}
	importRecord, err := s.getImport(ctx, sessionID)
	if err != nil {
		return session.ReportView{}, err
	}
	reviews, err := s.loadReviewsBySession(ctx, sessionID)
	if err != nil {
		return session.ReportView{}, err
	}

	details := make(map[string]session.NodeDetail, len(graph.Nodes))
	for _, node := range graph.Nodes {
		nodeView, err := s.GetNode(ctx, node.ID)
		if err != nil {
			return session.ReportView{}, err
		}
		details[node.ID] = nodeView.Detail
	}

	return session.ReportView{
		Session:     graph.Session,
		Nodes:       graph.Nodes,
		Details:     details,
		Checkpoints: checkpoints,
		Reviews:     reviews,
		Import:      importRecord,
	}, nil
}

func (s *Store) GetAssetPath(sessionID, assetName string) (string, error) {
	if strings.TrimSpace(s.cacheDir) == "" {
		return "", ErrNotFound
	}
	cleanName := filepath.Base(strings.TrimSpace(assetName))
	if cleanName == "" || cleanName != assetName {
		return "", ErrNotFound
	}
	fullPath := filepath.Join(s.cacheDir, sessionID, "assets", cleanName)
	if _, err := os.Stat(fullPath); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", ErrNotFound
		}
		return "", fmt.Errorf("读取预览图失败: %w", err)
	}
	return fullPath, nil
}

func (s *Store) exportSessionCache(ctx context.Context, sessionID string) error {
	if strings.TrimSpace(s.cacheDir) == "" {
		return nil
	}
	report, err := s.GetReport(ctx, sessionID)
	if err != nil {
		return err
	}
	graph, err := s.GetGraph(ctx, sessionID)
	if err != nil {
		return err
	}

	tables := make(map[string][]session.TableSnapshot, len(graph.Nodes))
	for _, node := range graph.Nodes {
		items, err := s.GetNodeTables(ctx, node.ID)
		if err != nil {
			return err
		}
		tables[node.ID] = items
	}

	payload := CachedSession{
		Session:     report.Session,
		Nodes:       graph.Nodes,
		Edges:       graph.Edges,
		Details:     report.Details,
		Tables:      tables,
		Checkpoints: report.Checkpoints,
		Reviews:     report.Reviews,
		Import:      report.Import,
	}

	raw, err := json.MarshalIndent(payload, "", "  ")
	if err != nil {
		return fmt.Errorf("序列化 session cache 失败: %w", err)
	}
	path := filepath.Join(s.cacheDir, sessionID+".json")
	if err := os.WriteFile(path, raw, 0o644); err != nil {
		return fmt.Errorf("写入 session cache 失败: %w", err)
	}
	return nil
}

func (s *Store) getSessionRow(ctx context.Context, sessionID string) (session.Session, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, debug_run_id, title, project_name, branch, commit_id, status, city_id, group_id,
		       task_id, artifact_dir, artifact_path, created_at, updated_at, imported_at,
		       entry_node_ids_json, tags_json
		FROM sessions
		WHERE id = ?`, sessionID)
	item, err := scanSession(row)
	if err != nil {
		return session.Session{}, err
	}
	return item, nil
}

func (s *Store) getCheckpoints(ctx context.Context, sessionID string) ([]session.ReviewCheckpoint, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, session_id, title, target_node_ids_json, priority, expected_result, actual_result, review_state
		FROM review_checkpoints
		WHERE session_id = ?
		ORDER BY id ASC`, sessionID)
	if err != nil {
		return nil, fmt.Errorf("查询检查点失败: %w", err)
	}
	defer rows.Close()

	items := make([]session.ReviewCheckpoint, 0)
	for rows.Next() {
		var item session.ReviewCheckpoint
		var targetIDsJSON string
		if err := rows.Scan(&item.ID, &item.SessionID, &item.Title, &targetIDsJSON, &item.Priority, &item.ExpectedResult, &item.ActualResult, &item.ReviewState); err != nil {
			return nil, fmt.Errorf("读取检查点失败: %w", err)
		}
		if err := decodeJSON(targetIDsJSON, &item.TargetNodeIDs); err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历检查点失败: %w", err)
	}
	return items, nil
}

func (s *Store) getImport(ctx context.Context, sessionID string) (session.ImportRecord, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT session_id, source_type, artifact_path, imported_at, import_summary_json
		FROM imports
		WHERE session_id = ?`, sessionID)

	var item session.ImportRecord
	var summaryJSON string
	if err := row.Scan(&item.SessionID, &item.SourceType, &item.ArtifactPath, &item.ImportedAt, &summaryJSON); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return session.ImportRecord{}, ErrNotFound
		}
		return session.ImportRecord{}, fmt.Errorf("读取导入记录失败: %w", err)
	}
	if err := decodeJSON(summaryJSON, &item.ImportSummary); err != nil {
		return session.ImportRecord{}, err
	}
	return item, nil
}

func (s *Store) getSourceRefs(ctx context.Context, nodeID string) ([]session.SourceRef, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT path, symbol, line_start, line_end, snippet
		FROM source_refs
		WHERE node_id = ?
		ORDER BY id ASC`, nodeID)
	if err != nil {
		return nil, fmt.Errorf("查询源码引用失败: %w", err)
	}
	defer rows.Close()

	items := make([]session.SourceRef, 0)
	for rows.Next() {
		var item session.SourceRef
		if err := rows.Scan(&item.Path, &item.Symbol, &item.LineStart, &item.LineEnd, &item.Snippet); err != nil {
			return nil, fmt.Errorf("读取源码引用失败: %w", err)
		}
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历源码引用失败: %w", err)
	}
	return items, nil
}

func (s *Store) getReview(ctx context.Context, nodeID, sessionID string) (session.Review, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT node_id, session_id, review_state, comment, updated_at
		FROM reviews
		WHERE node_id = ? AND session_id = ?`, nodeID, sessionID)

	var item session.Review
	if err := row.Scan(&item.NodeID, &item.SessionID, &item.ReviewState, &item.Comment, &item.UpdatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return session.Review{}, ErrNotFound
		}
		return session.Review{}, fmt.Errorf("读取 review 失败: %w", err)
	}
	return item, nil
}

func (s *Store) loadReviewsBySession(ctx context.Context, sessionID string) (map[string]session.Review, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT node_id, session_id, review_state, comment, updated_at
		FROM reviews
		WHERE session_id = ?`, sessionID)
	if err != nil {
		return nil, fmt.Errorf("查询 reviews 失败: %w", err)
	}
	defer rows.Close()

	return collectReviews(rows)
}

func (s *Store) loadReviewsBySessionTx(ctx context.Context, tx *sql.Tx, sessionID string) (map[string]session.Review, error) {
	rows, err := tx.QueryContext(ctx, `
		SELECT node_id, session_id, review_state, comment, updated_at
		FROM reviews
		WHERE session_id = ?`, sessionID)
	if err != nil {
		return nil, fmt.Errorf("查询旧 reviews 失败: %w", err)
	}
	defer rows.Close()

	return collectReviews(rows)
}

func collectReviews(rows *sql.Rows) (map[string]session.Review, error) {
	items := map[string]session.Review{}
	for rows.Next() {
		var item session.Review
		if err := rows.Scan(&item.NodeID, &item.SessionID, &item.ReviewState, &item.Comment, &item.UpdatedAt); err != nil {
			return nil, fmt.Errorf("读取 review 失败: %w", err)
		}
		items[item.NodeID] = item
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历 reviews 失败: %w", err)
	}
	return items, nil
}

func (s *Store) sessionExistsTx(ctx context.Context, tx *sql.Tx, sessionID string) (bool, error) {
	var value string
	err := tx.QueryRowContext(ctx, `SELECT id FROM sessions WHERE id = ?`, sessionID).Scan(&value)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return false, fmt.Errorf("检查 session 是否存在失败: %w", err)
}

func insertSession(ctx context.Context, tx *sql.Tx, item session.Session) error {
	entryJSON, err := encodeJSON(item.EntryNodeIDs)
	if err != nil {
		return err
	}
	tagsJSON, err := encodeJSON(item.Tags)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO sessions(
			id, debug_run_id, title, project_name, branch, commit_id, status, city_id, group_id, task_id,
			artifact_dir, artifact_path, created_at, updated_at, imported_at, entry_node_ids_json, tags_json
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		item.ID, item.DebugRunID, item.Title, item.ProjectName, item.Branch, item.CommitID, item.Status,
		item.CityID, item.GroupID, item.TaskID, item.ArtifactDir, item.ArtifactPath, item.CreatedAt,
		item.UpdatedAt, item.ImportedAt, entryJSON, tagsJSON); err != nil {
		return fmt.Errorf("写入 session 失败: %w", err)
	}
	return nil
}

func insertNode(ctx context.Context, tx *sql.Tx, item session.Node, detail session.NodeDetail) error {
	evidenceJSON, err := encodeJSON(detail.Evidence)
	if err != nil {
		return err
	}
	legendJSON, err := encodeJSON(detail.Legend)
	if err != nil {
		return err
	}
	tableRefsJSON, err := encodeJSON(detail.TableRefs)
	if err != nil {
		return err
	}
	decisionNotesJSON, err := encodeJSON(detail.DecisionNotes)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO nodes(
			id, session_id, name, stage, checkpoint_type, status, summary, duration_ms, start_at, end_at,
			review_state, risk_level, detail_ref, step_key, step_index, preview_image,
			input_summary, output_summary, error_summary, goal_zh, action_summary_zh, implementation_zh, result_zh,
			evidence_json, legend_json, table_refs_json, decision_notes_json
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		item.ID, item.SessionID, item.Name, item.Stage, item.CheckpointType, item.Status, item.Summary,
		item.DurationMS, item.StartAt, item.EndAt, item.ReviewState, item.RiskLevel, item.DetailRef,
		item.StepKey, item.StepIndex, item.PreviewImage, detail.InputSummary, detail.OutputSummary,
		detail.ErrorSummary, detail.GoalZh, detail.ActionSummaryZh, detail.ImplementationZh, detail.ResultZh,
		evidenceJSON, legendJSON, tableRefsJSON, decisionNotesJSON); err != nil {
		return fmt.Errorf("写入节点失败: %w", err)
	}
	return nil
}

func insertEdge(ctx context.Context, tx *sql.Tx, item session.Edge) error {
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO edges(id, session_id, from_node_id, to_node_id, edge_type, label)
		VALUES (?, ?, ?, ?, ?, ?)`,
		item.ID, item.SessionID, item.FromNodeID, item.ToNodeID, item.EdgeType, item.Label); err != nil {
		return fmt.Errorf("写入边失败: %w", err)
	}
	return nil
}

func insertTable(ctx context.Context, tx *sql.Tx, item session.TableSnapshot) error {
	columnsJSON, err := encodeJSON(item.Columns)
	if err != nil {
		return err
	}
	rowsJSON, err := encodeJSON(item.Rows)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO table_snapshots(id, node_id, title, columns_json, rows_json, diff_state)
		VALUES (?, ?, ?, ?, ?, ?)`,
		item.ID, item.NodeID, item.Title, columnsJSON, rowsJSON, item.DiffState); err != nil {
		return fmt.Errorf("写入表格失败: %w", err)
	}
	return nil
}

func insertSourceRef(ctx context.Context, tx *sql.Tx, nodeID string, item session.SourceRef) error {
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO source_refs(node_id, path, symbol, line_start, line_end, snippet)
		VALUES (?, ?, ?, ?, ?, ?)`,
		nodeID, item.Path, item.Symbol, item.LineStart, item.LineEnd, item.Snippet); err != nil {
		return fmt.Errorf("写入源码引用失败: %w", err)
	}
	return nil
}

func insertCheckpoint(ctx context.Context, tx *sql.Tx, item session.ReviewCheckpoint) error {
	targetJSON, err := encodeJSON(item.TargetNodeIDs)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO review_checkpoints(id, session_id, title, target_node_ids_json, priority, expected_result, actual_result, review_state)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		item.ID, item.SessionID, item.Title, targetJSON, item.Priority, item.ExpectedResult, item.ActualResult, item.ReviewState); err != nil {
		return fmt.Errorf("写入检查点失败: %w", err)
	}
	return nil
}

func insertReview(ctx context.Context, tx *sql.Tx, item session.Review) error {
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO reviews(node_id, session_id, review_state, comment, updated_at)
		VALUES (?, ?, ?, ?, ?)`,
		item.NodeID, item.SessionID, item.ReviewState, item.Comment, item.UpdatedAt); err != nil {
		return fmt.Errorf("写入 review 失败: %w", err)
	}
	return nil
}

func insertImport(ctx context.Context, tx *sql.Tx, item session.ImportRecord) error {
	summaryJSON, err := encodeJSON(item.ImportSummary)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO imports(session_id, source_type, artifact_path, imported_at, import_summary_json)
		VALUES (?, ?, ?, ?, ?)`,
		item.SessionID, item.SourceType, item.ArtifactPath, item.ImportedAt, summaryJSON); err != nil {
		return fmt.Errorf("写入导入记录失败: %w", err)
	}
	return nil
}

func scanSession(scanner interface {
	Scan(dest ...any) error
}) (session.Session, error) {
	var item session.Session
	var entryJSON string
	var tagsJSON string
	if err := scanner.Scan(
		&item.ID, &item.DebugRunID, &item.Title, &item.ProjectName, &item.Branch, &item.CommitID, &item.Status,
		&item.CityID, &item.GroupID, &item.TaskID, &item.ArtifactDir, &item.ArtifactPath, &item.CreatedAt,
		&item.UpdatedAt, &item.ImportedAt, &entryJSON, &tagsJSON,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return session.Session{}, ErrNotFound
		}
		return session.Session{}, fmt.Errorf("读取 session 失败: %w", err)
	}
	if err := decodeJSON(entryJSON, &item.EntryNodeIDs); err != nil {
		return session.Session{}, err
	}
	if err := decodeJSON(tagsJSON, &item.Tags); err != nil {
		return session.Session{}, err
	}
	return item, nil
}

func scanNode(scanner interface {
	Scan(dest ...any) error
}) (session.Node, error) {
	var item session.Node
	var duration sql.NullInt64
	if err := scanner.Scan(
		&item.ID, &item.SessionID, &item.Name, &item.Stage, &item.CheckpointType, &item.Status,
		&item.Summary, &duration, &item.StartAt, &item.EndAt, &item.ReviewState, &item.RiskLevel,
		&item.DetailRef, &item.StepKey, &item.StepIndex, &item.PreviewImage,
	); err != nil {
		return session.Node{}, fmt.Errorf("读取节点失败: %w", err)
	}
	if duration.Valid {
		item.DurationMS = &duration.Int64
	}
	return item, nil
}

func scanNodeView(scanner interface {
	Scan(dest ...any) error
}) (session.NodeView, string, error) {
	var view session.NodeView
	var duration sql.NullInt64
	var evidenceJSON string
	var legendJSON string
	var tableRefsJSON string
	var decisionNotesJSON string
	if err := scanner.Scan(
		&view.Node.ID, &view.Node.SessionID, &view.Node.Name, &view.Node.Stage, &view.Node.CheckpointType,
		&view.Node.Status, &view.Node.Summary, &duration, &view.Node.StartAt, &view.Node.EndAt,
		&view.Node.ReviewState, &view.Node.RiskLevel, &view.Node.DetailRef, &view.Node.StepKey,
		&view.Node.StepIndex, &view.Node.PreviewImage, &view.Detail.InputSummary, &view.Detail.OutputSummary,
		&view.Detail.ErrorSummary, &view.Detail.GoalZh, &view.Detail.ActionSummaryZh,
		&view.Detail.ImplementationZh, &view.Detail.ResultZh, &evidenceJSON, &legendJSON,
		&tableRefsJSON, &decisionNotesJSON,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return session.NodeView{}, "", ErrNotFound
		}
		return session.NodeView{}, "", fmt.Errorf("读取节点详情失败: %w", err)
	}
	if duration.Valid {
		view.Node.DurationMS = &duration.Int64
	}
	view.Detail.NodeID = view.Node.ID
	view.Detail.PreviewImage = view.Node.PreviewImage
	if err := decodeJSON(evidenceJSON, &view.Detail.Evidence); err != nil {
		return session.NodeView{}, "", err
	}
	if err := decodeJSON(legendJSON, &view.Detail.Legend); err != nil {
		return session.NodeView{}, "", err
	}
	if err := decodeJSON(tableRefsJSON, &view.Detail.TableRefs); err != nil {
		return session.NodeView{}, "", err
	}
	if err := decodeJSON(decisionNotesJSON, &view.Detail.DecisionNotes); err != nil {
		return session.NodeView{}, "", err
	}
	return view, view.Node.SessionID, nil
}

func encodeJSON(value any) (string, error) {
	if value == nil {
		return "null", nil
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return "", fmt.Errorf("序列化 JSON 失败: %w", err)
	}
	return string(raw), nil
}

func decodeJSON(raw string, target any) error {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" || trimmed == "null" {
		return nil
	}
	if err := json.Unmarshal([]byte(trimmed), target); err != nil {
		return fmt.Errorf("解析 JSON 失败: %w", err)
	}
	return nil
}
