#!/usr/bin/env python3
"""Publish five deterministic downloads after checking the reviewed local SHA-256 values."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = 'ohjun1998/burp-workbench-kr'
BRANCH = 'account-a/resume-0260-20261016'
EXPECTED = {
    'downloads/extensions/0.26.0/burp-workbench-kr-0.26.0.jar': '18c57391c8e88070ecdbf4c2214ea887821da34df56207d3c4d745af6d865e97',
    'downloads/extensions/0.26.0/burp-workbench-kr-0.26.0-source.zip': 'c309ff6d9ffb89e2e9e7ee00b43db9f27448366461de21223e98064ed9a1593f',
    'downloads/feature-mapper/0.2.0/feature-mapper-kr-0.2.0.jar': '9583daed9e8149958ec193c23c0b1dba9d0f62f90d51f7612e33e1c92a03d6a5',
    'downloads/feature-mapper/0.2.0/feature-mapper-browser-0.2.0.zip': '7f64f4e4d25da08bd5d66bd8be9b9971b8e32cc6af52e71bbbbd8a48f1236e75',
    'downloads/feature-mapper/0.2.0/feature-mapper-kr-0.2.0-source.zip': 'f3473c5b92dfcfc40a9a4938130db0658926f123a27c6d2645da82a529969655',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    payloads = {}
    for path, expected in EXPECTED.items():
        data = (ROOT / path).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('Reviewed artifact bytes differ: ' + path)
        payloads[path] = data
    if args.verify_only:
        print('All five download SHA-256 values match the locally verified release.')
        return
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise RuntimeError('Unexpected repository')
    if os.environ.get('GITHUB_REF') != 'refs/heads/' + BRANCH:
        raise RuntimeError('Publication is limited to the task branch')
    source = os.environ['GITHUB_SHA']
    token = os.environ['ASSEMBLY_TOKEN']

    def api(method, path, body=None):
        request = urllib.request.Request(
            'https://api.github.com/repos/' + REPOSITORY + '/' + path,
            data=None if body is None else json.dumps(body).encode('utf-8'),
            headers={'Authorization': 'Bearer ' + token,
                     'Accept': 'application/vnd.github+json',
                     'Content-Type': 'application/json',
                     'X-GitHub-Api-Version': '2022-11-28'},
            method=method)
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)

    def check_head():
        if api('GET', 'git/ref/heads/' + BRANCH)['object']['sha'] != source:
            raise RuntimeError('Task branch moved during assembly; refusing to overwrite it')

    check_head()
    parent = api('GET', 'git/commits/' + source)
    entries = []
    for path, data in payloads.items():
        blob = api('POST', 'git/blobs', {
            'content': base64.b64encode(data).decode('ascii'), 'encoding': 'base64'})
        expected_blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if blob['sha'] != expected_blob:
            raise RuntimeError('Uploaded Git blob differs: ' + path)
        entries.append({'path': path, 'mode': '100644', 'type': 'blob', 'sha': blob['sha']})
    tree = api('POST', 'git/trees', {'base_tree': parent['tree']['sha'], 'tree': entries})
    commit = api('POST', 'git/commits', {
        'message': 'Publish byte-exact Workbench 0.26.0 and Mapper 0.2.0 downloads\n\n'
                   'Generated from the reviewed source after the complete release checks. '
                   'All five binaries match the locally verified SHA-256 values. '
                   'Related conversation: docs/chat-logs/2026-10-16/workbench-resume-0260.md',
        'tree': tree['sha'], 'parents': [source]})
    check_head()
    api('PATCH', 'git/refs/heads/' + BRANCH, {'sha': commit['sha'], 'force': False})
    result = {'result': 'passed', 'sourceCommit': source, 'commit': commit['sha'],
              'tree': tree['sha'], 'artifacts': EXPECTED}
    output = ROOT / 'tmp/assembly-0260.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
