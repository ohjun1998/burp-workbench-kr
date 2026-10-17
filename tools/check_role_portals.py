#!/usr/bin/env python3
"""Compile current lab sources and check real role/session HTTP boundaries on loopback."""
import argparse
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
LAB = ROOT / 'project/burp-development-workspace/community-security-lab'
WB = ROOT / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18736)
    parser.add_argument('--java', default='java')
    parser.add_argument('--out', type=Path, default=ROOT / 'tmp/role-portals')
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('port range: 1024..65535')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', args.port))
    args.out.mkdir(parents=True, exist_ok=True)
    site = args.out / 'site'
    if site.exists():
        shutil.rmtree(site)
    site.mkdir()
    runtime = WB / 'validation/all-features'
    deps = runtime / '.run/deps'
    hashes = json.loads((runtime / 'runtime-hashes.json').read_text())
    for name, digest in hashes.items():
        if not (deps / name).is_file() or hashlib.sha256((deps / name).read_bytes()).hexdigest() != digest:
            raise RuntimeError('Prepare checked HTTP dependencies with python dev.py http-test: ' + name)
    cp = os.pathsep.join(str(deps / name) for name in sorted(hashes))
    subprocess.run([args.java, 'com.sun.tools.javac.Main', '--release', '17', '-parameters', '-encoding', 'UTF-8', '-cp', cp, '-d', str(site)] + [str(p) for p in sorted((LAB / 'src/main/java').rglob('*.java'))], check=True)
    shutil.copytree(LAB / 'src/main/resources', site, dirs_exist_ok=True)
    base = f'http://127.0.0.1:{args.port}'
    checks = []

    class Client:
        def __init__(self):
            self.csrf = ''
            self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), NoRedirect())

        def call(self, path, fields=None, token=None):
            headers = {}
            body = None
            if fields is not None:
                body = urllib.parse.urlencode(fields).encode()
                headers = {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'X-CSRF-Token': self.csrf if token is None else token}
            try:
                response = self.opener.open(urllib.request.Request(base + path, data=body, headers=headers), timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                text = response.read().decode('utf-8')
                try:
                    data = json.loads(text)
                except ValueError:
                    data = text
                return response.status, data, dict(response.headers)

        def login(self, name):
            status, data, _ = self.call('/api/auth/login', {'username': name, 'password': 'LabOnly!2026'})
            assert status == 200
            self.csrf = data['csrf']
            return self

    def check(label, condition):
        checks.append({'name': label, 'passed': bool(condition)})
        if not condition:
            raise AssertionError(label)

    def status(label, client, path, expected, fields=None, token=None):
        code, data, headers = client.call(path, fields, token)
        check(label, code == expected)
        return data, headers

    environment = dict(os.environ, PORT=str(args.port), DB_URL='jdbc:h2:mem:portal_' + uuid.uuid4().hex + ';MODE=Oracle;DB_CLOSE_DELAY=-1', DB_USER='sa', DB_PASSWORD='')
    server = None
    try:
        with (args.out / 'server.log').open('w') as log:
            server = subprocess.Popen([args.java, '-cp', str(site) + os.pathsep + cp, 'lab.enterprise.DevServer'], env=environment, stdout=log, stderr=subprocess.STDOUT)
            guest = Client()
            for _ in range(100):
                if server.poll() is not None:
                    raise RuntimeError('Lab startup failed; inspect server.log')
                try:
                    if guest.call('/health')[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(.2)
            else:
                raise RuntimeError('Lab startup timeout')
            alice, bob, admin, moderator = [Client().login(name) for name in ['alice', 'bob', 'admin', 'moderator']]
            for area, view in [('admin','users'),('user','activity')]:
                for suffix, expected in [('',view),('/login','login')]:
                    _, headers = status(area + suffix + ' redirects to shared community', guest, '/'+area+suffix, 302)
                    check(area + suffix + ' shared destination', headers['Location'] == '/?view='+expected)
            status('admin uses common dashboard', admin, '/api/access/fixed/user/dashboard', 200)
            status('bob reads own request', bob, '/api/access/fixed/user/requests/502', 200)
            status('bob cannot read alice request', bob, '/api/access/fixed/user/requests/501', 404)
            bad_login = Client()
            status('wrong password rejected', bad_login, '/api/auth/login', 401, {'username':'admin','password':'incorrect'})
            status('failed login has no authenticated session', bad_login, '/api/access/me', 401)
            for name, client, expected in [('alice',alice,1),('bob',bob,2),('admin',admin,3)]:
                data, _ = status(name + ' identity response', client, '/api/access/me', 200)
                check(name + ' identity is correct', data['user']['ID'] == expected and data['user']['USERNAME'] == name)
                check(name + ' password hash is absent', 'PASSWORD_HASH' not in data['user'])
            status('anonymous identity is rejected', guest, '/api/access/me', 401)
            for mode in ['fixed', 'vuln']:
                for route in ['admin/members','admin/review-queue','admin/settings','user/dashboard','user/requests/501']:
                    status(mode + ' guest blocked ' + route, guest, f'/api/access/{mode}/'+route, 401)
                for route in ['admin/members','admin/review-queue','admin/settings']:
                    status(mode + ' admin allowed ' + route, admin, f'/api/access/{mode}/'+route, 200)
                    status(mode + ' alice vertical comparison ' + route, alice, f'/api/access/{mode}/'+route, 403 if mode=='fixed' else 200)
                status(mode + ' own object', alice, f'/api/access/{mode}/user/requests/501', 200)
                status(mode + ' cross-user object', alice, f'/api/access/{mode}/user/requests/502', 404 if mode=='fixed' else 200)
            data, _ = status('alice dashboard', alice, '/api/access/fixed/user/dashboard', 200)
            check('dashboard excludes bob objects', {row['OWNER_ID'] for row in data['requests']} == {1})
            for mode in ['fixed','vuln']:
                for route,fields in [('user/requests',{'subject':'anonymous','body':'not allowed'}),('user/requests/501/cancel',{}),('admin/requests/501/review',{'decision':'APPROVED'}),('admin/settings',{'notice':'not allowed'})]:
                    status(mode+' guest write rejected '+route, guest, '/api/access/'+mode+'/'+route, 401, fields)
            status('query role spoof rejected', alice, '/api/access/fixed/admin/members?role=ADMIN', 403)
            status('invalid mode rejected', admin, '/api/access/other/admin/members', 404)
            status('missing object is 404', alice, '/api/access/fixed/user/requests/999999', 404)
            status('fixed create missing csrf', alice, '/api/access/fixed/user/requests', 403, {'subject':'a','body':'b'}, '')
            status('fixed create other account csrf', alice, '/api/access/fixed/user/requests', 403, {'subject':'a','body':'b'}, bob.csrf)
            status('fixed create invalid fields', alice, '/api/access/fixed/user/requests', 400, {'subject':'','body':'b'})
            data, _ = status('alice creates request', alice, '/api/access/fixed/user/requests', 200, {'subject':'권한 비교 테스트','body':'ALICE_NEW_REQUEST'})
            new_id = data['id']
            status('bob cannot cancel alice request', bob, f'/api/access/fixed/user/requests/{new_id}/cancel', 404, {})
            data, _ = status('blocked cancel preserves state', alice, f'/api/access/fixed/user/requests/{new_id}', 200)
            check('state stays pending', data['STATE'] == 'PENDING')
            status('owner cancels pending request', alice, f'/api/access/fixed/user/requests/{new_id}/cancel', 200, {})
            status('cannot cancel twice', alice, f'/api/access/fixed/user/requests/{new_id}/cancel', 409, {})
            status('user cannot approve', alice, '/api/access/fixed/admin/requests/501/review', 403, {'decision':'APPROVED'})
            status('admin csrf enforced', admin, '/api/access/fixed/admin/requests/501/review', 403, {'decision':'APPROVED'}, '')
            status('decision enum enforced', admin, '/api/access/fixed/admin/requests/501/review', 400, {'decision':'OTHER'})
            status('admin approves', admin, '/api/access/fixed/admin/requests/501/review', 200, {'decision':'APPROVED'})
            status('review replay rejected', admin, '/api/access/fixed/admin/requests/501/review', 409, {'decision':'REJECTED'})
            status('user cannot change notice', alice, '/api/access/fixed/admin/settings', 403, {'notice':'forbidden'})
            status('admin updates notice', admin, '/api/access/fixed/admin/settings', 200, {'notice':'새 서비스 공지'})
            data, _ = status('user sees notice', bob, '/api/access/fixed/user/dashboard', 200)
            check('notice persisted', data['notice'] == '새 서비스 공지')
            status('vulnerable cross-user cancel works', alice, '/api/access/vuln/user/requests/502/cancel', 200, {})
            status('reset needs admin', alice, '/api/access/reset', 403, {})
            status('admin resets state', admin, '/api/access/reset', 200, {})
            status('vulnerable vertical review works', alice, '/api/access/vuln/admin/requests/502/review', 200, {'decision':'REJECTED'})
            status('vulnerable settings bypass works', bob, '/api/access/vuln/admin/settings', 200, {'notice':'intentional training bypass'})
            status('vulnerable cancel still rejects nonpending', alice, '/api/access/vuln/user/requests/502/cancel', 409, {})
            status('final reset', admin, '/api/access/reset', 200, {})
            for asset in ['role-portal.js','role-portal.css']:
                status('asset available ' + asset, guest, '/assets/'+asset, 200)
            data, _ = status('OpenAPI includes new endpoints', guest, '/v3/api-docs', 200)
            check('OpenAPI version and portal path', data['info']['version']=='3.7.0' and '/api/access/fixed/admin/members' in data['paths'])
            from community_workspace_checks import run_workspace_checks
            run_workspace_checks(Client, status, check, guest, alice, bob, admin, moderator)
            status('logout', bob, '/api/auth/logout', 200, {})
            status('old session identity rejected', bob, '/api/access/me', 401)
            status('old session protected request rejected', bob, '/api/access/fixed/user/dashboard', 401)
    finally:
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill(); server.wait(timeout=5)
        with socket.socket() as probe:
            closed = probe.connect_ex(('127.0.0.1', args.port)) != 0
        report = {'version':'3.7.0', 'passed':sum(c['passed'] for c in checks), 'failed':sum(not c['passed'] for c in checks), 'checks':checks,
                  'method':'Current Spring MVC/Tomcat sources, isolated H2 Oracle mode, separate real HTTP cookie jars on loopback',
                  'serverPortClosed':closed, 'browserDOM':False, 'nativeBurp':False, 'docker':False}
        (args.out/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    if not closed:
        raise RuntimeError('Owned loopback server did not close')
    print('Role portal HTTP checks:', report['passed'], 'passed;', report['failed'], 'failed')


if __name__ == '__main__':
    main()
