package httpapi

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"path"
	"strings"

	"code_process_viewer/internal/importer/jigsawdebug"
	"code_process_viewer/internal/session"
	"code_process_viewer/internal/storage"
)

type Config struct {
	Store        *storage.Store
	WebFS        fs.FS
	RepoRoot     string
	DocsRepoRoot string
	CacheDir     string
}

type Server struct {
	store        *storage.Store
	webFS        fs.FS
	repoRoot     string
	docsRepoRoot string
	cacheDir     string
}

type importRequest struct {
	ArtifactPath    string `json:"artifact_path"`
	ReplaceExisting bool   `json:"replace_existing"`
}

type reviewRequest struct {
	ReviewState string `json:"review_state"`
	Comment     string `json:"comment"`
}

func New(cfg Config) (http.Handler, error) {
	if cfg.Store == nil {
		return nil, fmt.Errorf("store 不能为空")
	}
	server := &Server{
		store:        cfg.Store,
		webFS:        cfg.WebFS,
		repoRoot:     cfg.RepoRoot,
		docsRepoRoot: cfg.DocsRepoRoot,
		cacheDir:     cfg.CacheDir,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/sessions", server.handleListSessions)
	mux.HandleFunc("GET /api/sessions/{id}", server.handleGetSession)
	mux.HandleFunc("GET /api/sessions/{id}/graph", server.handleGetGraph)
	mux.HandleFunc("GET /api/sessions/{id}/report", server.handleGetReport)
	mux.HandleFunc("GET /api/sessions/{id}/assets/{name}", server.handleGetAsset)
	mux.HandleFunc("GET /api/nodes/{id}", server.handleGetNode)
	mux.HandleFunc("GET /api/nodes/{id}/tables", server.handleGetNodeTables)
	mux.HandleFunc("POST /api/nodes/{id}/review", server.handleSaveReview)
	mux.HandleFunc("POST /api/import/jigsaw-debug", server.handleImportJigsawDebug)
	mux.HandleFunc("GET /", server.handleIndex)
	mux.HandleFunc("GET /app.css", server.handleStatic)
	mux.HandleFunc("GET /app.js", server.handleStatic)
	return mux, nil
}

func (s *Server) handleListSessions(w http.ResponseWriter, r *http.Request) {
	items, err := s.store.ListSessions(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	writeJSON(w, http.StatusOK, items)
}

func (s *Server) handleGetSession(w http.ResponseWriter, r *http.Request) {
	view, err := s.store.GetSession(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleGetGraph(w http.ResponseWriter, r *http.Request) {
	view, err := s.store.GetGraph(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleGetNode(w http.ResponseWriter, r *http.Request) {
	view, err := s.store.GetNode(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleGetNodeTables(w http.ResponseWriter, r *http.Request) {
	items, err := s.store.GetNodeTables(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, items)
}

func (s *Server) handleSaveReview(w http.ResponseWriter, r *http.Request) {
	var req reviewRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, fmt.Errorf("解析 review 请求失败: %w", err))
		return
	}
	if !isAllowedReviewState(req.ReviewState) {
		writeError(w, http.StatusBadRequest, fmt.Errorf("不支持的 review_state: %s", req.ReviewState))
		return
	}
	if _, err := s.store.SaveReview(r.Context(), r.PathValue("id"), req.ReviewState, req.Comment); err != nil {
		writeDomainError(w, err)
		return
	}
	view, err := s.store.GetNode(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) handleGetReport(w http.ResponseWriter, r *http.Request) {
	report, err := s.store.GetReport(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	content := session.BuildMarkdownReport(report)
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(content))
}

func (s *Server) handleImportJigsawDebug(w http.ResponseWriter, r *http.Request) {
	var req importRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, fmt.Errorf("解析导入请求失败: %w", err))
		return
	}

	bundle, err := jigsawdebug.Import(jigsawdebug.ImportOptions{
		RepoRoot:     s.repoRoot,
		DocsRepoRoot: s.docsRepoRoot,
		ArtifactPath: req.ArtifactPath,
		CacheRoot:    s.cacheDir,
	})
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	if err := s.store.ImportBundle(r.Context(), bundle, req.ReplaceExisting); err != nil {
		writeDomainError(w, err)
		return
	}
	view, err := s.store.GetSession(r.Context(), bundle.Session.ID)
	if err != nil {
		writeDomainError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, view)
}

func (s *Server) handleGetAsset(w http.ResponseWriter, r *http.Request) {
	assetPath, err := s.store.GetAssetPath(r.PathValue("id"), r.PathValue("name"))
	if err != nil {
		writeDomainError(w, err)
		return
	}
	http.ServeFile(w, r, assetPath)
}

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	index, err := fs.ReadFile(s.webFS, "index.html")
	if err != nil {
		writeError(w, http.StatusInternalServerError, fmt.Errorf("读取 index.html 失败: %w", err))
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(index)
}

func (s *Server) handleStatic(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
	if name == "." || name == "" || strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}
	content, err := fs.ReadFile(s.webFS, name)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	switch path.Ext(name) {
	case ".css":
		w.Header().Set("Content-Type", "text/css; charset=utf-8")
	case ".js":
		w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
	default:
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(content)
}

func isAllowedReviewState(value string) bool {
	switch strings.TrimSpace(value) {
	case "pass", "concern", "reject", "need_evidence", "unchecked":
		return true
	default:
		return false
	}
}

func writeDomainError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, storage.ErrNotFound):
		writeError(w, http.StatusNotFound, err)
	case errors.Is(err, storage.ErrSessionExists):
		writeError(w, http.StatusConflict, err)
	default:
		writeError(w, http.StatusInternalServerError, err)
	}
}

func writeError(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]any{"error": err.Error()})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(payload)
}
