"""RBAC feature boundaries, server capabilities and fail-closed HTTP acceptance."""
def run_policy_checks(Client, status, check, guest, alice, bob, admin, moderator):
    for name, client, expected in [('guest',guest,401),('alice',alice,403),('moderator',moderator,403),('admin',admin,200)]:
        for route in ['permissions','audit']:
            status('policy administration '+name+'/'+route,client,'/api/access/fixed/admin/'+route,expected)
    identities={}
    for name,client in [('alice',alice),('bob',bob),('admin',admin),('moderator',moderator)]:
        data,_=status('capability identity '+name,client,'/api/access/me',200);identities[name]=data
        permissions=set(data['permissions'])
        check('common permissions retained '+name,{'member:read','post:write','comment:write','course:apply','report:create','message:use'}.issubset(permissions))
        check('management permission matches actual role '+name,('user:manage' in permissions)==(name=='admin'))
        check('board permission is scoped by role '+name,('board:moderate' in permissions)==(name in ['admin','moderator']))
        check('permission labels cover identity '+name,permissions.issubset(data['permissionLabels']))
    for client in [guest,alice,admin]:
        data,_=status('missing declared API policy denied',client,'/api/workspace/fixed/policy-unregistered',403)
        check('unregistered API body is never executed','mustNeverBeReturned' not in data)
    for name,client,expected in [('guest',guest,401),('alice',alice,403),('moderator',moderator,200),('admin',admin,200)]:
        data,_=status('scoped moderation '+name,client,'/api/workspace/fixed/moderation/posts',expected)
        if expected==200:check('moderation excludes private and other boards '+name,all(p['CATEGORY']=='security' and p['VISIBILITY']!='PRIVATE' for p in data))
    for name,client,post_id,can_edit in [('guest',guest,101,False),('alice',alice,101,True),('bob',bob,101,False),('moderator',moderator,105,True),('moderator',moderator,101,False),('admin',admin,102,True)]:
        data,_=status('server object capabilities '+name+'/'+str(post_id),client,f'/api/community/fixed/posts/{post_id}',200)
        check('server decides edit and delete '+name+'/'+str(post_id),data['capabilities']['edit']==can_edit and data['capabilities']['delete']==can_edit)
        check('server decides comment capability '+name+'/'+str(post_id),data['capabilities']['comment']==(name!='guest'))
    data,_=status('create post for scope test',admin,'/api/community/fixed/posts',200,{'title':'Policy scope test','body':'scoped','category':'security','visibility':'PUBLIC'})
    post_id=data['id']
    fields={'title':'Moved','body':'scoped','category':'general','visibility':'PUBLIC'}
    status('moderator cannot move managed post outside board',moderator,f'/api/community/fixed/posts/{post_id}/edit',403,fields)
    fields.update(category='security',visibility='PRIVATE')
    status('moderator cannot privatize another member post',moderator,f'/api/community/fixed/posts/{post_id}/edit',403,fields)
    status('moderator may manage assigned public board',moderator,f'/api/community/fixed/posts/{post_id}/delete',200,{})
    status('ordinary member cannot spoof feature permission',alice,'/api/access/fixed/admin/permissions?role=ADMIN&permission=policy:read',403)
    status('record denial without query contents',alice,'/api/workspace/fixed/admin/users?secret=DO_NOT_LOG_QUERY_380',403)
    data,_=status('manager reads bounded audit',admin,'/api/access/fixed/admin/audit',200)
    check('audit contains feature denial',any(r['outcome']=='DENIED' and r['status']==403 and r['username']=='alice' for r in data['items']))
    check('audit contains completed write',any(r['outcome']=='ALLOWED' and r['method']=='POST' for r in data['items']))
    check('audit metadata excludes secrets','DO_NOT_LOG_QUERY_380' not in str(data) and all('csrf' not in r and 'password' not in r and 'cookie' not in r for r in data['items']))
    check('audit bounded and retention explicit',len(data['items'])<=data['capacity']==200 and data['persistent'] is False)
    # The same already-authenticated cookie jar must pick up grant/revoke on its next request.
    status('grant moderator role through manager',admin,'/api/workspace/fixed/admin/users/2/edit',200,{'role':'MODERATOR','state':'ACTIVE'})
    data,_=status('old bob session receives granted capability',bob,'/api/access/me',200)
    check('role grant updates server permissions','board:moderate' in data['permissions'] and 'user:manage' not in data['permissions'])
    status('new capability enables scoped API',bob,'/api/workspace/fixed/moderation/posts',200)
    status('revoke moderator capability',admin,'/api/workspace/fixed/admin/users/2/edit',200,{'role':'USER','state':'ACTIVE'})
    status('old cookie cannot retain revoked feature',bob,'/api/workspace/fixed/moderation/posts',403)
    data,_=status('revoked identity is current',bob,'/api/access/me',200)
    check('revoked feature absent from identity','board:moderate' not in data['permissions'])
