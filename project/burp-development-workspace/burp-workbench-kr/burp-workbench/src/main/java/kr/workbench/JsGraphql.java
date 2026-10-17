package kr.workbench;

import java.util.*;
import java.util.regex.*;

/** Local request-document metadata only; never performs GraphQL introspection. */
final class JsGraphql {
  record Operation(String type,String name,List<String> variables) {
    String label(){return "GraphQL "+type+" "+name+(variables.isEmpty()?"":" · variables "+String.join(", ",variables));}
  }
  static List<Operation> operations(String body) {
    try {
      Object parsed=MinerJson.parse(body);
      if(!(parsed instanceof Map<?,?> map)||!(map.get("query") instanceof String query)||query.length()>16384)return List.of();
      // Quoted values/comments cannot manufacture operation definitions.
      String clean=query.replaceAll("(?s)\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|#[^\\r\\n]*", " ");
      Matcher matcher=Pattern.compile("\\b(query|mutation|subscription)\\s*([_A-Za-z][_0-9A-Za-z]*)?\\s*(\\([^)]*\\))?").matcher(clean);
      List<Operation> out=new ArrayList<>();
      while(matcher.find()&&out.size()<64){
        List<String> variables=new ArrayList<>();
        Matcher variable=Pattern.compile("\\$([_A-Za-z][_0-9A-Za-z]*)\\s*:").matcher(Objects.toString(matcher.group(3),""));
        while(variable.find())variables.add(variable.group(1));
        out.add(new Operation(matcher.group(1),Objects.toString(matcher.group(2),"(anonymous)"),List.copyOf(variables)));
      }
      if(out.isEmpty()&&clean.stripLeading().startsWith("{"))out.add(new Operation("query","(anonymous)",List.of()));
      if(map.get("operationName") instanceof String selected&&!selected.isBlank())return out.stream().filter(op->op.name().equals(selected)).toList();
      return List.copyOf(out);
    }catch(Exception ex){return List.of();}
  }
  static String describe(String body){return operations(body).stream().map(Operation::label).reduce((a,b)->a+" / "+b).orElse("");}
}
