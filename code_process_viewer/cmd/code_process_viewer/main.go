package main

import (
	"context"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"

	codeprocessviewer "code_process_viewer"
	"code_process_viewer/internal/httpapi"
	"code_process_viewer/internal/importer/jigsawdebug"
	"code_process_viewer/internal/storage"
)

func main() {
	toolRoot := mustToolRoot()
	repoRoot := filepath.Clean(filepath.Join(toolRoot, ".."))
	docsRoot := filepath.Clean(filepath.Join(repoRoot, "..", "designer_territoryMod"))

	var (
		flagRepoRoot     = flag.String("repo-root", repoRoot, "实现仓库根目录")
		flagDocsRepoRoot = flag.String("docs-repo-root", docsRoot, "文档仓库根目录")
		flagArtifactRoot = flag.String("artifact-root", "", "要自动导入的 jigsaw_solver_debug/<runId> 目录")
		flagDBPath       = flag.String("db-path", filepath.Join(toolRoot, "runtime", "db", "code_process_viewer.db"), "SQLite 数据库路径")
		flagCacheDir     = flag.String("cache-dir", filepath.Join(toolRoot, "runtime", "cache"), "session JSON 与预览图缓存目录")
		flagPort         = flag.Int("port", 6657, "HTTP 监听端口")
	)
	flag.Parse()

	if err := ensureRuntimeDirs(toolRoot); err != nil {
		log.Fatalf("初始化 runtime 目录失败: %v", err)
	}

	store, err := storage.NewStore(*flagDBPath, *flagCacheDir)
	if err != nil {
		log.Fatalf("初始化存储失败: %v", err)
	}
	defer store.Close()

	if *flagArtifactRoot != "" {
		bundle, err := jigsawdebug.Import(jigsawdebug.ImportOptions{
			RepoRoot:     *flagRepoRoot,
			DocsRepoRoot: *flagDocsRepoRoot,
			ArtifactPath: *flagArtifactRoot,
			CacheRoot:    *flagCacheDir,
		})
		if err != nil {
			log.Fatalf("启动时导入调试产物失败: %v", err)
		}
		if err := store.ImportBundle(context.Background(), bundle, true); err != nil {
			log.Fatalf("写入导入结果失败: %v", err)
		}
		log.Printf("已自动导入会话: %s", bundle.Session.ID)
	}

	webFS, err := fs.Sub(codeprocessviewer.WebFS, "web")
	if err != nil {
		log.Fatalf("准备内嵌静态资源失败: %v", err)
	}
	handler, err := httpapi.New(httpapi.Config{
		Store:        store,
		WebFS:        webFS,
		RepoRoot:     *flagRepoRoot,
		DocsRepoRoot: *flagDocsRepoRoot,
		CacheDir:     *flagCacheDir,
	})
	if err != nil {
		log.Fatalf("初始化 HTTP 服务失败: %v", err)
	}

	addr := fmt.Sprintf(":%d", *flagPort)
	log.Printf("code_process_viewer 已启动: http://127.0.0.1%s", addr)
	log.Printf("局域网监听地址: http://0.0.0.0%s", addr)
	if err := http.ListenAndServe(addr, handler); err != nil {
		log.Fatalf("HTTP 服务异常退出: %v", err)
	}
}

func mustToolRoot() string {
	wd, err := os.Getwd()
	if err != nil {
		log.Fatalf("读取当前工作目录失败: %v", err)
	}
	return wd
}

func ensureRuntimeDirs(toolRoot string) error {
	paths := []string{
		filepath.Join(toolRoot, "runtime"),
		filepath.Join(toolRoot, "runtime", "db"),
		filepath.Join(toolRoot, "runtime", "cache"),
		filepath.Join(toolRoot, "runtime", "tmp"),
	}
	for _, item := range paths {
		if err := os.MkdirAll(item, 0o755); err != nil {
			return err
		}
	}
	return nil
}
