package kr.workbench;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Read-only snapshot of target.scope; native isInScope still enforces exclusions. */
final class BurpScopeConfig {
    record Entry(String key,String label,boolean enabled,String prefix,
                 Pattern protocol,Pattern host,Pattern port,Pattern file) {
        boolean matches(String raw) {
            try {
                if(prefix!=null)return raw.startsWith(prefix);
                URI u=URI.create(raw);
                int number=u.getPort()<0?("https".equalsIgnoreCase(u.getScheme())?443:80):u.getPort();
                String path=Objects.toString(u.getRawPath(),"/");
                if(u.getRawQuery()!=null)path+="?"+u.getRawQuery();
                return protocol.matcher(u.getScheme()).find()&&host.matcher(Objects.toString(u.getHost(),"")).find()
                    &&port.matcher(Integer.toString(number)).find()&&file.matcher(path).find();
            }catch(RuntimeException ex){return false;}
        }
    }
    static List<Entry> parse(String json) {
        if(json==null||json.isBlank())return List.of();
        Object value=MinerJson.parse(json);
        if(!(value instanceof Map<?,?> root)||!(root.get("target") instanceof Map<?,?> target)
            ||!(target.get("scope") instanceof Map<?,?> scope)||!(scope.get("include") instanceof List<?> rows))
            throw new IllegalArgumentException("Burp Target Scope 설정 형식 확인 실패");
        boolean advanced=Boolean.TRUE.equals(scope.get("advanced_mode"));
        List<Entry> entries=new ArrayList<>();
        for(Object valueRow:rows) {
            if(!(valueRow instanceof Map<?,?> row))continue;
            boolean enabled=!Boolean.FALSE.equals(row.get("enabled"));
            if(!advanced&&row.get("prefix") instanceof String prefix) {
                if(WorkbenchScope.canonical(prefix)==null)continue;
                // Scope prefixes contain no credentials. Discard query/fragment before storage.
                URI u=URI.create(prefix);
                String clean=u.getScheme()+"://"+u.getRawAuthority()+Objects.toString(u.getRawPath(),"");
                entries.add(new Entry("prefix:"+clean,clean,enabled,clean,null,null,null,null));
            }else if(row.get("host") instanceof String host) {
                String protocol=Objects.toString(row.get("protocol"),"any"),port=Objects.toString(row.get("port"),""),file=Objects.toString(row.get("file"),"");
                String key=MetadataExport.json(List.of(protocol,host,port,file));
                entries.add(new Entry("advanced:"+key,"Burp 고급 · "+protocol+" · "+host+" · "+port+" · "+file,enabled,null,
                    Pattern.compile(protocol.equals("any")?".*":"^"+Pattern.quote(protocol)+"$",Pattern.CASE_INSENSITIVE),
                    Pattern.compile(host.isEmpty()?".*":host,Pattern.CASE_INSENSITIVE),
                    Pattern.compile(port.isEmpty()?".*":port),Pattern.compile(file.isEmpty()?".*":file)));
            }
        }
        return List.copyOf(entries);
    }
}
