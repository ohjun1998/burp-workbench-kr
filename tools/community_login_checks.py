"""Standalone login acceptance checks against the current Spring MVC server."""
def run_login_checks(Client, status, check, guest):
    for path in ['/login', '/login/', '/login?type=admin', '/login?type=user']:
        page, headers = status('standalone login page ' + path, guest, path, 200)
        check('login form and role selector ' + path, 'data-screen="login"' in page and 'name="accountType"' in page and 'id="loginform"' in page)
        check('login page is not cached ' + path, headers.get('Cache-Control') == 'no-store')
    page, _ = status('community uses standalone login link', guest, '/', 200)
    check('community has no inline credentials', 'id="login-link"' in page and 'href="/login"' in page and 'id="loginform"' not in page)
    for area, view in [('admin', 'users'), ('user', 'activity')]:
        for suffix in ['', '/']:
            _, headers = status('portal alias ' + area + suffix, guest, '/' + area + suffix, 302)
            check('portal destination ' + area + suffix, headers['Location'] == '/?view=' + view)
            _, headers = status('typed login alias ' + area + suffix, guest, '/' + area + '/login' + suffix, 302)
            check('typed login destination ' + area + suffix, headers['Location'] == '/login?type=' + area)
    for name, kind, role, destination in [('alice', 'USER', 'USER', '/?view=feed'), ('admin', 'ADMIN', 'ADMIN', '/?view=users'), ('moderator', 'USER', 'MODERATOR', '/?view=feed')]:
        client = Client()
        data, _ = status('typed login succeeds ' + name, client, '/api/auth/login', 200, {'username':name, 'password':'LabOnly!2026', 'accountType':kind, 'redirect':'https://example.invalid', 'role':'ADMIN'})
        check('server determines role and landing ' + name, data['user']['ROLE'] == role and data['redirect'] == destination)
        client.csrf = data['csrf']
        status('typed login has authenticated session ' + name, client, '/api/access/me', 200)
        status('typed login actual admin access ' + name, client, '/api/workspace/fixed/admin/users', 200 if role == 'ADMIN' else 403)
        status('typed login common functions ' + name, client, '/api/workspace/fixed/activity', 200)
        status('typed login logout ' + name, client, '/api/auth/logout', 200, {})
        status('typed login logout invalidates session ' + name, client, '/api/access/me', 401)
    for name, kind, code in [('alice','ADMIN',403), ('admin','USER',403), ('moderator','ADMIN',403), ('alice','ROOT',400), ('alice','',400), ('admin','ADMIN',401)]:
        client = Client()
        status('invalid login rejected ' + name + '/' + kind + '/' + str(code), client, '/api/auth/login', code, {'username':name, 'password':'wrong' if code == 401 else 'LabOnly!2026', 'accountType':kind})
        status('invalid typed login creates no session ' + name + '/' + kind + '/' + str(code), client, '/api/access/me', 401)
    client = Client().login('alice')
    before, _ = status('identity before failed account switch', client, '/api/access/me', 200)
    status('failed typed account switch', client, '/api/auth/login', 403, {'username':'admin','password':'LabOnly!2026','accountType':'USER'})
    after, _ = status('identity survives failed account switch', client, '/api/access/me', 200)
    check('failed account switch preserves identity and CSRF', before == after)
    data, _ = status('successful account switch', client, '/api/auth/login', 200, {'username':'admin','password':'LabOnly!2026','accountType':'ADMIN'})
    check('successful switch changes actual identity and token', data['user']['ROLE'] == 'ADMIN' and data['csrf'] != before['csrf'])
    status('old token cannot logout switched identity', client, '/api/auth/logout', 403, {}, before['csrf'])
    client.csrf = data['csrf']
    status('new token logs out switched identity', client, '/api/auth/logout', 200, {})
    api, _ = status('login OpenAPI schema', guest, '/v3/api-docs', 200)
    check('login type is documented', 'accountType' in api['paths']['/api/auth/login']['post']['requestBody']['content']['application/x-www-form-urlencoded']['schema']['properties'])
