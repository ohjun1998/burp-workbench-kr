package kr.workbench;

import java.util.*;
import burp.api.montoya.http.message.requests.HttpRequest;

/** Conservative single-operation JSON GraphQL support; ambiguous documents keep the write gate. */
final class GraphqlSemantics {
  static boolean read(HttpRequest request){return Core.readMethod(request.method())||query(request);}
  static boolean query(HttpRequest request){
    if(!"POST".equals(request.method())||request.body().length()>65536)return false;
    String path;try{path=java.net.URI.create(request.url()).getPath();}catch(Exception ex){return false;}if(path==null)return false;String type=Objects.toString(request.headerValue("Content-Type"),"").toLowerCase(Locale.ROOT);
    return (path.equals("/graphql")||path.endsWith("/graphql")||path.endsWith("/graphql/"))&&type.startsWith("application/json")&&queryBody(request.bodyToString());
  }
  static boolean queryBody(String body){
    try{
      Object parsed=MinerJson.parse(body,65536,4096);if(!(parsed instanceof Map<?,?> m)||!(m.get("query") instanceof String document))return false;
      Object operation=m.get("operationName");if(operation!=null&&!(operation instanceof String))return false;
      List<String> tokens=tokens(document);if(tokens.isEmpty())return false;int at=0;String name=null;
      if(tokens.get(0).equals("query")){at++;if(at<tokens.size()&&tokens.get(at).matches("[_A-Za-z][_0-9A-Za-z]*"))name=tokens.get(at++);}
      else if(!tokens.get(0).equals("{"))return false;
      if(operation!=null&&!operation.equals(name))return false;
      // Skip variable definitions/directives, accounting for object defaults inside parentheses.
      int parens=0;while(at<tokens.size()){
        String t=tokens.get(at);if(t.equals("(") )parens++;if(t.equals(")")&&--parens<0)return false;
        if(t.equals("{")&&parens==0)break;
        if(parens==0&&(t.equals("mutation")||t.equals("subscription")||t.equals("fragment")||t.equals("query")))return false;at++;
      }
      if(parens!=0||at>=tokens.size())return false;int depth=0;boolean field=false;
      for(;at<tokens.size();at++){String t=tokens.get(at);if(t.equals("..."))return false;if(t.equals("{"))depth++;else if(t.equals("}")){if(--depth==0)return field&&at==tokens.size()-1;}else if(t.matches("[_A-Za-z][_0-9A-Za-z]*"))field=true;}
      return false;
    }catch(Exception ex){return false;}
  }
  private static List<String> tokens(String s){
    List<String> out=new ArrayList<>();Deque<Character> stack=new ArrayDeque<>();
    for(int i=0;i<s.length();){char c=s.charAt(i);if(Character.isWhitespace(c)||c==','||c=='\uFEFF'){i++;continue;}if(c=='#'){while(i<s.length()&&s.charAt(i)!='\n'&&s.charAt(i)!='\r')i++;continue;}
      if(c=='"'){if(s.startsWith("\"\"\"",i))throw new IllegalArgumentException();int start=i++;boolean end=false;while(i<s.length()){char q=s.charAt(i++);if(q=='\\'){if(i>=s.length())throw new IllegalArgumentException();i++;}else if(q=='"'){end=true;break;}else if(q<32)throw new IllegalArgumentException();}if(!end)throw new IllegalArgumentException();MinerJson.parse(s.substring(start,i));out.add("STRING");continue;}
      if(Character.isLetter(c)||c=='_'){int start=i++;while(i<s.length()&&(Character.isLetterOrDigit(s.charAt(i))||s.charAt(i)=='_'))i++;out.add(s.substring(start,i));continue;}
      if(c=='-'||Character.isDigit(c)){int start=i++;while(i<s.length()&&"0123456789.eE+-".indexOf(s.charAt(i))>=0)i++;MinerJson.parse(s.substring(start,i));out.add("NUMBER");continue;}
      if(s.startsWith("...",i)){out.add("...");i+=3;continue;}
      if("{}()[]!$:=@|&".indexOf(c)<0)throw new IllegalArgumentException();
      if("{([".indexOf(c)>=0)stack.push(c);if("})]".indexOf(c)>=0&&(stack.isEmpty()||"{([".indexOf(stack.pop())!="})]".indexOf(c)))throw new IllegalArgumentException();
      out.add(String.valueOf(c));i++;
    }if(!stack.isEmpty())throw new IllegalArgumentException();return out;
  }
  enum Result{DATA,DENIED,ERROR,UNKNOWN}
  static Result response(String body){
    try{Object parsed=MinerJson.parse(body,262144,20000);if(!(parsed instanceof Map<?,?> m))return Result.UNKNOWN;
      Object errors=m.get("errors");boolean data=hasData(m.get("data"));
      if(errors instanceof List<?> list&&!list.isEmpty()){
        if(data)return Result.ERROR;
        for(Object error:list){if(!(error instanceof Map<?,?> e))return Result.ERROR;Object ext=e.get("extensions");String code=ext instanceof Map<?,?> x?Objects.toString(x.get("code"),""):"";String message=Objects.toString(e.get("message"),"").split("\\r?\\n",2)[0].strip();
          if(!Set.of("FORBIDDEN","UNAUTHENTICATED","PERMISSION_DENIED").contains(code)&&!message.matches("(?is)^(?:You can only query your own transaction summary\\.|Forbidden|Unauthorized|Not authorized|Access denied)[.!]?$"))return Result.ERROR;
        }return Result.DENIED;
      }
      if(errors!=null&&(!(errors instanceof List<?> list)||!list.isEmpty()))return Result.ERROR;
      return data?Result.DATA:Result.UNKNOWN;
    }catch(Exception e){return Result.UNKNOWN;}
  }
  private static boolean hasData(Object value){if(value instanceof Map<?,?> m)return m.values().stream().anyMatch(GraphqlSemantics::hasData);if(value instanceof List<?> l)return l.stream().anyMatch(GraphqlSemantics::hasData);return value!=null;}
}
