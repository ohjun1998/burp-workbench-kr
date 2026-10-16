#!/usr/bin/env python3
"""Publish only the two exact locally tested 0.26.1 downloads on the task branch."""
import base64
import hashlib
import json
import os
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REPO = 'ohjun1998/burp-workbench-kr'
BRANCH = 'account-a/windows-store-0261-20261016'
EXPECTED = {
    'downloads/extensions/0.26.1/burp-workbench-kr-0.26.1.jar': 'b5aa13895b2833808d35bb2f26b69aef0f9d8ee39f8126e05c1f6be3b038faba',
    'downloads/extensions/0.26.1/burp-workbench-kr-0.26.1-source.zip': 'fdad9d81d5683e27b6a5f50d0916f03eb14f52afcac6c73b73f61f1439588ea7',
}


def main():
    payloads = {name: (ROOT / name).read_bytes() for name in EXPECTED}
    for name, data in payloads.items():
        if hashlib.sha256(data).hexdigest() != EXPECTED[name]:
            raise RuntimeError('Locally reviewed bytes differ: ' + name)
    if os.environ.get('GITHUB_REPOSITORY') != REPO or os.environ.get('GITHUB_REF') != 'refs/heads/' + BRANCH:
        raise RuntimeError('Unexpected repository or branch')
    source = os.environ['GITHUB_SHA']
    token = os.environ['ASSEMBLY_TOKEN']

    def api(method, path, body=None):
        request = urllib.request.Request(
            'https://api.github.com/repos/' + REPO + '/' + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
                     'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'}, method=method)
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)

    def check_head():
        if api('GET', 'git/ref/heads/' + BRANCH)['object']['sha'] != source:
            raise RuntimeError('Task branch moved; refusing to overwrite')

    check_head()
    base = api('GET', 'git/commits/' + source)['tree']['sha']
    entries = []
    for name, data in payloads.items():
        blob = api('POST', 'git/blobs', {'content': base64.b64encode(data).decode(), 'encoding': 'base64'})
        expected = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if blob['sha'] != expected:
            raise RuntimeError('Git blob mismatch: ' + name)
        entries.append({'path': name, 'mode': '100644', 'type': 'blob', 'sha': expected})
    tree = api('POST', 'git/trees', {'base_tree': base, 'tree': entries})
    commit = api('POST', 'git/commits', {
        'message': 'Publish exact Workbench 0.26.1 Windows storage fix downloads\n\n'
                   'Both SHA-256 values match the locally tested release. '
                   'Conversation: docs/chat-logs/2026-10-16/workbench-windows-0261.md',
        'tree': tree['sha'], 'parents': [source]})
    check_head()
    api('PATCH', 'git/refs/heads/' + BRANCH, {'sha': commit['sha'], 'force': False})
    print(json.dumps({'result': 'passed', 'source': source, 'commit': commit['sha'],
                      'tree': tree['sha'], 'artifacts': EXPECTED}))


if __name__ == '__main__':
    main()
