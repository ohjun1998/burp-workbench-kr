package kr.workbench;
import java.util.*;
/** Stable metadata bridge; third-party extensions have no shared invocation API. */
final class BountyToolBridge {
 record Parameter(String method,String url,String location,String name){}
 static List<Parameter> parse(String text){if(text.length()>128*1024)throw new IllegalArgumentException("외부 결과 128 KiB 제한");List<Parameter> out=new ArrayList<>();Set<Parameter> seen=new HashSet<>();
  for(String line:text.split("\\R")){if(line.isBlank()||line.startsWith("#"))continue;String[] p=line.split("\t",-1);if(p.length!=4||!p[0].matches("GET|HEAD|OPTIONS|POST|PUT|PATCH|DELETE")||ActivityTracker.safeUrl(p[1])==null||!Set.of("URL","BODY","HEADER","COOKIE").contains(p[2])||!p[3].matches("[A-Za-z0-9_.\\[\\]-]{1,128}"))throw new IllegalArgumentException("결과는 METHOD [탭] URL [탭] URL/BODY/HEADER/COOKIE [탭] 이름 형식입니다.");Parameter item=new Parameter(p[0],p[1],p[2],p[3]);if(seen.add(item))out.add(item);if(out.size()>500)throw new IllegalArgumentException("외부 결과 최대 500개");}if(out.isEmpty())throw new IllegalArgumentException("가져올 결과 없음");return List.copyOf(out);
 }
}
