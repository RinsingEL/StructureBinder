#!/bin/bash
set -e
cd -- "$(dirname -- "$0")"
studio_python="$PWD/.cache/venv/bin/python"
if [ ! -x "$studio_python" ]; then
  echo '未找到 Studio 本地 Python 环境，请按 README 准备依赖。'
  exit 1
fi
studio_url='http://127.0.0.1:8765/?asset=CH-13-v01'
if /usr/bin/curl --max-time 3 -fsS http://127.0.0.1:8765/api/catalog >/dev/null 2>&1; then
  /usr/bin/open "$studio_url"
  exit 0
fi
(sleep 2; /usr/bin/open "$studio_url") &
exec "$studio_python" cli.py serve
