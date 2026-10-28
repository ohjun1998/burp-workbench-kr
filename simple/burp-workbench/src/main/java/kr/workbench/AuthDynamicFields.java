package kr.workbench;

import java.util.*;

/** Suggest only; never automatically discards differing evidence. */
final class AuthDynamicFields {
  static List<String> suggest(List<AuthComparePanel.Run> runs){
    Map<String,Map<String,Object>> prior=new HashMap<>();Set<String> changed=new TreeSet<>();
    for(var run:runs){var response=run.baseline();if(response==null||response.statusCode()<200||response.statusCode()>=300||response.body().length()>128*1024)continue;
      try{var json=AuthJudgement.json(AuthEvidencePanel.readBody(response));if(json==null)continue;
        String key=run.original.method()+" "+run.original.url()+" "+Core.hash(String.join("|",AuthComparePanel.credentialValues(run.original,run.context.names())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var old=prior.put(key,json);if(old==null)continue;
        for(String field:old.keySet())if(json.containsKey(field)&&!Objects.equals(old.get(field),json.get(field)))changed.add(field);
      }catch(Exception ignored){}
    }
    return changed.stream().limit(32).toList();
  }
}
