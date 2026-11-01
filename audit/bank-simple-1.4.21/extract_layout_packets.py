#!/usr/bin/env python3
"""Derive limited, redacted display packets from the already saved real bank corpus.

No network requests are sent. Original fingerprints are retained separately from
the deliberately changed display text; this file is never a wire-byte exporter.
"""
import argparse,base64,hashlib,json,re
from collections import defaultdict
from datetime import datetime,timezone
from pathlib import Path
from urllib.parse import parse_qsl,urlsplit,urlunsplit,urlencode

REPO=Path(__file__).resolve().parents[2]
DEFAULT_WORK=Path('/workspace/tools/burp-workbench/bank-simple-1.4.21')
MASK='[가림]'
JWT=re.compile(r'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*')
URI_USERINFO=re.compile(r'(?i)([a-z][a-z0-9+.-]*://)([^/\s@]+)@')
SENSITIVE_WORDS=('password','passwd','pwd','token','secret','apikey','accesskey',
                 'authorization','cookie','sessionid','sessiontoken','signingkey','privatekey')
SENSITIVE_HEADERS={'authorization','proxy-authorization','cookie','set-cookie','x-api-key','api-key','x-auth-token'}
REQUEST_HEADERS={'host','accept','content-type','content-length','user-agent','accept-encoding','accept-language'}
RESPONSE_HEADERS={'content-type','content-length','server','date','cache-control','expires','pragma',
                  'access-control-allow-origin','access-control-allow-methods','access-control-allow-headers',
                  'content-security-policy','x-content-type-options','x-frame-options','location','content-encoding'}
TEXT_ASSIGNMENT=re.compile(r'''(?m)(["']?[A-Za-z_$][A-Za-z0-9_$.-]*["']?)(\s*(?:=(?!=)|:(?!:))\s*)("(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|[^\s,;<>\r\n]+)''')
QUERY_SECRET=re.compile(r'''(?i)([?&](?:[^=&\s"'<>]*(?:password|passwd|token|secret|api[_-]?key|access[_-]?key)[^=&\s"'<>]*)=)[^&\s"'<>]+''')

def sha(raw):return hashlib.sha256(raw).hexdigest()
def sensitive(key):
    compact=re.sub(r'[^a-z0-9]','',str(key).lower())
    return any(word in compact for word in SENSITIVE_WORDS)
def split_http(raw):
    head,separator,body=raw.partition(b'\r\n\r\n')
    if not separator:head,separator,body=raw.partition(b'\n\n')
    if not separator:raise ValueError('Serialized HTTP separator missing')
    lines=head.decode('iso-8859-1').splitlines();headers=[]
    for line in lines[1:]:
        if ':' in line:
            name,value=line.split(':',1);headers.append((name.strip(),value.strip()))
    return lines[0],headers,body
def content_type(headers):return next((v for k,v in headers if k.lower()=='content-type'),'')
def actor_from_headers(headers):
    """Read only a whitelisted username claim; never validate or publish a token."""
    values=[v for k,v in headers if k.lower() in {'authorization','cookie'}]
    present=bool(values)
    if not present:return 'anonymous',False
    names=set()
    for value in values:
        for token in JWT.findall(value):
            try:
                middle=token.split('.')[1]
                claims=json.loads(base64.urlsafe_b64decode(middle+'='*((-len(middle))%4)))
                name=claims.get('username')
                if name in {'admin','wb_alice','wb_bob'}:names.add(name)
            except (ValueError,UnicodeDecodeError,TypeError):pass
    return (next(iter(names)) if len(names)==1 else 'unknown'),present
def is_admin_html(url,ctype):
    path=urlsplit(url).path.lower()
    return 'html' in ctype.lower() and (path.startswith('/sup3r_s3cr3t_admin') or path.startswith('/admin'))

class Sanitizer:
    def __init__(self,forbidden):
        self.forbidden=sorted({s for s in forbidden if isinstance(s,str) and len(s)>=4},key=len,reverse=True)
    def text(self,value):
        text=str(value)
        for secret in self.forbidden:text=text.replace(secret,MASK)
        text=JWT.sub(MASK,text)
        text=URI_USERINFO.sub(lambda m:m.group(1)+MASK+'@',text)
        text=QUERY_SECRET.sub(lambda m:m.group(1)+MASK,text)
        # Tokenize every assignment, then classify the entire key. Requiring a
        # prefix before the sensitive word misses keys such as SECRET_KEY.
        def assignment(match):
            # Query values were handled above; do not swallow later &parameters.
            if match.start()>0 and text[match.start()-1] in '?&':return match.group(0)
            return match.group(1)+match.group(2)+MASK if sensitive(match.group(1).strip('"\'')) else match.group(0)
        return TEXT_ASSIGNMENT.sub(assignment,text)
    def structured(self,value):
        if isinstance(value,dict):return {self.text(k):(MASK if sensitive(k) else self.structured(v)) for k,v in value.items()}
        if isinstance(value,list):return [self.structured(x) for x in value]
        return self.text(value) if isinstance(value,str) else value
    def url(self,value):
        url=self.text(value);parts=urlsplit(url)
        if not parts.query:return url
        pairs=parse_qsl(parts.query,keep_blank_values=True)
        if not any(sensitive(k) for k,_ in pairs):return url
        pairs=[(k,MASK if sensitive(k) else self.text(v)) for k,v in pairs]
        return urlunsplit((parts.scheme,parts.netloc,parts.path,urlencode(pairs,safe='[]'),parts.fragment))
    def headers(self,headers,response=False):
        kept=[];omitted=[];allow=RESPONSE_HEADERS if response else REQUEST_HEADERS
        for key,value in headers:
            name=key.lower()
            if name in SENSITIVE_HEADERS or sensitive(name):kept.append({'name':key,'value':MASK,'redacted':True})
            elif name in allow:
                safe=self.url(value) if name=='location' else self.text(value)
                kept.append({'name':key,'value':safe,'redacted':safe!=value})
            else:omitted.append(key)
        return kept,omitted
    def body(self,raw,ctype,url,request=False):
        info={'originalBodyBytes':len(raw),'bodyText':'','bodyJson':None,'bodyKind':'empty',
              'truncated':False,'omitted':False,'note':''}
        if not raw:return info
        if is_admin_html(url,ctype):
            info.update(bodyKind='html',omitted=True,note='관리자 HTML 원문은 민감한 계정 자료를 포함할 수 있어 표시하지 않습니다.');return info
        lower=ctype.lower()
        if lower.startswith('image/') or lower.startswith(('application/octet-stream','font/')):
            info.update(bodyKind='binary',omitted=True,note='바이너리 본문은 표시하지 않습니다.');return info
        match=re.search(r'charset\s*=\s*["\']?([\w-]+)',ctype,re.I)
        charset=match.group(1) if match else 'utf-8'
        try:text=raw.decode(charset)
        except (UnicodeDecodeError,LookupError):
            text=raw.decode('utf-8','replace');info['note']='UTF-8 대체 문자로 표시한 본문입니다.'
        if 'json' in lower or text.lstrip().startswith(('{','[')):
            try:decoded=json.loads(text)
            except (ValueError,TypeError):decoded=None
            if decoded is not None:
                decoded=self.structured(decoded);safe=json.dumps(decoded,ensure_ascii=False,indent=2)
                info.update(bodyKind='json',bodyText=safe,bodyJson=decoded)
                return info
        safe=self.text(text)
        kind='html' if 'html' in lower else 'javascript' if ('javascript' in lower or 'ecmascript' in lower) else 'text'
        limit=4000
        info.update(bodyKind=kind,bodyText=safe[:limit],truncated=len(safe)>limit)
        if info['truncated']:info['note']=(info['note']+' ' if info['note'] else '')+f'안전 표시본의 앞 {limit}자만 표시합니다. 전체 분석·전체 원문을 뜻하지 않습니다.'
        return info

def collect_json_secrets(value,found):
    if isinstance(value,dict):
        for key,item in value.items():
            if sensitive(key) and isinstance(item,str) and len(item)>=4:found.add(item)
            else:collect_json_secrets(item,found)
    elif isinstance(value,list):
        for item in value:collect_json_secrets(item,found)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,default=REPO/'docs/reports/2026-11-01-bank-simple-1.4.21')
    parser.add_argument('--work',type=Path,default=DEFAULT_WORK)
    parser.add_argument('--replace',action='store_true',help='Replace this generated display snapshot, never the source corpus')
    args=parser.parse_args();report=args.report.resolve();work=args.work.resolve()
    output=report/'layout-view/safe-packets.json'
    if output.exists() and not args.replace:raise RuntimeError('Preserve an existing packet display snapshot or explicitly use --replace')
    datasets={
        'core':(report/'results',work/'native-runs/run-003/private-results'),
        'business':(report/'results/business',work/'business-runs/run-001/private-results')}
    sources={};forbidden={'admin123'}
    # Read task-local credentials only in memory for blind removal; never serialize them.
    state=json.loads((work/'runtime/private-state.json').read_text())
    forbidden.add(state['password']);forbidden.update(x['token'] for x in state['actors'])
    originals_verified=0
    for dataset,(metadata,private) in datasets.items():
        transport=json.loads((metadata/'transport-index.json').read_text())
        originals=json.loads((metadata/'original-id-index.json').read_text());by_digest=defaultdict(list)
        for original in originals:
            for side in ['request','response']:
                raw=(private/original['private'+side.title()+'File']).read_bytes()
                assert sha(raw)==original[side+'SHA256']
                assert len(raw)==original[side+'Bytes']
            by_digest[(original['requestSHA256'],original['responseSHA256'])].append(original['originalId']);originals_verified+=1
        loaded=[]
        for row in transport:
            request=(private/row['privateRequestFile']).read_bytes();response=(private/row['privateResponseFile']).read_bytes()
            assert sha(request)==row['requestSHA256'] and sha(response)==row['responseSHA256']
            r_start,r_headers,r_body=split_http(request);s_start,s_headers,s_body=split_http(response)
            actual=(private/'transport'/f"{row['index']:04d}.body.bin").read_bytes()
            assert s_body==actual and sha(actual)==row['bodySHA256'] and len(actual)==row['bodyBytes']
            assert r_start.split(' ',1)[0]==row['method'] and int(s_start.split()[1])==row['status']
            for text in [request.decode('utf-8','replace'),response.decode('utf-8','replace')]:forbidden.update(JWT.findall(text))
            for body in [r_body,s_body]:
                try:collect_json_secrets(json.loads(body),forbidden)
                except (ValueError,UnicodeDecodeError):pass
            loaded.append((row,r_start,r_headers,r_body,s_start,s_headers,s_body))
        sources[dataset]=(loaded,by_digest)
    clean=Sanitizer(forbidden);result={}
    for dataset,(loaded,by_digest) in sources.items():
        rows=[]
        for row,r_start,r_headers,r_body,s_start,s_headers,s_body in loaded:
            ids=by_digest[(row['requestSHA256'],row['responseSHA256'])];assert ids
            uri=clean.url(row['url']);request_headers,request_omitted=clean.headers(r_headers)
            response_headers,response_omitted=clean.headers(s_headers,True)
            request={'startLine':clean.text(r_start),'headers':request_headers,'omittedHeaderNames':request_omitted,
                     'contentType':content_type(r_headers),**clean.body(r_body,content_type(r_headers),row['url'],True)}
            response={'startLine':clean.text(s_start),'headers':response_headers,'omittedHeaderNames':response_omitted,
                      'contentType':content_type(s_headers),**clean.body(s_body,content_type(s_headers),row['url'])}
            for item in [request,response]:
                item['safeText']=item['startLine']+'\n'+'\n'.join(h['name']+': '+h['value'] for h in item['headers'])+'\n\n'+item['bodyText']
                item['safeDisplaySha256']=sha(item['safeText'].encode())
            actor,auth_present=actor_from_headers(r_headers)
            rows.append({'dataset':dataset,'index':row['index'],'originalId':ids[0],'originalIds':ids,
                         'url':uri,'method':row['method'],'status':row['status'],
                         'actor':actor,'authPresent':auth_present,
                         'request':request,'response':response,
                         'provenance':{'sourceRequestSha256':row['requestSHA256'],'sourceResponseSha256':row['responseSHA256'],
                                       'sourceBodySha256':row['bodySHA256'],'sourceBodyBytes':row['bodyBytes'],
                                       'originalFingerprintsVerified':True,'displayTextIsRedactedDerivedRepresentation':True}})
        result[dataset]=rows
    result['schemaVersion']=1
    result['scope']='Saved actual core003 and business001 HTTP only; safe derived display text, not exact HTTP wire bytes.'
    result['newHttpRequests']=0
    result['redaction']={'mask':MASK,'sensitiveHeaderValuesMasked':True,'nonAllowlistedHeadersOmitted':True,
                         'sensitiveJsonValuesMaskedRecursively':True,'jwtAndKnownLocalCredentialsMasked':True,
                         'uriUserinfoMasked':True,'textSensitiveAssignmentsUseWholeKeyClassification':True,
                         'adminHtmlBodiesOmitted':True,'binaryBodiesOmitted':True,'textExcerptLimit':4000,
                         'jsonBodiesRemainStructuredAfterRedaction':True}
    result['validation']={'sourceTransportRecords':94,'sourceOriginalRecordsFingerprintVerified':originals_verified,
                           'coreRecords':len(result['core']),'businessRecords':len(result['business']),
                           'actorIdentification':'from original request token claim only, not signature validation',
                           'allSourceBytesAndBodiesMatchPublicFingerprints':True,
                           'displayContainsKnownCredentials':False,'displayContainsJwtPattern':False}
    assert len(result['core'])==89 and len(result['business'])==5 and originals_verified==140
    serialized=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    assert not JWT.search(serialized)
    assert not any(secret in serialized for secret in forbidden if len(secret)>=4)
    for dataset in datasets:
        for row in result[dataset]:
            for side in ['request','response']:
                packet=row[side]
                for header in packet['headers']:
                    if header['name'].lower() in SENSITIVE_HEADERS:assert header['value']==MASK
                if packet['bodyKind'] in ['html','javascript','text']:assert len(packet['bodyText'])<=4000
                if is_admin_html(row['url'],packet['contentType']):assert packet['omitted'] and not packet['bodyText']
    output.parent.mkdir(parents=True,exist_ok=True)
    temporary=output.with_suffix('.json.building');temporary.write_text(serialized);temporary.replace(output)
    print(json.dumps({'result':'passed','coreRecords':89,'businessRecords':5,'sourceOriginalsVerified':originals_verified,
                      'outputBytes':output.stat().st_size,'outputSha256':sha(output.read_bytes()),
                      'newHttpRequests':0,'knownCredentialsAndJwtInDisplay':0},ensure_ascii=False))

if __name__=='__main__':main()
