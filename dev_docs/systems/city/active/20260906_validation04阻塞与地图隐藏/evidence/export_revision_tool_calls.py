"""Export only real test tool calls/results, excluding credentials and model reasoning."""
import json
import sqlite3
from pathlib import Path

profile = Path('run/config/geomantia/hermes/profiles/geomantia-1qdmf2j')
connection = sqlite3.connect(profile.joinpath('state.db').resolve().as_uri() + '?mode=ro', uri=True)
records = []
for row in connection.execute(
    'select session_id,role,tool_call_id,tool_calls,tool_name,content,timestamp from messages '
    "where role='tool' or tool_calls is not null order by id"
):
    session, role, call_id, calls, name, content, timestamp = row
    if not session.startswith('geomantia-'):
        continue
    records.append(dict(sessionId=session, role=role, toolCallId=call_id,
                        toolCalls=json.loads(calls) if calls else None,
                        toolName=name, result=content if role == 'tool' else None, timestamp=timestamp))
target = Path(__file__).with_name('real_glm_tool_calls.json')
target.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Exported {len(records)} tool call/result records to {target}')
