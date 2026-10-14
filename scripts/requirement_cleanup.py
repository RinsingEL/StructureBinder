#!/usr/bin/env python3
"""Cross-platform requirement-cleanup state: `check` prints OK/DUE, `mark <reason>` forces DUE,
`complete` records a finished cleanup and schedules the next reminder.

Same state file and semantics as the original PowerShell scripts, resolved relative to this repository.
"""
import json
import sys
from datetime import datetime, timedelta
from pathlib import Path

STATE = Path(__file__).resolve().parents[1] / 'run' / 'workflow_state' / 'requirement_case_cleanup.json'


def check(state_path):
    if not state_path.exists():
        return 'DUE state_missing'
    try:
        state = json.loads(state_path.read_text(encoding='utf-8-sig'))
        if str(state.get('status')) != 'CLEAN':
            return f"DUE status_{state.get('status')}"
        if not str(state.get('lastCompletedAt') or '').strip():
            return 'DUE last_completed_missing'
        if not str(state.get('nextCheckAt') or '').strip():
            return 'DUE next_check_missing'
        next_check = datetime.fromisoformat(str(state['nextCheckAt']))
        now = datetime.now(next_check.tzinfo) if next_check.tzinfo else datetime.now()
        if now >= next_check:
            return 'DUE timestamp_elapsed'
        return f'OK {next_check.isoformat()}'
    except Exception:
        return 'DUE state_invalid'


def mark(state_path, reason):
    values = {'schemaVersion': 'requirement_case_cleanup_state.v0.1', 'intervalDays': 1,
              'lastCompletedAt': '', 'nextCheckAt': ''}
    if state_path.exists():
        try:
            values.update(json.loads(state_path.read_text(encoding='utf-8-sig')))
        except Exception:
            pass  # Replace invalid state with a valid due state.
    values.update(status='DUE_REQUIREMENT_CHANGED', dueReason=reason,
                  markedAt=datetime.now().astimezone().isoformat())
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps(values, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return f'DUE requirement_changed {reason}'


def complete(state_path):
    values = {'schemaVersion': 'requirement_case_cleanup_state.v0.1', 'intervalDays': 1}
    if state_path.exists():
        try:
            values.update(json.loads(state_path.read_text(encoding='utf-8-sig')))
        except Exception:
            pass  # An invalid state is replaced by the completed one.
    try:
        interval = max(1, int(values.get('intervalDays', 1)))
    except (TypeError, ValueError):
        interval = 1
    now = datetime.now().astimezone()
    values.update(status='CLEAN', intervalDays=interval, lastCompletedAt=now.isoformat(),
                  nextCheckAt=(now + timedelta(days=interval)).isoformat())
    values.pop('dueReason', None)
    values.pop('markedAt', None)
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps(values, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return f"CLEAN next {values['nextCheckAt']}"


if __name__ == '__main__':
    command = sys.argv[1] if len(sys.argv) > 1 else 'check'
    if command == 'check':
        print(check(STATE))
    elif command == 'mark':
        print(mark(STATE, sys.argv[2] if len(sys.argv) > 2 else 'requirement_changed'))
    elif command == 'complete':
        print(complete(STATE))
    else:
        sys.exit('usage: requirement_cleanup.py [check | mark <reason> | complete]')
