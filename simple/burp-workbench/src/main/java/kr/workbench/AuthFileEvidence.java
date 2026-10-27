package kr.workbench;
import burp.api.montoya.http.message.responses.HttpResponse;import java.security.*;import java.util.*;
/** Whole-body bounded digest. Never asserts equal prefixes as equal objects. */
final class AuthFileEvidence {
 static final int LIMIT=4*1024*1024;
 static String digest(HttpResponse r){if(r==null||r.body().length()>LIMIT)throw new IllegalArgumentException("파일 본문 4MiB 제한");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(r.body().getBytes()));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 static String note(HttpResponse a,HttpResponse b){if(b.statusCode()==401||b.statusCode()==403)return "차단 근거 확인 · HTTP "+b.statusCode();if(a.statusCode()<200||a.statusCode()>=300||b.statusCode()<200||b.statusCode()>=300)return "파일 판정 보류 · HTTP/존재 여부 확인";if(a.statusCode()==206||b.statusCode()==206||a.headers().stream().anyMatch(h->h.name().equalsIgnoreCase("Content-Range"))||b.headers().stream().anyMatch(h->h.name().equalsIgnoreCase("Content-Range")))return "부분 파일 응답 · 판정 보류 · 전체 파일 재조회 필요";if(a.body().length()==0||b.body().length()==0)return "빈 파일 응답 · 판정 보류 · 실행/내용 확인";boolean same=a.body().length()==b.body().length()&&digest(a).equals(digest(b));return same?"동일 파일 접근 후보 · 전체 바이트 수/SHA-256 일치 · 정책/영향 수동 확인":"파일 판정 보류 · 전체 바이트 수/SHA-256 차이 · 부분 응답/변환 확인";}
}
