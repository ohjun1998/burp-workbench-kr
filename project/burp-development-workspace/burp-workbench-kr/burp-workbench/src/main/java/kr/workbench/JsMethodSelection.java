package kr.workbench;

import java.util.*;

/** Selects only a known draft method or a single observed method; never probes an endpoint. */
final class JsMethodSelection {
  record Choice(JsRequestDraft.Draft draft,String reason) {}
  static boolean known(String method){return method!=null&&method.matches("[A-Z][A-Z0-9_-]{0,31}")&&!method.startsWith("REPLACE_");}
  static Choice choose(JsRequestDraft.Draft draft,Set<String> observed){
    if(known(draft.method()))return new Choice(draft,"분석·확인된 초안");
    if(observed==null||observed.isEmpty())return new Choice(draft,"관찰 근거 없음 · 직접 선택");
    if(observed.size()!=1)return new Choice(draft,"관찰 메서드 충돌 · 직접 선택");
    String method=observed.iterator().next();
    if(!known(method)||draft.url().contains("REPLACE_PATH")||draft.url().contains("{dynamic}"))
      return new Choice(draft,"확정된 관찰 근거 없음 · 직접 선택");
    return new Choice(new JsRequestDraft.Draft(draft.url(),method,draft.body(),draft.contentType()),"관찰 기록에서 자동 선택 · "+method);
  }
}
