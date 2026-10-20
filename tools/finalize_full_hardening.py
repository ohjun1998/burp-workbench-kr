#!/usr/bin/env python3
"""Assemble 0.49.0 verification from retained evidence; never manufactures test results."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
WB = ROOT / 'project/burp-development-workspace/burp-workbench-kr/burp-workbench'
OUT = ROOT / 'docs/verification/full-0.49.0'


def read(path):
    return json.loads((ROOT / path).read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--recheck-log', required=True, type=Path)
    args = parser.parse_args()
    log = args.recheck_log.read_text()
    assert 'WORKBENCH_CHECK_SUMMARY' in log and '"result": "passed"' in log
    check = json.loads((WB/'build/check-summary.json').read_text())
    assert check['version'] == '0.49.0' and check['result'] == 'passed'
    jar = ROOT/'downloads/extensions/0.49.0/burp-workbench-kr-0.49.0.jar'
    jar_hash = digest(jar)
    assert digest(WB/'dist'/jar.name) == jar_hash
    ui = read('docs/verification/full-0.49.0/ui-workbench-summary.json')
    assert ui['releaseJarSha256'] == jar_hash
    auth = read('tmp/auth-lab-049/summary.json')
    auth50 = read('tmp/auth50-049/summary.json')
    assert auth['extensionJarSha256'] == auth50['extensionJarSha256'] == jar_hash
    analysis = read('tmp/auth50-analysis-049/analysis.json')
    assert analysis['result'] == 'passed'
    mapper_text = (ROOT/'feature-mapper/build/test-results.txt').read_text()
    mapper_count = int(re.search(r'MapperTest: (\d+) checks passed', mapper_text).group(1))
    OUT.mkdir(parents=True, exist_ok=True)
    copies = {
        args.recheck_log: 'resumed-check.log',
        WB/'build/check-summary.json': 'resumed-check-summary.json',
        WB/'validation/all-features/.run/results.json': 'resumed-all-features-http.json',
        ROOT/'audit/0.23.0/.run/results.json': 'resumed-bounty-http.json',
        ROOT/'feature-mapper/build/test-results.txt': 'mapper-tests.txt',
        ROOT/'tmp/role-portals-049/results.json': 'role-portals.json',
        ROOT/'tmp/auth-lab-049/summary.json': 'auth-lab-summary.json',
        ROOT/'tmp/auth50-049/summary.json': 'auth50-summary.json',
        ROOT/'tmp/auth50-analysis-049/analysis.json': 'auth50-analysis.json',
        ROOT/'tmp/auth50-analysis-049/account-results.csv': 'auth50-account-results.csv',
        ROOT/'tmp/auth50-analysis-049/classification-reasons.csv': 'auth50-classification-reasons.csv',
        ROOT/'tmp/auth50-analysis-049/review-reasons.csv': 'auth50-review-reasons.csv',
        ROOT/'tmp/ui-049-mapper/layout-report.json': 'ui-mapper-summary.json',
    }
    for source, name in copies.items():
        shutil.copyfile(source, OUT/name)
    screenshots = []
    for name, title in [('tool-10','기능 자동 매핑'), ('tool-08','입력값 보안 검사'), ('bounty-3','재현·증거 정리')]:
        source = ROOT/f'tmp/ui-049-base/light/1280x800/{name}.png'
        target = OUT/f'{name}.png'
        shutil.copyfile(source, target)
        screenshots.append({'path': str(target.relative_to(ROOT)), 'caption': title+' · 최종 JAR 운영 Swing 렌더 · 합성 입력/API 어댑터'})
    remaining = [
        {'priority':'P1 · 검증', 'scope':'실제 Burp 11개 도구 연결 및 기존 7개 사이트 전후 재측정', 'acceptance':'허용된 실행 환경에서 최종 JAR 로드, 대상 소스·요청 조건 고정, 도구별 패킷·판정·정탐/오탐/미탐/보류 기록'},
        {'priority':'P1 · 사용성', 'scope':'계정 비교의 권한표·쓰기 조회 설정 의존성', 'acceptance':'설정 공백을 시작 전에 명확히 안내하고, 사용자 확인을 거친 정책 설정 흐름을 제공. 현재 표본의 미설정 미탐 392건과 설정 후 보류 91건을 별도로 추적'},
        {'priority':'P1 · 검증', 'scope':'Chrome/Edge MV3의 실제 도우미 연결·클릭 귀속', 'acceptance':'도우미 설치→인증→클릭→요청 메서드/본문 대조→중복·재시작·해제까지 실제 브라우저 검사'},
        {'priority':'P2 · 분석', 'scope':'JS의 복잡한 객체·별칭·동적 호출 및 한도를 넘는 응답', 'acceptance':'정상/오탐 대조를 갖춘 지원 범위 확대와 시간·메모리 예산 유지. 지원하지 않는 구간은 계속 미확정으로 표시'},
        {'priority':'P2 · 검증', 'scope':'Windows DPI/IME·장시간 사용·실제 디스크 부족 복구', 'acceptance':'Windows 화면 조작 및 시간·OS를 명시한 수집/복구 검사. 현재 Linux 약 3분의 어댑터 결과를 장시간 실사용으로 확대하지 않음'},
        {'priority':'P2 · 판정', 'scope':'401/403 원인과 저장·리다이렉트의 실제 효과', 'acceptance':'세션·권한·CSRF·업무 오류를 대상별 근거로 분리. 상태 코드만으로 판단하지 않고 조회·브라우저 실행·서버 상태를 확인'},
        {'priority':'P3 · 연동', 'scope':'외부 AI·OAST·BApp, Docker 스택 및 Microsoft eShop', 'acceptance':'실제 의존 환경에서 검증. 모의 콜백과 원본 소스 준비를 실연동 완료로 계산하지 않음'},
        {'priority':'P3 · 증거', 'scope':'사용자 첨부 이미지와 알려지지 않은 개인정보 가림', 'acceptance':'내보내기 미리보기와 수동 확인 유지. 자동 가림이 원본의 모든 개인정보를 제거한다고 보장하지 않음'},
    ]
    review = [
        ('같은 클릭·밀리초·URL의 다른 메서드/본문 이벤트가 덮어써질 수 있음','이벤트 키에 메서드와 본문 해시를 포함하고 모호한 매핑은 취소'),
        ('worker 초기화 전에 받은 클릭과 이전 연결 오류가 새 연결에 섞임','설정 초기화를 기다리고 성공 시 오류 상태 초기화, 종료된 탭의 세션 정보 정리'),
        ('변경·별칭·조건부 함수의 JS 값을 확정하거나 요청 원본 위치가 흐려짐','보수적인 정적 문맥 검사와 미확정 처리, 요청 객체의 호출 위치·메서드 보존'),
        ('신원 확인과 기준 요청 사이 간격 누락, 큰 익명 응답을 음성 대조로 오인','모든 경계에 간격 적용. 불완전·미지원·동일 신원 익명 응답은 검사값 미전송'),
        ('큰 본문의 미검사 범위가 전체 무근거 판정으로 오해됨','일반 문자열/JSON/수신 한도를 분리하고 미검사·큐 제외·처리 오류를 표시'),
        ('숫자 비밀값 가림 후 JSON이 깨지거나 공개 쿠키 속성이 가려짐','구조를 보존하는 가림과 쿠키 속성 구분. 재검증에서 두 JSON 유효 및 공개 도메인 보존'),
        ('이스케이프된 비밀값이 coverage/OAST 증거에 남음','민감값의 표현을 정규화하여 가림. 두 저장 경로에서 잔류 없음 확인'),
        ('증거 저장과 종료가 겹치거나 미리보기 비용이 과도함','종료 경합 회귀 수정. 동일 72,912바이트 입력 관찰에서 1,748ms→198ms; 일반 성능 보장 수치 아님'),
        ('URL 대표군이 메서드·쿼리와 발견 출처를 잃을 수 있음','관측/정적/아카이브/수동과 GET 확인 기록 분리, 경로·쿼리·중복 키 보존'),
    ]
    v = {
        'version':'0.49.0','mapperVersion':'0.3.3','labVersion':'3.10.0','date':'2026-10-20','timezone':'Asia/Seoul',
        'sourceRef':'account-a/full-hardening-20261020',
        'baseCommit':'ee1ac6a1f36b6b22c946aa03728e93e274b3057d',
        'githubStatus':'검증한 작업 브랜치의 게시·PR 상태는 PROJECT_STATUS_KR.md와 연결한 PR에서 확인합니다.',
        'result':'AUTOMATED_CHECKS_PASSED_FULL_NATIVE_AND_MULTISITE_VALIDATION_INCOMPLETE',
        'releaseJarSha256':jar_hash,'releaseCheck':check,
        'mapper':{'javaChecks':mapper_count,'nodeChecks':8,'nativeBrowserLocal':'BLOCKED_SOCKET_PERMISSION'},
        'ui':{'totalViews':sum(x['renderedViews'] for x in ui['results']),'mapperViews':30,'releaseJarSha256':jar_hash,'method':ui['method'],'results':ui['results']},
        'authLab':auth,'auth50':auth50,'auth50Metrics':analysis['metrics'],
        'metricInterpretation':'auth50는 0.49.0 최종 JAR의 앞선 실행 자료를 이번 재개에서 재집계했다. API 어댑터이며 native Burp가 아니다. REVIEW는 TP/FP/TN/FN과 중복될 수 있어 합산하지 않는다. 미설정 392 FN 중 385는 보류이며 7건은 차단 응답 뒤 실제 저장 변화가 있었다. 설정 후 REVIEW 91건은 302 7건, 404 63건, 410 21건이며 위반 사례와 겹치지 않는다.',
        'reviewFixes':[{'problem':a,'resolution':b} for a,b in review],
        'verificationRows':[
            {'name':'전체 회귀·격리 HTTP 재실행','result':'통과','detail':f"{check['regressionGroups']}개 회귀 묶음 {check['regressionChecks']:,} + 페이로드 HTTP {check['payloadHttpChecks']} + 전체 기능 HTTP {check['allFeaturesHttpChecks']} + 업무 HTTP {check['bountyHttpChecks']}. 합계 {check['totalChecks']:,}."},
            {'name':'Mapper Java / Node','result':'통과','detail':f'{mapper_count}개 / 8개. 실제 Chrome 실행과 별도인 격리 검사.'},
            {'name':'50개 업무 기능 계정 비교','result':'앞선 동일 JAR 실행·이번 재집계 통과','detail':'HTTP 2,800개·비교 1,400기록·계정 대조 4,200개. 설정 후 2,100개 대조에서 TP392·FP0·TN1708·FN0, 보류91(중복).'},
            {'name':'역할·인증 경계','result':'앞선 동일 버전 실행 통과','detail':'역할 HTTP384개. 인증 조합 HTTP420개 및 운영 컨트롤러 42회·126계정 대조. native Burp 아님.'},
            {'name':'화면 배치','result':'앞선 최종 JAR 렌더 통과','detail':'Workbench420개·Mapper30개 화면, 확인 대상 잘림·핵심 작업 영역 붕괴0. 실제 데스크톱 DPI/IME 아님.'},
            {'name':'Linux 연속 수집','result':'앞선 180초 검사 통과','detail':'3,632건·누락0·처리 오류0. API 어댑터와 실제 Swing 타이머·디스크 저장; 장시간 실사용은 별도.'},
            {'name':'배포 무결성','result':'통과','detail':'현재 소스 지문과 다운로드 일치. 같은 Java17로 소스 ZIP을 새 폴더에서 재빌드한 JAR 바이트 일치.'},
            {'name':'로컬 실제 Chrome / 실제 Burp','result':'미완료','detail':'Chrome 시작의 socket 권한 거부. 이전 Burp 시도는 라이선스 안내에서 중단했으며 최종 JAR와 해시가 달라 최종 버전의 근거에서 제외.'},
            {'name':'기존 7개 외부 실습 사이트 전체 재측정','result':'미수행','detail':'이전 단계에서 추가 취약점 재현이 실행 보안 검사에 차단됨. 준비된 소스/아카이브를 결과로 세지 않음. Docker/Podman 부재.'},
        ],
        'remaining':remaining,'screenshots':screenshots,
        'artifacts':[{'name':p.name,'path':str(p.relative_to(ROOT)),'sha256':digest(p)} for p in [jar,jar.with_name('burp-workbench-kr-0.49.0-source.zip'),ROOT/'downloads/feature-mapper/0.3.3/feature-mapper-browser-0.3.3.zip']],
        'historicalNativeAttempt':read('docs/verification/full-0.49.0/native-attempt.json'),
        'historicalNativeAttemptMatchesFinalJar':read('docs/verification/full-0.49.0/native-attempt.json')['workbenchSha256']==jar_hash,
        'historicalResultsNotRecounted':['0.48.0 Benchmark XSS 24 cases','0.48.0 OopsSec JSON 4 cases','0.48.0 Juice Shop/DVBank/native Burp observations'],
        'commands':{'resumedCheck':'python3 dev.py check --port 18109 --bounty-port 18409','auth50Aggregation':'python3 tools/verify_auth50_results.py --run-dir tmp/auth50-049 --out tmp/auth50-analysis-049','downloadVerification':'python3 tools/package_downloads.py --verify'},
        'evidenceFiles':[{'path':str(p.relative_to(ROOT)),'sha256':digest(p)} for p in sorted(OUT.glob('*')) if p.is_file()],
    }
    target=ROOT/'docs/VERIFICATION_0.49.0.json'
    if target.exists():
        previous=json.loads(target.read_text())
        for key in ['githubStatus','githubPublished','githubDestination','pullRequestCreated','localImplementationCommit','remoteVerification']:
            if key in previous:
                v[key]=previous[key]
    target.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'result':v['result'],'checks':check['totalChecks'],'jar':jar_hash,'uiViews':v['ui']['totalViews']},ensure_ascii=False))


if __name__ == '__main__':
    main()
