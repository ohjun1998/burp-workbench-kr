"""Independent HTTP expectations for the authentication training application."""
import base64
import hashlib
import hmac
import http.client
import json
import time
import urllib.parse

PROFILES = {'session': {'Cookie'}, 'token': {'X-Auth-Token'}, 'jwt': {'Authorization'},
            'session-token': {'Cookie', 'X-Auth-Token'}, 'session-jwt': {'Cookie', 'Authorization'},
            'token-jwt': {'X-Auth-Token', 'Authorization'}, 'all': {'Cookie', 'X-Auth-Token', 'Authorization'}}
ACTORS = ['admin', 'alice', 'bob', 'anonymous']
SENSITIVE = {'cookie', 'authorization', 'x-auth-token', 'x-csrf-token', 'set-cookie'}


def run(base, signing_key):
    origin = urllib.parse.urlsplit(base)
    cases, evidence, credentials = [], [], {}
    requests = 0

    def call(path, method='GET', headers=None, fields=None):
        nonlocal requests
        body = urllib.parse.urlencode(fields).encode() if fields is not None else b''
        lines = list((headers or {}).items()) if isinstance(headers or {}, dict) else list(headers)
        if fields is not None:
            lines.append(('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8'))
        conn = http.client.HTTPConnection(origin.hostname, origin.port, timeout=10)
        try:
            conn.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
            conn.putheader('Host', origin.netloc)
            for key, value in lines:
                conn.putheader(key, value)
            if body:
                conn.putheader('Content-Length', str(len(body)))
            conn.endheaders(body)
            response = conn.getresponse()
            raw = response.read()
            response_headers = response.getheaders()
            text = raw.decode('utf-8', errors='replace')
            try:
                value = json.loads(text)
            except ValueError:
                value = text
            requests += 1
            safe_path = path.split('?', 1)[0] + ('?[QUERY_VALUES_REDACTED]' if '?' in path else '')
            safe_request = [f'{k}: '+('[REDACTED]' if k.lower() in SENSITIVE else v) for k, v in lines]
            item = {'requestNumber': requests, 'method': method, 'path': safe_path, 'requestHeaders': safe_request,
                    'requestBody': '[LOGIN_FIELDS_REDACTED]' if path == '/api/auth-lab/login' or path == '/api/auth/login' else body.decode(),
                    'status': response.status, 'responseHeaders': [f'{k}: '+('[REDACTED]' if k.lower() in SENSITIVE else v) for k, v in response_headers],
                    'responseSha256': hashlib.sha256(raw).hexdigest(), 'responseExcerpt': '[AUTHENTICATION_MATERIAL_REDACTED]' if '/login' in path else text[:1400]}
            evidence.append(item)
            return {'status': response.status, 'value': value, 'text': text, 'headers': response_headers, 'evidence': requests}
        finally:
            conn.close()

    def expect(name, profile, actor, group, expected, path, method='GET', headers=None, fields=None, validate=None):
        result = call(path, method, headers, fields)
        okay = result['status'] == expected and (validate(result) if validate else True)
        if expected >= 400:
            okay = okay and 'AUTHLAB_PRIVATE_' not in result['text'] and 'AUTHLAB_ADMIN_DIRECTORY' not in result['text']
        row = {'id': f'HTTP-{len(cases)+1:03}', 'name': name, 'profile': profile, 'actor': actor, 'group': group,
               'expectedStatus': expected, 'status': result['status'], 'passed': bool(okay), 'evidence': result['evidence']}
        cases.append(row)
        if not okay:
            raise AssertionError(f"{row['id']} {name}: expected {expected}, actual {result['status']}")
        return result

    def login(profile, actor, lifetime='normal'):
        result = call('/api/auth-lab/login', 'POST', fields={'profile': profile, 'username': actor, 'password': 'LabOnly!2026', 'lifetime': lifetime})
        assert result['status'] == 200, ('login failed', profile, actor)
        return result['value']['headers']

    def prefix(profile, mode='fixed'):
        return '/api/auth-lab/' + profile + '/' + mode

    def expected_user(result, actor):
        user = result['value']['user']
        return user['username'] == actor and user['id'] == {'alice': 1, 'bob': 2, 'admin': 3}[actor] and user['role'] == ('ADMIN' if actor == 'admin' else 'USER')

    for profile, required in PROFILES.items():
        users = {}
        for actor in ACTORS[:3]:
            result = expect('valid login', profile, actor, 'login', 200, '/api/auth-lab/login', 'POST', fields={'profile': profile, 'username': actor, 'password': 'LabOnly!2026'},
                            validate=lambda r: set(r['value']['headers']) == required | {'X-CSRF-Token'})
            users[actor] = result['value']['headers']
            if 'Cookie' in required:
                cookie = next(v for k, v in result['headers'] if k.lower() == 'set-cookie')
                assert 'HttpOnly' in cookie and 'SameSite=Lax' in cookie and 'Path=/api/auth-lab' in cookie
        users['anonymous'] = {}
        credentials[profile] = users
        for actor in ACTORS:
            header = users[actor]
            expect('server identity', profile, actor, 'identity', 401 if actor == 'anonymous' else 200, '/api/auth-lab/' + profile + '/me', headers=header,
                   validate=None if actor == 'anonymous' else lambda r, a=actor: expected_user(r, a))
            expect('member endpoint', profile, actor, 'authorization', 401 if actor == 'anonymous' else 200, prefix(profile) + '/member', headers=header,
                   validate=None if actor == 'anonymous' else lambda r: r['value']['data']['message'] == 'MEMBER_INFORMATION')
            expect('fixed administrator boundary', profile, actor, 'authorization', 401 if actor == 'anonymous' else 200 if actor == 'admin' else 403, prefix(profile) + '/admin/users', headers=header,
                   validate=None if actor != 'admin' else lambda r: r['value']['data']['marker'] == 'AUTHLAB_ADMIN_DIRECTORY')
            for object_id, owner in [(901, 'alice'), (902, 'bob')]:
                allowed = actor in [owner, 'admin']
                expect('fixed object ownership ' + str(object_id), profile, actor, 'ownership', 401 if actor == 'anonymous' else 200 if allowed else 404,
                       prefix(profile) + '/requests/' + str(object_id), headers=header,
                       validate=None if not allowed else lambda r, o=owner: r['value']['data']['ownerId'] == {'alice': 1, 'bob': 2}[o])
            expect('intentional administrator flaw', profile, actor, 'negative-control', 401 if actor == 'anonymous' else 200, prefix(profile, 'vuln') + '/admin/users', headers=header)
            expect('intentional object flaw', profile, actor, 'negative-control', 401 if actor == 'anonymous' else 200, prefix(profile, 'vuln') + '/requests/901', headers=header)
        expect('public information needs no login', profile, 'anonymous', 'public', 200, '/api/auth-lab/' + profile + '/public')
        for key in required:
            missing = dict(users['alice']);missing.pop(key)
            expect('required part absent: ' + key, profile, 'alice', 'missing-component', 401, prefix(profile) + '/member', headers=missing)
        invalid = dict(users['alice'])
        key = next(iter(required));invalid[key] = 'AUTHLABSID=invalid' if key == 'Cookie' else 'Bearer invalid' if key == 'Authorization' else 'invalid'
        expect('invalid required credential', profile, 'alice', 'invalid-component', 401, prefix(profile) + '/member', headers=invalid)
        if len(required) > 1:
            for key in required:
                mixed = dict(users['alice']);mixed[key] = users['admin'][key]
                expect('different accounts mixed: ' + key, profile, 'alice/admin', 'mixed-binding', 401, prefix(profile) + '/member', headers=mixed)
            fresh = login(profile, 'alice');same_user = dict(users['alice']);same_user[next(iter(required))] = fresh[next(iter(required))]
            expect('different logins of same user mixed', profile, 'alice/alice', 'mixed-binding', 401, prefix(profile) + '/member', headers=same_user)
        extra = dict(users['alice'])
        if 'Authorization' not in required:
            extra['Authorization'] = 'Bearer invalid.extra.signature'
        else:
            extra['X-Auth-Token'] = 'invalid'
        expect('invalid extra credential never falls back', profile, 'alice', 'no-fallback', 401, prefix(profile) + '/member', headers=extra)
        expect('client role parameter cannot elevate', profile, 'alice', 'role-spoof', 403, prefix(profile) + '/admin/users?role=ADMIN', headers={**users['alice'], 'X-Role': 'ADMIN'})
        expect('incorrect password rejected', profile, 'alice', 'login', 401, '/api/auth-lab/login', 'POST', fields={'profile': profile, 'username': 'alice', 'password': 'wrong-password'})
        # Verify state before and after each denied write, rather than relying on its status alone.
        path = prefix(profile) + '/requests/901'
        before_result = call(path, headers=users['alice']);before = before_result['value']['data']
        denied = expect('other owner write rejected', profile, 'bob', 'write-ownership', 404, path + '/note', 'POST', users['bob'], {'note': 'SHOULD_NOT_CHANGE'})
        after_result = call(path, headers=users['alice']);after = after_result['value']['data'];assert after == before
        cases[-1].update(stateUnchanged=True, beforeEvidence=before_result['evidence'], afterEvidence=after_result['evidence'])
        expect('anonymous write rejected', profile, 'anonymous', 'write-ownership', 401, path + '/note', 'POST', {}, {'note': 'SHOULD_NOT_CHANGE'})
        assert call(path, headers=users['alice'])['value']['data'] == before;cases[-1]['stateUnchanged'] = True
        if 'Cookie' in required:
            no_csrf = dict(users['alice']);no_csrf.pop('X-CSRF-Token')
            expect('cookie write requires CSRF', profile, 'alice', 'csrf', 403, path + '/note', 'POST', no_csrf, {'note': 'SHOULD_NOT_CHANGE'})
            assert call(path, headers=users['alice'])['value']['data'] == before;cases[-1]['stateUnchanged'] = True
            expect('other session CSRF rejected', profile, 'alice', 'csrf', 403, path + '/note', 'POST', {**users['alice'], 'X-CSRF-Token': users['bob']['X-CSRF-Token']}, {'note': 'SHOULD_NOT_CHANGE'})
        expect('foreign origin rejected', profile, 'alice', 'csrf', 403, path + '/note', 'POST', {**users['alice'], 'Origin': 'https://outside.invalid'}, {'note': 'SHOULD_NOT_CHANGE'})
        assert call(path, headers=users['alice'])['value']['data'] == before;cases[-1]['stateUnchanged'] = True
        marker = 'AUTH_HTTP_OWN_' + profile
        expect('owner write succeeds', profile, 'alice', 'write-positive', 200, path + '/note', 'POST', users['alice'], {'note': marker}, validate=lambda r: r['value']['data']['note'] == marker)
        actual_result = call(path, headers=users['alice']);actual = actual_result['value']['data'];assert actual['note'] == marker and actual['revision'] == before['revision'] + 1;cases[-1].update(stateChanged=True, beforeEvidence=before_result['evidence'], afterEvidence=actual_result['evidence'])
        marker = 'AUTH_HTTP_VULN_' + profile
        expect('intentional write ownership flaw', profile, 'bob', 'negative-control', 200, prefix(profile, 'vuln') + '/requests/901/note', 'POST', users['bob'], {'note': marker})
        actual_result = call(path, headers=users['alice']);assert actual_result['value']['data']['note'] == marker;cases[-1].update(stateChanged=True, afterEvidence=actual_result['evidence'])

    # Duplicate credentials are rejected instead of picking the first or last value.
    h = credentials['all']['alice']
    expect('duplicate authorization headers', 'all', 'alice/admin', 'ambiguity', 401, prefix('all') + '/member', headers=list(h.items()) + [('Authorization', credentials['all']['admin']['Authorization'])])
    expect('duplicate opaque headers', 'all', 'alice/admin', 'ambiguity', 401, prefix('all') + '/member', headers=list(h.items()) + [('X-Auth-Token', credentials['all']['admin']['X-Auth-Token'])])
    expect('duplicate authentication cookies', 'all', 'alice/admin', 'ambiguity', 401, prefix('all') + '/member', headers={**h, 'Cookie': h['Cookie'] + '; ' + credentials['all']['admin']['Cookie']})
    expect('cross-profile credential replay', 'jwt', 'alice', 'profile-binding', 401, prefix('jwt') + '/member', headers=h)
    expect('query token is not accepted', 'token', 'alice', 'transport', 401, prefix('token') + '/member?token=' + credentials['token']['alice']['X-Auth-Token'])
    expect('unrelated cookies do not change token identity', 'token', 'alice', 'transport', 200, '/api/auth-lab/token/me', headers={**credentials['token']['alice'], 'Cookie': 'JSESSIONID=unrelated; theme=dark'}, validate=lambda r: expected_user(r, 'alice'))

    b64 = lambda value: base64.urlsafe_b64encode(value).rstrip(b'=').decode()
    def sign(header, body):
        a = b64(json.dumps(header, separators=(',', ':')).encode()) if isinstance(header, dict) else b64(header.encode())
        b = b64(json.dumps(body, separators=(',', ':')).encode()) if isinstance(body, dict) else b64(body.encode())
        return 'Bearer ' + a + '.' + b + '.' + b64(hmac.new(signing_key, (a + '.' + b).encode(), hashlib.sha256).digest())

    for profile in ['jwt', 'session-jwt', 'token-jwt', 'all']:
        header = credentials[profile]['alice'];jwt = header['Authorization'][7:];claims = json.loads(base64.urlsafe_b64decode(jwt.split('.')[1] + '==='))
        variants = [('issuer mismatch', {'iss': 'wrong-issuer'}), ('audience mismatch', {'aud': 'wrong-audience'}),
                    ('expired JWT', {'exp': int(time.time()) - 60}), ('future not-before', {'nbf': int(time.time()) + 300}),
                    ('future issued-at', {'iat': int(time.time()) + 300}), ('subject mismatch', {'sub': '3'}),
                    ('expiry type mismatch', {'exp': str(claims['exp'])}), ('profile claim mismatch', {'profile': 'wrong-profile'})]
        for name, patch in variants:
            expect(name, profile, 'alice', 'jwt-claims', 401, prefix(profile) + '/member', headers={**header, 'Authorization': sign({'alg': 'HS256', 'typ': 'JWT'}, {**claims, **patch})})
        expect('signed role claim cannot elevate server role', profile, 'alice', 'role-spoof', 403, prefix(profile) + '/admin/users', headers={**header, 'Authorization': sign({'alg': 'HS256', 'typ': 'JWT'}, {**claims, 'role': 'ADMIN'})})
        for name, jwt_header in [('none algorithm rejected', {'alg': 'none', 'typ': 'JWT'}), ('other algorithm rejected', {'alg': 'RS256', 'typ': 'JWT'}), ('other token type rejected', {'alg': 'HS256', 'typ': 'id+jwt'}), ('remote key header rejected', {'alg': 'HS256', 'typ': 'JWT', 'jku': 'https://outside.invalid/keys'})]:
            expect(name, profile, 'alice', 'jwt-header', 401, prefix(profile) + '/member', headers={**header, 'Authorization': sign(jwt_header, claims)})
        expect('duplicate JWT header keys rejected', profile, 'alice', 'jwt-header', 401, prefix(profile) + '/member', headers={**header, 'Authorization': sign('{"alg":"none","alg":"HS256","typ":"JWT"}', claims)})
        duplicate = json.dumps(claims)[:-1] + ',"sub":"3"}'
        expect('duplicate JWT claims rejected', profile, 'alice', 'jwt-claims', 401, prefix(profile) + '/member', headers={**header, 'Authorization': sign({'alg': 'HS256', 'typ': 'JWT'}, duplicate)})
        forged = dict(claims);forged['sub'] = '3'
        original_parts = jwt.split('.');original_parts[1] = b64(json.dumps(forged).encode())
        expect('payload tampering invalidates signature', profile, 'alice', 'jwt-signature', 401, prefix(profile) + '/member', headers={**header, 'Authorization': 'Bearer ' + '.'.join(original_parts)})
        expect('Bearer scheme case is insensitive', profile, 'alice', 'transport', 200, '/api/auth-lab/' + profile + '/me', headers={**header, 'Authorization': 'bearer ' + jwt}, validate=lambda r: expected_user(r, 'alice'))

    # Live DB role and suspension changes also apply to an already-issued JWT.
    legacy = call('/api/auth/login', 'POST', fields={'username': 'admin', 'password': 'LabOnly!2026'})
    legacy_cookie = next(v.split(';')[0] for k, v in legacy['headers'] if k.lower() == 'set-cookie')
    legacy_headers = {'Cookie': legacy_cookie, 'X-CSRF-Token': legacy['value']['csrf']}
    edit_path = '/api/workspace/fixed/admin/users/1/edit'
    assert call(edit_path, 'POST', legacy_headers, {'role': 'ADMIN', 'state': 'ACTIVE'})['status'] == 200
    expect('current database promotion applied', 'all', 'alice', 'live-role', 200, prefix('all') + '/admin/users', headers=credentials['all']['alice'])
    assert call(edit_path, 'POST', legacy_headers, {'role': 'USER', 'state': 'ACTIVE'})['status'] == 200
    expect('current database demotion applied', 'all', 'alice', 'live-role', 403, prefix('all') + '/admin/users', headers=credentials['all']['alice'])
    assert call(edit_path, 'POST', legacy_headers, {'role': 'USER', 'state': 'SUSPENDED'})['status'] == 200
    expect('suspension invalidates current authorization', 'all', 'alice', 'live-role', 403, prefix('all') + '/member', headers=credentials['all']['alice'])
    expect('suspended account cannot log in', 'all', 'alice', 'live-role', 403, '/api/auth-lab/login', 'POST', fields={'profile': 'all', 'username': 'alice', 'password': 'LabOnly!2026'})
    assert call(edit_path, 'POST', legacy_headers, {'role': 'USER', 'state': 'ACTIVE'})['status'] == 200
    expect('restored database status applied', 'all', 'alice', 'live-role', 200, prefix('all') + '/member', headers=credentials['all']['alice'])
    for profile in PROFILES:
        fresh = login(profile, 'alice')
        expect('logout succeeds', profile, 'alice', 'revocation', 200, '/api/auth-lab/' + profile + '/logout', 'POST', fresh, {})
        expect('logout revokes issued credentials', profile, 'alice', 'revocation', 401, '/api/auth-lab/' + profile + '/me', headers=fresh)
    short = {}
    for profile in PROFILES:
        short[profile] = login(profile, 'alice', 'short')
        assert call(prefix(profile) + '/member', headers=short[profile])['status'] == 200
    time.sleep(2.3)
    for profile in PROFILES:
        expect('expired authentication rejected', profile, 'alice', 'expiration', 401, prefix(profile) + '/member', headers=short[profile])
    assert call('/api/auth-lab/all/reset', 'POST', credentials['all']['admin'], {})['status'] == 200
    return {'result': 'passed', 'checks': len(cases), 'targetHttpRequests': requests, 'cases': cases, 'evidence': evidence,
            'cookieAttributesChecked': ['HttpOnly', 'SameSite=Lax', 'Path=/api/auth-lab'],
            'jwtFixtureKey': 'per-run random key, never included in evidence'}
