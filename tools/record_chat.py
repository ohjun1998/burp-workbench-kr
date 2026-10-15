#!/usr/bin/env python3
"""Save the available task conversation under a Korean calendar date, idempotently."""
from pathlib import Path
from datetime import date, datetime
from zoneinfo import ZoneInfo
import argparse, hashlib, json, re

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--date', default=datetime.now(ZoneInfo('Asia/Seoul')).date().isoformat())
    p.add_argument('--topic', required=True, help='One ASCII task slug per conversation')
    p.add_argument('--input', required=True, type=Path, help='JSON object containing title and messages')
    a = p.parse_args()
    day = date.fromisoformat(a.date).isoformat()
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,79}', a.topic):
        p.error('topic must be an ASCII filename slug')
    payload = json.loads(a.input.read_text(encoding='utf-8'))
    messages = payload['messages']
    if not isinstance(messages,list) or not messages:
        raise ValueError('messages must be a non-empty list')
    sections = []
    for number, message in enumerate(messages,1):
        role = message['role']
        text = message['content']
        kind = message.get('kind','verbatim')
        if role not in ('user','assistant') or not isinstance(text,str) or kind not in ('verbatim','summary'):
            raise ValueError(f'invalid message {number}')
        label = '사용자' if role == 'user' else '도우미'
        sections.append(f'## {number:02d}. {label}'+(' · 요약' if kind == 'summary' else ' · 원문')+'\n\n'+text.strip()+'\n')
    output = ROOT/'docs/chat-logs'/day/(a.topic+'.md')
    output.parent.mkdir(parents=True,exist_ok=True)
    header = f'# {payload.get("title",a.topic)}\n\n- 날짜: {day} (Asia/Seoul)\n- 작업: {a.topic}\n\n'
    note = '이 작업에서 확인 가능한 사용자 요청과 사용자에게 표시한 응답을 기록합니다. 과거 대화 전체나 내부 추론·도구의 원본 출력은 포함하지 않습니다. 요약한 항목은 별도로 표시합니다.\n\n'
    content = header+note+'\n'.join(sections)
    # Re-running the same input preserves the file rather than appending duplicates.
    if not output.exists() or output.read_text(encoding='utf-8') != content:
        output.write_text(content,encoding='utf-8')
    print(output.relative_to(ROOT),hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
