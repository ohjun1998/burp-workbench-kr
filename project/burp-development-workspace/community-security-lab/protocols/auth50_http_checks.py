"""Independent reviewer-authored policy versus real HTTP/data state; never reads server policy labels."""
from pathlib import Path
import csv,hashlib,json,urllib.request,urllib.error
from collections import Counter
ROOT=Path(__file__).resolve().parents[1]
PROFILES=['session','token','jwt','session-token','session-jwt','token-jwt','all']
ACTORS=['admin','alice','bob','anonymous']

def expected_features():
 policy=ROOT/'audit/auth50/expected-policy.csv'
 if not policy.exists():policy=Path(__file__).with_name('auth50_policy.csv')
 with policy.open() as f:return list(csv.DictReader(f))

def run(base):
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPHandler(),NoRedirect())
 requests=0
 def send(method,path,headers=None,fields=None):
  nonlocal requests
  from urllib.parse import urlencode
  request=urllib.request.Request(base+path,data=urlencode(fields).encode() if fields is not None else (b'' if method not in ['GET','HEAD'] else None),method=method,headers=dict(headers or {}));requests+=1
  if fields is not None:request.add_header('Content-Type','application/x-www-form-urlencoded')
  try:r=opener.open(request,timeout=10)
  except urllib.error.HTTPError as error:r=error
  with r:body=r.read(256*1024);return r.status,body,r.headers
 def decoded(body):
  try:return json.loads(body)
  except (ValueError,UnicodeDecodeError):return body.decode('utf-8','replace')
 cases=[];features=expected_features();credentials={}
 catalog=decoded(send('GET','/api/auth-lab/bench/catalog')[1]);assert len(catalog['features'])==50
 assert {(x['method'],x['path']) for x in catalog['features']}=={(x['method'],x['path']) for x in features}
 mutations={6:('createdPosts',None),8:('content','EDITED_DRAFT'),9:('deleted','true'),10:('visibility','HIDDEN'),12:('comment','EDITED_COMMENT'),13:('deleted','true'),14:('pinned','true'),17:('email','bob.changed@example.test'),19:('revokedDevices',None),20:('keyVersion',None),22:('readNotifications',None),23:('deleted','true'),25:('accountState','LOCKED'),28:('enrollments',None),29:('enrollmentState','CANCELLED'),30:('enrollmentState','APPROVED'),32:('registrations',None),33:('reservationState','CANCELLED'),34:('refundRequests',None),37:('wiki','EDITED_TEAM_B_WIKI'),38:('invitations',None),39:('membershipState','REMOVED'),41:('attachments',None),43:('shareLinkVersion',None),44:('shareState','REVOKED'),48:('assignedRole','MODERATOR'),50:('ticketState','CLOSED')}
 for profile in PROFILES:
  bundles={}
  for actor in ACTORS[:3]:
   status,body,_=send('POST','/api/auth-lab/login',fields={'profile':profile,'username':actor,'password':'LabOnly!2026','lifetime':'normal'});assert status==200
   bundles[actor]=decoded(body)['headers']
  bundles['anonymous']={};credentials[profile]=bundles
  for mode in ['fixed','vuln']:
   for f in features:
    fid=int(f['id']);write=f['method']!='GET';statepath=f'/api/auth-lab/{profile}/bench/inspect/{fid}?mode={mode}'
    for actor in ACTORS:
     before=decoded(send('GET',statepath,bundles['admin'])[1])['data'] if write else None
     path=f"/api/auth-lab/{profile}/{mode}/features/{f['path']}";status,body,headers=send(f['method'],path,bundles[actor]);value=decoded(body)
     after=decoded(send('GET',statepath,bundles['admin'])[1])['data'] if write else None
     expected_allow=f[actor]=='ALLOW';actual_should_allow=expected_allow or (mode=='vuln' and actor!='anonymous')
     marker=f'AUTH50_PRIVATE_{fid}'.encode();public_marker=f'AUTH50_PUBLIC_{fid}'.encode()
     changed=write and after['revision']!=before['revision'];exposed=bool(changed) if write else marker in body or public_marker in body
     assert exposed==actual_should_allow,(profile,mode,fid,actor,'data/effect mismatch',status)
     if actor=='anonymous' and not expected_allow:assert status==401,(fid,status)
     if write:
      if actual_should_allow:
       assert after['revision']==before['revision']+1 and after['eventCount']==before['eventCount']+1
       field,want=mutations[fid];assert after[field]==(before.get(field,0)+1 if want is None else want),(fid,field)
      else:assert before==after,(fid,actor,'denial changed state')
     violation=not expected_allow and exposed
     cases.append({'id':f'HTTP50-{len(cases)+1:04d}','featureId':fid,'name':f['name'],'profile':profile,'mode':mode,'actor':actor,'method':f['method'],'path':path,'expectedPolicy':f[actor],'status':status,'groundTruthViolation':violation,'dataExposed':not write and exposed,'actualStateChanged':bool(changed),'before':before,'after':after,'responseSha256':hashlib.sha256(body).hexdigest(),'responseExcerpt':body[:1800].decode('utf-8','replace'),'requestHeaders':{k:'[REDACTED]' for k in bundles[actor]},'passed':True})
  print('HTTP50_PROFILE_COMPLETE',profile,len(cases),flush=True)
 # Invalid combinations hit a protected new feature, not just the old authentication endpoints.
 negative=[]
 for profile,bundles in credentials.items():
  admin=bundles['admin'];alice=bundles['alice'];auth=[k for k in admin if k!='X-CSRF-Token']
  probes=[]
  for key in auth:
   probe=dict(admin);probe.pop(key);probes.append(('missing '+key,probe))
  for key in auth:
   probe=dict(admin);probe[key]='invalid' if key!='Cookie' else 'AUTHLABSID='+'x'*43;probes.append(('invalid '+key,probe))
  if len(auth)>1:
   for key in auth:
    probe=dict(admin);probe[key]=alice[key];probes.append(('cross-account '+key,probe))
  for label,probe in probes:
   status,body,_=send('GET',f'/api/auth-lab/{profile}/fixed/features/administration/usage',probe);assert status==401,(profile,label,status)
   assert b'AUTH50_PRIVATE_' not in body
   negative.append({'profile':profile,'probe':label,'expectedStatus':401,'status':status,'passed':True})
 return {'result':'passed','uniqueFunctions':50,'profiles':PROFILES,'actors':ACTORS,'checks':len(cases),'targetHttpRequests':requests,'cases':cases,'invalidCombinationChecks':negative,'groundTruthViolations':sum(c['groundTruthViolation'] for c in cases),'writesChecked':sum(c['before'] is not None for c in cases),'policySource':'audit/auth50/expected-policy.csv; authored independently of server implementation','oracle':'Protected marker for reads; independently observed actual DB revision, business field and event count for writes. HTTP status alone is not the oracle.'}

class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args):return None
