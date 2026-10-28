package kr.workbench;

import java.nio.file.*;
import java.util.*;

/** Semantic result filters and noise-aware masked history retain discovery meaning. */
public final class ScopeInspectionViewTest extends WorkbenchTest {
  static ScopeInspection.Finding finding(String category,String title,String state,String detail,String fingerprint) {
    return new ScopeInspection.Finding(category,title,"https://view.test/app/data","GET","선택 계정","관측","중간",state,detail,"source-1",2,12,fingerprint);
  }
  static ScopeInspection.Snapshot snapshot(String id,List<ScopeInspection.Finding> findings) {
    return new ScopeInspection.Snapshot(id,ScopeInspection.State.COMPLETED,"",0,0,0,0,0,0,findings,List.of(),List.of(),1,2);
  }
  public static void main(String[] args) throws Exception {
    var secret=finding("SECRET","api_key","유효성 미검증","[가림] · 분류: SECRET_CANDIDATE","a".repeat(64));
    var personal=finding("SENSITIVE","phone","관측 값·공개 범위 미확인","[가림] · 분류: PERSONAL_DATA","b".repeat(64));
    var example=finding("SECRET","fakeToken","예제값 가능","[가림] · 분류: EXAMPLE_OR_PLACEHOLDER","c".repeat(64));
    var publicId=finding("SECRET","client_id","공개 식별자 가능","[가림] · 분류: PUBLIC_IDENTIFIER","d".repeat(64));
    var dynamic=finding("API","API 템플릿","동적 미해결","분류: DYNAMIC_UNRESOLVED · 문자열 관측·도달 별도","e".repeat(64));
    var declared=finding("API","API 문서 선언","문서 선언","OpenAPI 명세 · 실제 구현 미검증","f".repeat(64));
    var cookie=finding("SECRET","BenchmarkTest00077","쿠키 값·인증 용도 미확인","[가림] · 분류: COOKIE_VALUE","3".repeat(64));
    var fragment=finding("API","정적 경로 조각","정적 경로 조각·서버 미확정","분류: STATIC_FRAGMENT_UNRESOLVED · 문자열 관측·도달 별도","4".repeat(64));
    check(ScopeInspectionView.matches(ScopeInspectionView.Filter.IMPORTANT,secret),"unverified secret candidate stays visible as an important observation");
    check(ScopeInspectionView.matches(ScopeInspectionView.Filter.IMPORTANT,personal),"personal data is included in key-information view");
    check(!ScopeInspectionView.matches(ScopeInspectionView.Filter.IMPORTANT,example)&&!ScopeInspectionView.matches(ScopeInspectionView.Filter.IMPORTANT,publicId),"examples and public identifiers are separated from primary secret candidates");
    check(ScopeInspectionView.matches(ScopeInspectionView.Filter.EXAMPLE,example)&&ScopeInspectionView.matches(ScopeInspectionView.Filter.EXAMPLE,publicId),"example/public filter uses explicit classification rather than entropy guesses");
    check(ScopeInspectionView.matches(ScopeInspectionView.Filter.UNRESOLVED,dynamic),"dynamic API remains explicitly unresolved");
    check(!ScopeInspectionView.matches(ScopeInspectionView.Filter.UNRESOLVED,secret),"credential validity unverified does not mean dynamic URL resolution");
    check(ScopeInspectionView.matches(ScopeInspectionView.Filter.DECLARED,dynamic)&&ScopeInspectionView.matches(ScopeInspectionView.Filter.DECLARED,declared),"declarations and templates are available without claiming endpoint reachability");
    check(ScopeInspectionView.matches(ScopeInspectionView.Filter.EVIDENCE,declared),"response evidence availability is distinct from literal endpoint success");
    check(ScopeInspectionView.meaning(dynamic).contains("대상 요청 성공 아님")&&ScopeInspectionView.meaning(declared).contains("접근 여부"),"detail guidance distinguishes declaration, request and response");
    check(ScopeInspectionView.search(personal,"SOURCE-1")&&ScopeInspectionView.search(personal,"PHONE"),"search matches masked metadata and immutable original IDs case-insensitively");
    check(ScopeInspectionView.meaning(personal).contains("비인가 노출"),"own-account personal observation does not assert unauthorized disclosure");
    check(!ScopeInspectionView.matches(ScopeInspectionView.Filter.IMPORTANT,cookie)&&ScopeInspectionView.matches(ScopeInspectionView.Filter.METADATA,cookie),"arbitrary response cookies remain metadata rather than promoted secret-token discoveries");
    check(ScopeInspectionView.meaning(cookie).contains("유효 토큰 유출 확정 아님"),"cookie observation guidance states unverified authentication purpose");
    check(ScopeInspectionView.matches(ScopeInspectionView.Filter.UNRESOLVED,fragment)&&ScopeInspectionView.matches(ScopeInspectionView.Filter.DECLARED,fragment),"static API fragments remain visible as both declared strings and unresolved server context");
    check(ScopeInspectionView.meaning(fragment).contains("서버")&&ScopeInspectionView.meaning(fragment).contains("대상 요청 성공 아님"),"static path fragments do not imply a reconstructed server or successful request");
    check(ScopeInspectionView.routeDisplay("${host}/api/items/${id}?token=PRIVATE_QUERY").equals("${host}/api/items/${id}"),"masked dynamic path remains identifiable without exposing a query value");
    check(ScopeInspectionView.routeDisplay("/api/items/{id}?key=PRIVATE_QUERY").equals("/api/items/{id}"),"relative API templates retain exact path placeholders in the display-only helper");
    check(!ScopeInspectionView.routeDisplay("/api/token/PRIVATE_TOKEN?next=PRIVATE_QUERY").contains("PRIVATE_"),"relative credential path segments and query values remain redacted");
    check(ScopeInspectionView.routeDisplay("javascript:alert(1)").isEmpty(),"non-HTTP executable schemes are not displayed as route candidates");

    Path directory=Files.createTempDirectory("scope-view-history-");
    var history=new ScopeInspectionHistory(directory,"33333333-3333-4333-8333-333333333333",()->"fixed-scope");
    var options=new ScopeInspection.Options("https://view.test/app/",50,1,0,2000,1024*1024,2,false,false,List.of(new ScopeInspection.Profile("viewer","선택 계정",Map.of(),null)));
    var progress=finding("COVERAGE","진행 기록","미확인","private body must never persist","1".repeat(64));
    history.save(options,snapshot("first",List.of(secret,progress)));
    var changedProgress=finding("COVERAGE","진행 기록","미확인","other private body","2".repeat(64));
    check(history.changes(options,snapshot("second",List.of(secret,changedProgress))).isEmpty(),"transient coverage fingerprints do not become new key-information changes");
    var changedSecret=finding("SECRET","api_key","유효성 미검증","[가림] · 분류: SECRET_CANDIDATE","9".repeat(64));
    check(history.changes(options,snapshot("third",List.of(changedSecret,changedProgress))).stream().anyMatch(f->f.title().equals("발견 내용 변경")),"real masked discovery fingerprint changes still appear");
    var crowded=new ArrayList<ScopeInspection.Finding>();
    for(int i=0;i<ScopeInspectionHistory.MAX_FINDINGS+5;i++)crowded.add(finding("COVERAGE","진행 "+i,"미확인","sensitive-progress-detail",ScopeInspectionAnalysis.hash("progress"+i)));
    crowded.add(secret);crowded.add(personal);
    history.save(options,snapshot("bounded",crowded));
    var saved=history.list().stream().filter(s->s.id().equals("bounded")).findFirst().orElseThrow();
    check(saved.findings().get(0).category().equals("SECRET")&&saved.findings().get(1).category().equals("SENSITIVE"),"bounded history keeps actual key and personal observations ahead of housekeeping rows");
    check(!saved.complete()&&saved.findingsCount()==crowded.size(),"prioritizing saved summaries never hides truncation or manufactures complete coverage");
    String wire=Files.readString(directory.resolve("runs-v1.json"));
    check(!wire.contains("private body")&&!wire.contains("sensitive-progress-detail")&&!wire.contains("source-1"),"history prioritization preserves masked metadata-only storage");
    System.out.println("ScopeInspectionViewTest: "+checks+" checks passed");
  }
}
