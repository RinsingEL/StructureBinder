"""MCP stdio transport for the running game's authenticated local designer service.

Usage: python -u designer-mcp.py --game-dir E:/Mod/_Dev/.realmfolk-playtest
No third party packages. stdout contains JSON-RPC only. Credentials never appear there.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--game-dir', required=True, type=Path)
    args = parser.parse_args()
    sys.stdin.reconfigure(encoding='utf-8')
    sys.stdout.reconfigure(encoding='utf-8')
    for line in sys.stdin:
        request = None
        try:
            if len(line.encode('utf-8')) > 131072:
                raise ValueError('Maximum request is 128 KiB')
            request = json.loads(line)
            if not isinstance(request, dict):
                raise ValueError('Expected a JSON-RPC object')
            settings = json.loads((args.game_dir / 'runtime/realmfolk-designer.json').read_text(encoding='utf-8'))
            url = urllib.parse.urlsplit(settings['url'])
            if url.scheme != 'http' or url.hostname != '127.0.0.1' or url.path != '/mcp':
                raise ValueError('Designer service must be local')
            outgoing = urllib.request.Request(settings['url'], data=line.encode('utf-8'), headers={
                'Authorization': 'Bearer ' + settings['token'], 'Content-Type': 'application/json',
                'Accept': 'application/json, text/event-stream',
            })
            # Ignore machine proxy settings for this loopback-only service.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(outgoing, timeout=20) as response:
                payload = response.read().decode('utf-8')
            if payload:
                print(json.dumps(json.loads(payload), ensure_ascii=False), flush=True)
        except Exception as exc:
            if isinstance(request, dict) and 'id' not in request:
                print('Realmfolk notification could not be forwarded; open a world with -Designer.', file=sys.stderr)
                continue
            result = {'jsonrpc': '2.0', 'id': request.get('id') if isinstance(request, dict) else None,
                      'error': {'code': -32000, 'message': 'Designer unavailable or invalid request: ' + type(exc).__name__}}
            print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
