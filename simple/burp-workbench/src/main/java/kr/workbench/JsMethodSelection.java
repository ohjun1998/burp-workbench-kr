package kr.workbench;

import java.util.*;

/** Prefers source and single observed methods, otherwise prepares a labeled GET default. Never probes. */
final class JsMethodSelection {
  record Choice(JsRequestDraft.Draft draft,String reason) {}
  static boolean known(String method){return method!=null&&method.matches("[A-Z][A-Z0-9_-]{0,31}")&&!method.startsWith("REPLACE_");}
  static Choice choose(JsRequestDraft.Draft draft,Set<String> observed){
    if(known(draft.method()))return new Choice(draft,"분석·확인된 초안");
    if(observed==null||observed.isEmpty())return fallback(draft,"근거 없음");
    if(observed.size()!=1)return fallback(draft,"관찰 충돌");
    String method=observed.iterator().next();
    if(!known(method)||draft.url().contains("REPLACE_PATH")||draft.url().contains("{dynamic}"))
      return fallback(draft,"관찰 미확정");
    return new Choice(new JsRequestDraft.Draft(draft.url(),method,draft.body(),draft.contentType()),"관찰 기록에서 자동 선택 · "+method);
  }
  private static Choice fallback(JsRequestDraft.Draft draft,String reason){
    return new Choice(new JsRequestDraft.Draft(draft.url(),"GET",draft.body(),draft.contentType()),"GET · 기본값 · "+reason);
  }
}
