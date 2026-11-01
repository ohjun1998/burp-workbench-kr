#!/usr/bin/env python3
"""Present saved real-bank results; never sends requests or invents engine findings."""
import argparse,csv,html,json,re
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--report',type=Path,required=True)
    a=p.parse_args();root=a.report.resolve()
    def load(name):return json.loads((root/name).read_text(encoding='utf-8'))
    core=load('results/results.json');traffic=load('results/traffic.json')
    mapper=load('results/mapper-final.json');js=load('results/js-analysis-final.json')
    auth=load('results/auth-comparisons.json');response=load('results/response-findings.json')
    paths=load('results/path-results.json');payload=load('results/payload-sql.json')
    activity=load('results/activity-final.json');scope=load('results/scope-inspection.json')
    dashboard=load('results/dashboard.json');diff=load('results/structured-comparison.json')
    jwt=load('results/jwt-analysis.json');evidence=load('results/evidence.json')
    business=load('results/business/business-negative-transfer.json');truth=load('truth/groundtruth.json')
    assert core['version']=='1.4.21' and core['failedChecks']==0 and len(core['checks'])==13
    assert core['jarSHA256']=='9d3d20af946add3498da79a021be31fafe8d84e046352463c4a559616187301c'
    assert business['result']=='PASS' and business['senderDelta']==5 and business['recipientDelta']==-5
    endpoint_rows=[e for source in js for e in source['endpoints']]
    unique_urls=sorted({e['url'] for e in endpoint_rows})
    sql_candidates=[r for r in payload['rows'] if 'SQL 조건 차이 후보' in str(r)]
    idor=next(r for r in auth if r['id']=='AUTH-IDOR-BALANCE')
    suspects=[c['actor'] for c in idor['cells'] if c['kind']=='SUSPECT']
    rows=[]
    def add(key,name,summary,values,note,files,kind='core',state='executed',image=None):
        rows.append(dict(id=key,name=name,summary=summary,values=values,interpretation=note,
                         evidence=files,kind=kind,state=state,image=image or key))
    add('traffic','요청·응답 목록',f"실제 HTTP {traffic['captured']}건 수집",
        [f"수신 {traffic['received']} · 처리 오류 {traffic['processingErrors']}",traffic['scope']],
        '실제 서버 body와 정규화한 HTTP 헤더를 사용했습니다. TCP 원시 프레이밍 캡처는 아닙니다.',
        ['results/traffic.json','results/transport-index.json','results/original-id-index.json'])
    add('mapper','기능 자동 매핑',f"관측 {mapper['packets']}항목 → {len(mapper['metadata']['features'])}개 기능 그룹",
        ['Check account balance','Admin panel','서비스·정적 자료·상태 확인 등 실제 관측을 묶음'],
        '같은 실제 응답을 일반 수집과 Scope handoff에서 다시 관측할 수 있습니다. 135항목은 독립 HTTP 요청 수가 아닙니다.',
        ['results/mapper-final.json','results/measurement-stages.json'])
    add('auth','계정 권한 비교',f"잔액 조회에서 우회 의심 {len(suspects)}개 인증 조건",
        [idor['url'],', '.join(suspects)+' → HTTP 200, 타인 계좌 표식 관측',
         '정상 대조: 관리자 화면 admin 200 / Alice·Bob 403 / 비로그인 401'],
        '제품의 SUSPECT 판정과 정책 대조 결과입니다. 독립 검증에서도 무인증 잔액 노출을 확인했습니다. 기존 기준 계정 캡션 문제는 별도 기록했습니다.',
        ['results/auth-comparisons.json','truth/groundtruth.json','product-observations.md'])
    counts={Path(s['url'].split('#')[0]).name:len(s['endpoints']) for s in js if s['url'].endswith(('/static/dashboard.js','/static/merchant.js','/static/openapi.json'))}
    add('js','JS에서 API 찾기',f"{len(js)}개 소스 · {len(endpoint_rows)}개 후보 행 · URL 문자열 {len(unique_urls)}개",
        [f"dashboard.js {counts['dashboard.js']} / merchant.js {counts['merchant.js']} / OpenAPI {counts['openapi.json']}",
         '/transfer · /request_loan · /api/virtual-cards 등의 경로 후보'],
        '중복·동적 경로와 미완성 후보를 포함합니다. URL 수는 호출 가능한 API 수가 아닙니다. dashboard.js는 AST 실패 후 보조 패턴 분석으로 19개를 찾았습니다.',
        ['results/js-analysis-final.json'])
    add('environment','서버·경로 탐색','Flask / Werkzeug · Swagger / OpenAPI · GraphQL 단서',
        [f"{r['url'].split(':24904')[-1]} → {r['status']} / {r['kind']}" for r in paths],
        '헤더·본문·응답 구조에 근거한 구성 추정입니다. /.env는 실제 HTTP 200의 실습용 환경 설정 노출입니다.',
        ['results/environment-final.json','results/path-results.json'])
    add('response.recon','응답 보안 분석',f"{len(response)}개 현재 분석 항목",
        sorted({r['rule'] for r in response}),
        'SQL 구문 오류·DB_PASSWORD 키·헤더 미관측은 검토 후보입니다. 일부 큰 JS 본문은 앞 1,048,576자만 분석해 범위 제한을 함께 남겼습니다.',
        ['results/response-findings.json','results/sql-error.json'])
    add('payload','입력값 보안 검사',f"5회 제한 실행 · SQL 조건 차이 후보 {len(sql_candidates)}개",
        ['기준 200 → 참 조건 200 → 거짓 조건 404','기준·거짓 조건을 각각 재검증',payload['status']],
        '제품은 WAF·일반 필터 가능성을 포함해 후보로 표시합니다. 독립 SQL 로그인 우회 확인과 같은 결과로 합치지 않습니다.',
        ['results/payload-sql.json','truth/groundtruth.json'])
    add('bounty','재현·증거 정리','순차 요청·변수 추출·증거 ZIP 및 음수 이체 재현',
        [f"별도 실제 workflow {business['actualHttpRequests']}단계 · 금액 {business['fakeMoneyAmount']}",
         f"Alice {business['senderBefore']:g} → {business['senderAfter']:g} / Bob {business['recipientBefore']:g} → {business['recipientAfter']:g}",
         f"가림 증거 ZIP {evidence['items']}개 생성 · 계정/업무 자동 연결은 미완료"],
        '제품 출력은 설정한 HTTP 기대 조건 일치입니다. 잔액 숫자 변화로 음수 이체의 업무 규칙 위반을 별도로 확인했습니다. 익명 자료의 자동 증거 연결 오류를 숨기지 않았습니다.',
        ['results/workflow.json','results/business/business-negative-transfer.json','results/redacted-evidence.zip','product-observations.md'],state='partial')
    add('data','데이터 변환·비교',f"실제 계좌 응답 변경 구간 {diff['changeCount']}개 · JWT 내용 해석",
        ['Alice·Bob의 실제 잔액/사용자명/계좌 응답 비교','실제 JWT: HS256 · is_admin=false · exp 없음','서명 검증은 수행하지 않음'],
        '변경 구간은 필드 수나 취약점 수가 아닙니다. JWT 디코딩 결과와 독립적인 서버 변조 수용 시험을 구분합니다.',
        ['results/structured-comparison.json','results/jwt-analysis.json','truth/groundtruth.json'],kind='support')
    add('activity','점검 진행 현황',f"관측·발견 경로 {activity['items']}항목 관리",
        ['관측과 미관측, 파라미터·재검토·수동 완료 상태','GET /는 하네스에서 직접 확인 후 완료 표시'],
        '후보/동적 경로가 포함되며 이 개수는 점검 완료 수가 아닙니다.',
        ['results/activity-final.json','results/activity.json'],kind='support')
    add('exposure','외부 노출·CVE 조사','검색식 생성 · 외부 조회 미실행',
        [load('results/exposure-research-final.json')['query'],'외부 공급자 API / Google / CVE 조회: NOT_RUN'],
        'CVE 0행은 안전하다는 판정이 아닙니다. 로컬 은행만 시험했으며 외부 조회는 하지 않았습니다.',
        ['results/exposure-research-final.json'],kind='support',state='not-run')
    add('scope','통합 점검',f"{scope['state']} · 전송 {scope['sent']} / 완료 응답 {scope['completed']}",
        [f"대기 {scope['pending']} / 제외 {scope['excluded']} / 실패 {scope['failed']} / 미확인 {scope['partial']}",
         f"관측·후보 finding 행 {len(scope['findings'])}개",scope['message']],
        '발견 대기열 처리가 끝났다는 뜻입니다. 525개는 확정 취약점 수가 아니고 미확인 10개를 포함합니다. native Katana는 초기 설정 제약으로 미실행입니다.',
        ['results/scope-inspection.json','limitations/katana/README_KR.md'],kind='support',state='partial')
    add('dashboard','대시보드',f"같은 실행 {dashboard['runId'][:8]}의 현재 결과 표시",
        [f"snapshot pages {dashboard['pages']} / finding 행 {dashboard['findings']}",'통합 점검과 동일 runId 확인'],
        '스냅샷 페이지, 완료 응답, 전송 횟수는 서로 다른 집계입니다. 대시보드 표시만으로 취약점이 확정되지 않습니다.',
        ['results/dashboard.json','results/scope-inspection.json'],kind='support')
    data={'version':'1.4.21','jarSha256':core['jarSHA256'],'coreChecks':len(core['checks']),
          'actualCoreHttpRequests':core['httpRequests'],'toolRows':rows,
          'independentConfirmedClasses':truth['confirmedVulnerabilities'],
          'normalControls':truth['normalControlsPassed'],'negativeTransferConfirmed':True,
          'countsOverlap':True,'nativeBurpGui':False,'syntheticTargetResponses':False}
    (root/'report-data.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    with (root/'tool-results.csv').open('w',newline='',encoding='utf-8-sig') as f:
        writer=csv.writer(f);writer.writerow(['도구','실행 범위','실제 결과','값','해석','근거'])
        for row in rows:writer.writerow([row['name'],row['state'],row['summary'],' / '.join(row['values']),row['interpretation'],' / '.join(row['evidence'])])
    e=html.escape
    cards=[]
    for row in rows:
        images=[]
        for theme in ['light','dark']:
            img=f"images/workbench/{theme}/{row['image']}.png"
            assert (root/img).is_file(),img
            images.append(img)
        links=' · '.join(f'<a href="{e(f)}">{e(Path(f).name)}</a>' for f in row['evidence'])
        values=''.join(f'<li>{e(v)}</li>' for v in row['values'])
        cards.append(f'''<article class="tool" data-kind="{row['kind']}" data-state="{row['state']}"><div class="eyebrow">{e(row['state'])}</div><h2>{e(row['name'])}</h2><p class="result">{e(row['summary'])}</p><ul>{values}</ul><p class="note">{e(row['interpretation'])}</p><p class="links">{links}</p><details><summary>실제 결과 화면</summary><a class="shotlink" href="{images[0]}"><img loading="lazy" data-light="{images[0]}" data-dark="{images[1]}" src="{images[0]}" alt="{e(row['name'])} 실제 실행 결과"></a></details></article>''')
    table=''.join(f'<tr><th>{e(r["name"])}</th><td>{e(r["summary"])}</td></tr>' for r in rows)
    template='''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>은행 실습 · Workbench Simple 1.4.21 결과</title><style>
    :root{color-scheme:light}*{box-sizing:border-box}body{margin:0;background:#f3f5f9;color:#17243b;font:16px/1.65 system-ui,sans-serif}main{max-width:1260px;margin:auto;padding:38px 24px}header{background:#17243b;color:white;padding:30px;border-radius:18px}h1{font-size:31px;margin:5px 0 15px}h2{font-size:21px;margin:5px 0}a{color:#075dbd}header a{color:#a7d4ff}.eyebrow{font-size:12px;font-weight:700;letter-spacing:.06em;color:#49718c}header .eyebrow{color:#a5d8d0}.lead{max-width:900px}.metrics{display:flex;flex-wrap:wrap;gap:12px;margin:20px 0}.metric{flex:1;min-width:210px;background:white;padding:20px;border:1px solid #dfe6ee;border-radius:12px}.metric strong{display:block;font-size:28px;color:#076e68}.toolbar{position:sticky;top:0;z-index:2;background:#f3f5f9ee;padding:14px 0;display:flex;gap:10px;flex-wrap:wrap}input,select,button{font:inherit;padding:9px 12px;border:1px solid #bdcad9;border-radius:8px;background:white}input{flex:1;min-width:210px}table{width:100%;border-collapse:collapse;background:white;font-size:14px}th,td{text-align:left;border-bottom:1px solid #dfe6ee;padding:12px 15px}th{width:240px}.tool{margin:18px 0;padding:25px;background:white;border:1px solid #dfe6ee;border-radius:13px}.result{font-weight:700;color:#076e68;margin:9px 0}.note{padding:13px 16px;background:#f5f7fb;border-left:3px solid #7b94ad;color:#42536c;font-size:14px}.links{font-size:13px;overflow-wrap:anywhere}img{display:block;max-width:100%;height:auto;margin:15px 0;border:1px solid #d6deea;border-radius:7px}summary{cursor:pointer;color:#075dbd;font-weight:600}.warning{background:#fff6df;border-left:3px solid #bd841a;padding:18px;margin:20px 0}footer{color:#52627b;font-size:13px;margin:25px 0}[hidden]{display:none!important}@media(max-width:650px){main{padding:18px 12px}header{padding:20px}h1{font-size:25px}th{width:35%}.tool{padding:18px}}
    </style><main><header><div class="eyebrow">로컬 VulnBank · 고정된 Simple 1.4.21 JAR</div><h1>도구별로 실제 얻은 결과</h1><p class="lead">실제 은행 앱의 HTTP 응답을 Workbench 엔진으로 처리했습니다. 자동 후보, 독립 재현으로 확인한 취약점, 정상 차단과 미실행을 구분합니다. 합성 응답·가짜 결과를 넣지 않았으며 실제 Burp 본체 GUI 자동화는 아닙니다.</p><a href="README_KR.md">검증·설치 안내</a> · <a href="tool-results.csv">도구별 CSV</a> · <a href="report-data.json">값 JSON</a> · <a href="downloads/workbench-simple-1.4.21-bank-site.zip">은행 사이트 ZIP</a></header>
    <div class="metrics"><div class="metric">제품 기능 확인<strong>13 시나리오</strong>최종 한 세션 · HTTP 89회</div><div class="metric">독립 재현<strong>3종 + 음수 이체</strong>SQL 로그인 우회 · 잔액 노출 · JWT 변조</div><div class="metric">정상 대조<strong>2개 통과</strong>관리자 역할 차단 · 음수 대출 거절</div></div>
    <p>은행 브라우저 화면은 진단 전 잔액 1000·거래 0개 상태입니다. 이후 가짜 금액 -5 이체를 재현해 Alice는1005, Bob은995가 되었고, Workbench 최종 화면은 그 상태에서 얻었습니다. 서로 다른 단계의 수치는 합산하지 않습니다.</p>
    <table><tbody>{{TABLE}}</tbody></table><div class="toolbar"><input id="search" aria-label="도구와 결과 검색" placeholder="도구·결과 검색"><select id="filter" aria-label="표시 범위"><option value="all">모든 도구</option><option value="core">주요 8개 도구</option><option value="partial">제한·미실행 포함 항목</option></select><button id="theme" type="button">다크 화면 보기</button></div><div id="cards">{{CARDS}}</div>
    <div class="warning"><strong>발견한 제품 한계</strong><p>기준 계정이 첫 계정이 아닐 때 응답 비교 캡션이 첫 계정 이름으로 표시되는 문제와, 계정·업무에 연결되지 않은 익명 증거의 자동 연결 실패를 기록했습니다. 원본 데이터와 ZIP 저장은 유지되며 캡처는 올바른 기존 차이·판정 탭을 사용합니다. <a href="product-observations.md">재현 조건과 영향</a></p></div>
    <footer>시험 환경: Linux Xorg/xfwm4 실제 Swing · Chromium 실제 은행 웹 UI · Montoya 호환 HTTP 어댑터 · PGlite PostgreSQL18.6 · 원본 Flask 앱. Windows Burp·공식 PostgreSQL13·동시성·실제 AI·외부 CVE/Google·브라우저 보조 확장 연동은 미검증입니다. Native Katana는 읽기 전용 초기 설정 제약으로 미실행입니다. <a href="limitations/katana/README_KR.md">근거</a></footer></main>
    <script>let dark=false;const tools=[...document.querySelectorAll('.tool')];function update(){const q=document.getElementById('search').value.toLowerCase(),f=document.getElementById('filter').value;tools.forEach(x=>x.hidden=!x.textContent.toLowerCase().includes(q)||(f==='core'&&x.dataset.kind!=='core')||(f==='partial'&&!['partial','not-run'].includes(x.dataset.state)));}document.getElementById('search').addEventListener('input',update);document.getElementById('filter').addEventListener('change',update);document.getElementById('theme').addEventListener('click',e=>{dark=!dark;document.querySelectorAll('img[data-light]').forEach(x=>{x.src=dark?x.dataset.dark:x.dataset.light;x.closest('a').href=x.src});e.target.textContent=dark?'라이트 화면 보기':'다크 화면 보기';});</script></html>'''
    (root/'RESULTS.html').write_text(template.replace('{{TABLE}}',table).replace('{{CARDS}}','\n'.join(cards)))
    table_md='\n'.join('| '+r['name']+' | '+r['summary']+' | '+' / '.join(r['values'][:2]).replace('|',' / ')+' |' for r in rows)
    (root/'TOOL_RESULTS_KR.md').write_text('# Workbench Simple 1.4.21 · 은행 실습 도구별 실제 값\n\n[그림·검색·상세 결과](RESULTS.html) · [CSV](tool-results.csv) · [원본 값 JSON](report-data.json)\n\n| 도구 | 실제 출력 | 값·예시 |\n|---|---|---|\n'+table_md+'\n\n각 도구의 관측 항목은 중복됩니다. 후보 수를 취약점 수나 독립 HTTP 수로 합산하지 않습니다. 상세 의미와 원본 ID·응답 지문은 각 JSON을 확인하세요.\n')
    print(json.dumps({'toolRows':len(rows),'coreRows':sum(r['kind']=='core' for r in rows),'uniqueCandidateUrlStrings':len(unique_urls),'renderedHtml':'RESULTS.html'}))

if __name__=='__main__':main()
