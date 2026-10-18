package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

/** Pure response classification. Transport, session and baseline guards precede this stage. */
final class AuthComparison {
  static AuthFinding assess(AuthComparePanel.Run run,int slot,HttpRequest request,HttpResponse response,boolean exists)throws Exception{
    var result=assessResponse(run,slot,request,response,exists);
    if(!Core.readMethod(request.method())&&(result.verdict()==AuthVerdict.SUSPECT||result.kind()==AuthFinding.Kind.ALLOWED))return new AuthFinding(AuthFinding.Kind.WRITE_UNVERIFIED,"쓰기 응답만으로 처리 성공 미확정 · 전후 조회 필요",result.changes());
    return result;
  }
  private static AuthFinding assessResponse(AuthComparePanel.Run run,int slot,HttpRequest request,HttpResponse response,boolean exists)throws Exception{
    var c=run.context;var baseline=run.baseline();
    if(c.largeFiles()&&(baseline.toByteArray().length()>AuthComparePanel.MESSAGE_LIMIT||response.toByteArray().length()>AuthComparePanel.MESSAGE_LIMIT)){
      if(!c.rules().important().isEmpty())return new AuthFinding(AuthFinding.Kind.INCOMPLETE,"대용량 본문에서 중요 JSON 필드 검증 불가 · 본문 수동 확인","");
      if(!c.responseRules().rules.isEmpty())return new AuthFinding(AuthFinding.Kind.REVIEW,"대용량 응답 차단 규칙 검사 미지원 · 해시/본문 수동 확인","");
      var access=c.policy().forRequest(run.original.method(),run.original.url());var expected=c.matrix().expected(request.method(),request.url(),slot);
      AuthFinding.Kind kind=response.statusCode()==401||response.statusCode()==403?AuthFinding.Kind.BLOCKED:AuthFinding.Kind.REVIEW;
      String note=AuthFileEvidence.note(baseline,response);
      if(baseline.statusCode()>=200&&baseline.statusCode()<300&&response.statusCode()>=200&&response.statusCode()<300&&baseline.body().length()>0&&baseline.body().length()==response.body().length()&&baseline.statusCode()!=206&&response.statusCode()!=206&&baseline.headerValue("Content-Range")==null&&response.headerValue("Content-Range")==null&&AuthFileEvidence.digest(baseline).equals(AuthFileEvidence.digest(response)))kind=AuthFinding.Kind.SUSPECT;
      if(expected==BountyMatrix.Expected.ALLOW&&access==AuthPolicy.Access.OWNER_ONLY)return new AuthFinding(AuthFinding.Kind.CONFLICT,"기대 ALLOW와 소유자 전용 정책 충돌","");
      if(kind==AuthFinding.Kind.BLOCKED&&expected==BountyMatrix.Expected.ALLOW)kind=AuthFinding.Kind.CONFLICT;
      if(kind==AuthFinding.Kind.SUSPECT){if(expected==BountyMatrix.Expected.ALLOW||access==AuthPolicy.Access.PUBLIC)kind=AuthFinding.Kind.ALLOWED;else if(expected==BountyMatrix.Expected.UNKNOWN&&access==AuthPolicy.Access.UNSPECIFIED)kind=AuthFinding.Kind.REVIEW;}
      return new AuthFinding(kind,note,"원본 "+baseline.body().length()+" bytes · SHA-256 "+AuthFileEvidence.digest(baseline)+"\n대상 "+response.body().length()+" bytes · SHA-256 "+AuthFileEvidence.digest(response));
    }
    String original=JsAnalysisPanel.readBody(baseline),body=JsAnalysisPanel.readBody(response);
    var access=c.policy().forRequest(run.original.method(),run.original.url());
    var rules=c.rules().withPolicy(access);
    var result=AuthJudgement.compare(baseline.statusCode(),original,response.statusCode(),body,rules,c.owners()[slot],exists);
    var fingerprint=c.responseRules().assess(baseline.statusCode(),AuthComparePanel.responseHeaders(baseline),original,response.statusCode(),AuthComparePanel.responseHeaders(response),body);
    var expected=c.matrix().expected(request.method(),request.url(),slot);
    if(fingerprint!=null&&result.kind()!=AuthFinding.Kind.BASELINE&&result.kind()!=AuthFinding.Kind.DENIAL_DATA){
      if(fingerprint.kind()==AuthFinding.Kind.BLOCKED&&expected==BountyMatrix.Expected.ALLOW)return new AuthFinding(AuthFinding.Kind.CONFLICT,"기대 ALLOW · 차단 관측 · 세션/정책 재검토 · "+fingerprint.reason(),result.changes());
      result=new AuthJudgement.Result(fingerprint.kind(),fingerprint.reason(),result.changes());
    }
    if(result.kind()==AuthFinding.Kind.BASELINE||result.kind()==AuthFinding.Kind.INCOMPLETE||result.kind()==AuthFinding.Kind.DENIAL_DATA)return result.finding();
    if(expected!=BountyMatrix.Expected.UNKNOWN){
      if(expected==BountyMatrix.Expected.ALLOW&&access==AuthPolicy.Access.OWNER_ONLY)return new AuthFinding(AuthFinding.Kind.CONFLICT,"기대 ALLOW와 소유자 전용 정책 충돌 · 설정 재검토",result.changes());
      if(result.kind()==AuthFinding.Kind.BLOCKED)return new AuthFinding(expected==BountyMatrix.Expected.DENY?AuthFinding.Kind.BLOCKED:AuthFinding.Kind.CONFLICT,"기대 "+expected+" · "+result.reason(),result.changes());
      if(response.statusCode()==404||response.statusCode()==410)return new AuthFinding(exists&&expected==BountyMatrix.Expected.DENY?AuthFinding.Kind.BLOCKED:AuthFinding.Kind.REVIEW,"기대 "+expected+" · 404/410 · "+(exists?"소유자 재조회 정상":"존재·세션 재검토"),result.changes());
      if(response.statusCode()>=200&&response.statusCode()<300&&!body.isBlank())return new AuthFinding(expected==BountyMatrix.Expected.DENY?AuthFinding.Kind.SUSPECT:AuthFinding.Kind.ALLOWED,"기대 "+expected+" · 성공 응답 관측 · 실제 데이터/처리 결과 확인",result.changes());
    }
    if(result.kind()==AuthFinding.Kind.SUSPECT&&access==AuthPolicy.Access.UNSPECIFIED)return new AuthFinding(AuthFinding.Kind.REVIEW,"접근 정책 미설정 · 동일 응답의 공개/공유 허용 여부 확인",result.changes());
    return result.finding();
  }
}
