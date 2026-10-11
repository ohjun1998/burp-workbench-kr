package kr.workbench;
import java.net.URI;import java.util.*;import java.util.regex.Pattern;
/** Explicit reviewer policy, snapshotted per run. No inference from an owner field alone. */
final class AuthPolicy {
 enum Access {UNSPECIFIED,PUBLIC,OWNER_ONLY}
 record Rule(String method,String path,Access access,Pattern match){}
 final List<Rule> rules;final Access fallback;
 private AuthPolicy(List<Rule> rules,Access fallback){this.rules=List.copyOf(rules);this.fallback=fallback;}
 static AuthPolicy parse(String text,Access fallback){if(text.length()>8192)throw new IllegalArgumentException("정책 8KB 제한");List<Rule> rows=new ArrayList<>();
  for(String line:text.split("\\R")){if(line.isBlank())continue;String[] p=line.strip().split("\\s+");if(p.length!=3||!p[0].matches("\\*|GET|HEAD|OPTIONS|POST|PUT|PATCH|DELETE")||!p[1].startsWith("/")||p[1].contains("?")||p[1].length()>512)throw new IllegalArgumentException("정책: GET /경로 PUBLIC 또는 OWNER_ONLY");Access access;try{access=Access.valueOf(p[2]);}catch(Exception e){throw new IllegalArgumentException("정책은 PUBLIC / OWNER_ONLY / UNSPECIFIED");}
   StringBuilder regex=new StringBuilder("^");for(String s:p[1].split("/",-1)){if(regex.length()>1)regex.append('/');if(s.matches("\\{[A-Za-z_][A-Za-z0-9_]*}"))regex.append("[^/]+");else regex.append(Pattern.quote(s));}regex.append('$');rows.add(new Rule(p[0],p[1],access,Pattern.compile(regex.toString())));if(rows.size()>32)throw new IllegalArgumentException("정책 최대 32개");}
  rows.sort(Comparator.comparingInt((Rule r)->r.path.contains("{")?0:1).reversed().thenComparing(Comparator.comparingInt((Rule r)->r.path.length()).reversed()));return new AuthPolicy(rows,fallback);
 }
 Access forRequest(String method,String url){try{String path=URI.create(url).getPath();for(Rule r:rules)if((r.method.equals("*")||r.method.equals(method))&&r.match.matcher(path).matches())return r.access;}catch(Exception ignored){}return fallback;}
}
