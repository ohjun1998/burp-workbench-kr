package kr.workbench;

import java.util.*;
import java.util.regex.*;

/** Content evidence, not a vulnerability verdict. Never returns captured values. */
final class EnvironmentContent {
  record Finding(String kind,String title,String reason,String next) {}
  static final int MAX=65536;
  static boolean html(String body){return body.matches("(?is).*<(?:!doctype\\s+html|html|body|head)(?:\\s|>).*");}
  static boolean login(String body){return Pattern.compile("(?is)<input\\b[^>]*type\\s*=\\s*['\"]?password(?:['\"\\s>])").matcher(body).find()
      &&Pattern.compile("(?is)<form\\b").matcher(body).find();}
  static boolean challenge(String body){return Pattern.compile("(?is)(<title>[^<]*(?:just a moment|attention required|access denied)|cf-chl-|g-recaptcha|hcaptcha)").matcher(body).find();}
  static Finding inspect(String url,int status,String headers,String body,String comparison){
    if(body.length()>MAX)return f("LIMIT","내용 확인 보류","본문 분석 한도 초과","원본 응답을 직접 확인하세요.");
    if(status==429)return f("RATE","요청 제한","HTTP 429","대기 후 명시적으로 재개하세요.");
    if(challenge(body))return f("CHALLENGE","보안 확인 화면 의심","챌린지 화면 표식","정상 브라우저 응답과 비교하세요.");
    if(status==401||status==403)return f("DENIED","접근 제한 응답","HTTP "+status,"경로 존재와 계정 권한은 별도로 확인하세요.");
    if(status==404||status==410)return f("MISSING","미발견 응답","HTTP "+status,"다른 계정·시점의 결과와 비교하세요.");
    if(status>=300&&status<400)return f("REDIRECT","이동 응답","HTTP "+status,"Location과 로그인 이동 여부를 확인하세요.");
    if(status<200||status>=300)return f("UNKNOWN","내용 확인 보류","정상 조회 응답이 아님","오류 원인과 원본을 확인하세요.");
    if(login(body))return f("LOGIN","로그인 화면","비밀번호 입력 폼 확인","로그인 세션 유효성을 확인하세요.");
    if(comparison.contains("없는 경로와 동일"))return f("SOFT404","공통 응답","없는 경로 기준과 동일","실제 경로가 존재하는지는 확인되지 않았습니다.");
    if(comparison.contains("기준과 유사"))return f("SIMILAR","공통 화면 의심","없는 경로 기준과 구조·텍스트가 유사","유사도는 단서이며 원본 차이를 확인하세요.");
    Object json=null;try{json=MinerJson.parse(body,MAX,6000);}catch(Exception ignored){}
    if(json instanceof Map<?,?> m){
      if((m.get("openapi") instanceof String||m.get("swagger") instanceof String)&&m.get("paths") instanceof Map<?,?> paths)
        return f("OPENAPI","API 명세 확인","OpenAPI/Swagger 구조 · 선언 경로 "+paths.size()+"개","내부 API 포함 여부와 API별 인증·권한을 확인하세요.");
      if(m.get("version") instanceof Number&&m.get("sources") instanceof List<?> &&m.get("mappings") instanceof String)
        return f("SOURCEMAP","소스맵 구조 확인","version·sources·mappings 구조","원본 코드와 민감정보 포함 여부를 확인하세요.");
      if(m.get("propertySources") instanceof List<?>||m.get("contexts") instanceof Map<?,?>||m.get("beans") instanceof Map<?,?>)
        return f("CONFIG_JSON","운영 정보 구조 확인","설정·컨텍스트 구조 표식","비공개 설정과 민감한 값이 포함됐는지 확인하세요.");
      return f("JSON","JSON 응답","구조화된 JSON 객체","데이터 성격과 접근 권한을 확인하세요.");
    }
    if(!html(body)){
      if(url.matches("(?i).*/\\.git/HEAD$")&&body.strip().matches("(?:ref: refs/[A-Za-z0-9_./-]+|[a-fA-F0-9]{40,64})"))
        return f("GIT","Git 참조 파일 확인","Git HEAD 형식 일치","배포 저장소 정보 노출 범위를 확인하세요.");
      Matcher vars=Pattern.compile("(?m)^\\s*(?:export\\s+)?([A-Z][A-Z0-9_]{0,79})\\s*=\\s*[^\\r\\n]+").matcher(body);
      int count=0;boolean sensitive=false;while(vars.find()){count++;sensitive|=vars.group(1).matches(".*(?:SECRET|PASSWORD|TOKEN|API_KEY|DATABASE_URL|DB_PASS).*");}
      if(count>=2&&sensitive)return f("ENV","환경 설정 형식 확인","환경 변수 대입 "+count+"개 · 민감 항목 이름 표식","실제 인증정보인지 확인하고 노출 범위를 평가하세요.");
    }
    if(Pattern.compile("(?is)<title>\\s*(?:index of|directory listing for)").matcher(body).find()
        &&Pattern.compile("(?is)(parent directory|href=['\"]\\.\\./)").matcher(body).find())
      return f("INDEX","디렉터리 목록 확인","목록 제목과 상위 디렉터리 링크","다운로드 가능한 파일과 접근 범위를 확인하세요.");
    if(body.contains("phpinfo()")&&body.contains("PHP Version")&&body.contains("Configuration"))
      return f("PHPINFO","PHP 실행 정보 확인","phpinfo 구조 표식","내부 경로·환경 변수 노출 여부를 확인하세요.");
    return f("REVIEW","응답 검토",html(body)?"HTML 응답 · 전용 내용 표식 미확인":"내용 표식 미확인","파일명만으로 노출을 판단하지 말고 내용을 확인하세요.");
  }
  static String title(String kind){return switch(kind){case "OPENAPI"->"API 명세 확인";case "ENV"->"환경 설정 형식 확인";case "GIT"->"Git 참조 파일 확인";case "INDEX"->"디렉터리 목록 확인";case "PHPINFO"->"PHP 실행 정보 확인";case "SOURCEMAP"->"소스맵 구조 확인";case "CONFIG_JSON"->"운영 정보 구조 확인";case "JSON"->"JSON 응답";case "SOFT404"->"공통 응답";case "SIMILAR"->"공통 화면 의심";case "LOGIN"->"로그인 화면";case "DENIED"->"접근 제한 응답";case "MISSING"->"미발견 응답";case "RATE"->"요청 제한";case "CHALLENGE"->"보안 확인 화면 의심";case "REDIRECT"->"이동 응답";case "ERROR"->"전송 오류";default->"응답 검토";};}
  private static Finding f(String k,String t,String r,String n){return new Finding(k,t,r,n);}

  /** Strong similarity labels a candidate for review; it never establishes absence. */
  static boolean similar(String a,String b){
    if(a.isEmpty()||b.isEmpty()||!a.startsWith("TEXT:")||!b.startsWith("TEXT:"))return false;
    if(a.equals(b))return true;
    String x=normalize(a),y=normalize(b);
    if(x.equals(y)&&x.length()>=40)return true;
    if(!html(a)||!html(b)||Math.min(a.length(),b.length())<80)return false;
    String tagsA=tags(x),tagsB=tags(y);
    if(tagsA.length()<20||!tagsA.equals(tagsB))return false;
    Set<String> left=words(x),right=words(y),union=new HashSet<>(left),both=new HashSet<>(left);
    union.addAll(right);both.retainAll(right);
    return union.size()>=12&&(double)both.size()/union.size()>=.94;
  }
  static String normalize(String text){return text
      .replaceAll("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b","{uuid}")
      .replaceAll("\\b20[0-9]{2}-[01][0-9]-[0-3][0-9][T ][0-2][0-9]:[0-5][0-9]:[0-5][0-9](?:\\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})?","{time}")
      .replaceAll("(?i)((?:request[-_ ]?id|trace[-_ ]?id)\\s*[:=]\\s*)[a-z0-9_-]{8,128}","$1{id}");}
  static String tags(String text){Matcher m=Pattern.compile("</?([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*>").matcher(text);StringBuilder b=new StringBuilder();while(m.find()&&b.length()<4096)b.append(m.group(1).toLowerCase(Locale.ROOT)).append('/');return b.toString();}
  static Set<String> words(String text){return new HashSet<>(Arrays.asList(text.replaceAll("(?s)<[^>]*>"," ").toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_{}-]+")));}
}
