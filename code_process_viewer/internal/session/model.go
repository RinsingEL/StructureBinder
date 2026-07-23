package session

type Session struct {
	ID           string   `json:"session_id"`
	DebugRunID   string   `json:"debug_run_id"`
	Title        string   `json:"title"`
	ProjectName  string   `json:"project_name"`
	Branch       string   `json:"branch,omitempty"`
	CommitID     string   `json:"commit_id,omitempty"`
	Status       string   `json:"status"`
	CityID       string   `json:"city_id,omitempty"`
	GroupID      string   `json:"group_id,omitempty"`
	TaskID       string   `json:"task_id,omitempty"`
	ArtifactDir  string   `json:"artifact_dir,omitempty"`
	ArtifactPath string   `json:"artifact_path,omitempty"`
	CreatedAt    string   `json:"created_at"`
	UpdatedAt    string   `json:"updated_at"`
	ImportedAt   string   `json:"imported_at"`
	EntryNodeIDs []string `json:"entry_node_ids,omitempty"`
	Tags         []string `json:"tags,omitempty"`
}

type Node struct {
	ID             string `json:"node_id"`
	SessionID      string `json:"session_id"`
	Name           string `json:"name"`
	Stage          string `json:"stage"`
	CheckpointType string `json:"checkpoint_type"`
	Status         string `json:"status"`
	Summary        string `json:"summary"`
	DurationMS     *int64 `json:"duration_ms,omitempty"`
	StartAt        string `json:"start_at,omitempty"`
	EndAt          string `json:"end_at,omitempty"`
	ReviewState    string `json:"review_state,omitempty"`
	RiskLevel      string `json:"risk_level,omitempty"`
	DetailRef      string `json:"detail_ref,omitempty"`
	StepKey        string `json:"step_key"`
	StepIndex      int    `json:"step_index"`
	PreviewImage   string `json:"preview_image,omitempty"`
}

type Edge struct {
	ID         string `json:"edge_id"`
	SessionID  string `json:"session_id"`
	FromNodeID string `json:"from_node_id"`
	ToNodeID   string `json:"to_node_id"`
	EdgeType   string `json:"edge_type"`
	Label      string `json:"label,omitempty"`
}

type SourceRef struct {
	Path      string `json:"path"`
	Symbol    string `json:"symbol,omitempty"`
	LineStart int    `json:"line_start,omitempty"`
	LineEnd   int    `json:"line_end,omitempty"`
	Snippet   string `json:"snippet,omitempty"`
}

type NodeDetail struct {
	NodeID           string         `json:"node_id"`
	InputSummary     string         `json:"input_summary,omitempty"`
	OutputSummary    string         `json:"output_summary,omitempty"`
	ErrorSummary     string         `json:"error_summary,omitempty"`
	GoalZh           string         `json:"goal_zh,omitempty"`
	ActionSummaryZh  string         `json:"action_summary_zh,omitempty"`
	ImplementationZh string         `json:"implementation_zh,omitempty"`
	ResultZh         string         `json:"result_zh,omitempty"`
	Evidence         map[string]any `json:"evidence,omitempty"`
	Legend           map[string]any `json:"legend,omitempty"`
	SourceRefs       []SourceRef    `json:"source_refs,omitempty"`
	TableRefs        []string       `json:"table_refs,omitempty"`
	DecisionNotes    []string       `json:"decision_notes,omitempty"`
	PreviewImage     string         `json:"preview_image,omitempty"`
}

type TableColumn struct {
	Key   string `json:"key"`
	Label string `json:"label"`
}

type TableSnapshot struct {
	ID        string           `json:"table_id"`
	NodeID    string           `json:"node_id"`
	Title     string           `json:"title"`
	Columns   []TableColumn    `json:"columns"`
	Rows      []map[string]any `json:"rows"`
	DiffState string           `json:"diff_state,omitempty"`
}

type ReviewCheckpoint struct {
	ID             string   `json:"checkpoint_id"`
	SessionID      string   `json:"session_id"`
	Title          string   `json:"title"`
	TargetNodeIDs  []string `json:"target_node_ids"`
	Priority       string   `json:"priority"`
	ExpectedResult string   `json:"expected_result"`
	ActualResult   string   `json:"actual_result,omitempty"`
	ReviewState    string   `json:"review_state,omitempty"`
}

type Review struct {
	NodeID      string `json:"node_id"`
	SessionID   string `json:"session_id"`
	ReviewState string `json:"review_state"`
	Comment     string `json:"comment,omitempty"`
	UpdatedAt   string `json:"updated_at"`
}

type ImportRecord struct {
	SessionID     string         `json:"session_id"`
	SourceType    string         `json:"source_type"`
	ArtifactPath  string         `json:"artifact_path"`
	ImportedAt    string         `json:"imported_at"`
	ImportSummary map[string]any `json:"import_summary,omitempty"`
}

type Bundle struct {
	Session     Session                    `json:"session"`
	Nodes       []Node                     `json:"nodes"`
	Edges       []Edge                     `json:"edges"`
	Details     map[string]NodeDetail      `json:"node_details"`
	Tables      map[string][]TableSnapshot `json:"tables"`
	Checkpoints []ReviewCheckpoint         `json:"checkpoints"`
	Import      ImportRecord               `json:"import"`
}

type SessionView struct {
	Session     Session            `json:"session"`
	Checkpoints []ReviewCheckpoint `json:"checkpoints"`
	Import      ImportRecord       `json:"import"`
}

type GraphView struct {
	Session Session `json:"session"`
	Nodes   []Node  `json:"nodes"`
	Edges   []Edge  `json:"edges"`
}

type NodeView struct {
	Node   Node       `json:"node"`
	Detail NodeDetail `json:"detail"`
	Review *Review    `json:"review,omitempty"`
}

type ReportView struct {
	Session     Session               `json:"session"`
	Nodes       []Node                `json:"nodes"`
	Details     map[string]NodeDetail `json:"details"`
	Checkpoints []ReviewCheckpoint    `json:"checkpoints"`
	Reviews     map[string]Review     `json:"reviews"`
	Import      ImportRecord          `json:"import"`
}
