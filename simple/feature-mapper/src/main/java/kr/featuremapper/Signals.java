package kr.featuremapper;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Mapping examines bounded metadata; complete wire packets live independently on disk. */
final class Signals {
    static final int ANALYSIS_BYTES=512*1024;
    static String digest(String s){return digest(s.getBytes(StandardCharsets.UTF_8));}
    static String digest(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static String clean(String s,int n){if(s==null)return "";return s.replaceAll("[\\p{Cntrl}]"," ").strip().substring(0,Math.min(n,s.replaceAll("[\\p{Cntrl}]"," ").strip().length()));}
    static String origin(String url){URI u=URI.create(url);if(u.getHost()==null||!("http".equals(u.getScheme())||"https".equals(u.getScheme())))throw new IllegalArgumentException("HTTP URL이 아닙니다.");int p=u.getPort();return u.getScheme()+"://"+u.getHost().toLowerCase(Locale.ROOT)+((p<0||p==80&&u.getScheme().equals("http")||p==443&&u.getScheme().equals("https"))?"":":"+p);}
    static String safePage(String url){try{URI u=URI.create(url);return origin(url)+(u.getRawPath()==null?"/":u.getRawPath());}catch(Exception e){return "";}}
    static byte[] body(byte[] raw){for(int i=0;i+3<raw.length;i++)if(raw[i]==13&&raw[i+1]==10&&raw[i+2]==13&&raw[i+3]==10)return Arrays.copyOfRange(raw,i+4,raw.length);return new byte[0];}
    static Map<String,String> headers(byte[] raw){String s=new String(raw,0,Math.min(raw.length,65536),StandardCharsets.ISO_8859_1);int end=s.indexOf("\r\n\r\n");if(end>=0)s=s.substring(0,end);Map<String,String> out=new LinkedHashMap<>();String[] lines=s.split("\r\n");for(int i=1;i<lines.length;i++){int j=lines[i].indexOf(':');if(j>0)out.merge(lines[i].substring(0,j).toLowerCase(Locale.ROOT),lines[i].substring(j+1).strip(),(a,b)->a+"; "+b);}return out;}
    static Object json(byte[] b){if(b.length>ANALYSIS_BYTES)return null;try{return Json.parse(new String(b,StandardCharsets.UTF_8),ANALYSIS_BYTES,20000);}catch(RuntimeException e){return null;}}
    static Map<String,String> leaves(Object value){Map<String,String> out=new LinkedHashMap<>();flatten(value,"",out,0);return out;}
    static void flatten(Object v,String path,Map<String,String> out,int depth){if(depth>12||out.size()>=1024)return;if(v instanceof Map<?,?> m)m.forEach((k,x)->flatten(x,path.isEmpty()?String.valueOf(k):path+"."+k,out,depth+1));else if(v instanceof List<?> a){for(int i=0;i<Math.min(a.size(),64);i++)flatten(a.get(i),path+"[]",out,depth+1);}else if(v!=null)out.put(path,clean(String.valueOf(v),512));}
    static Map<String,String> form(String s){Map<String,String> out=new LinkedHashMap<>();for(String part:s.split("&")){if(out.size()>1024)break;int at=part.indexOf('=');try{out.put(URLDecoder.decode(at<0?part:part.substring(0,at),StandardCharsets.UTF_8),URLDecoder.decode(at<0?"":part.substring(at+1),StandardCharsets.UTF_8));}catch(IllegalArgumentException ignored){}}return out;}
    static Map<String,String> fields(byte[] body,Map<String,String> headers){Object j=json(body);if(j!=null)return leaves(j);if(body.length<=ANALYSIS_BYTES&&headers.getOrDefault("content-type","").contains("x-www-form-urlencoded"))return form(new String(body,StandardCharsets.UTF_8));return Map.of();}
    static String session(String origin,Map<String,String> headers,String salt){String auth=headers.getOrDefault("authorization","")+headers.getOrDefault("x-auth-token","");String cookie=headers.getOrDefault("cookie","");List<String> selected=new ArrayList<>();for(String c:cookie.split(";")){String name=c.split("=",2)[0].strip().toLowerCase(Locale.ROOT);if(name.matches(".*(session|sess|sid|auth|token|jwt|ticket).*"))selected.add(c.strip());}Collections.sort(selected);String value=auth+String.join(";",selected);return value.isBlank()?"미인증/미확인":"세션-"+digest(salt+origin+value).substring(0,12);}
    static String template(String path,Set<String> knownIds){List<String> parts=new ArrayList<>();for(String s:path.split("/",-1)){String decoded=s;try{decoded=URLDecoder.decode(s,StandardCharsets.UTF_8);}catch(Exception ignored){}parts.add(s.matches("[0-9]+|[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}|[0-9a-fA-F]{24,}")||knownIds.contains(decoded)?"{id}":s);}return String.join("/",parts);}
    static String entity(String path){String[] parts=path.split("/");String found="서비스";for(String s:parts){if(s.isBlank()||(s.matches("api|rest|v[0-9]+|graphql")||s.equals("{id}")))continue;found=s;break;}return switch(found.toLowerCase(Locale.ROOT)){case "posts","post","boards","board","articles"->"게시판";case "users","user","members","member","profile"->"회원";case "files","file","attachments","upload"->"파일";case "orders","order"->"주문";case "payments","payment"->"결제";case "auth","login","logout","session"->"인증";default->found;};}
    static String operation(String method,String path,Map<String,String> fields){
        for(String key:List.of("operationName","action","operation","cmd")){String v=fields.get(key);if(v!=null&&!v.isBlank())return clean(v,80);}
        if(fields.keySet().stream().anyMatch(k->k.toLowerCase(Locale.ROOT).contains("newpassword")))return "비밀번호 변경";
        if(path.toLowerCase(Locale.ROOT).contains("login"))return "로그인";if(path.toLowerCase(Locale.ROOT).contains("logout"))return "로그아웃";
        return switch(method){case "POST"->path.matches(".*(upload|files|attachments).*" )?"업로드":"생성";case "PATCH","PUT"->"수정";case "DELETE"->"삭제";default->path.contains("{id}")?"상세 조회":"목록·조회";};
    }
    static boolean common(String path,Map<String,String> responseHeaders){String mime=responseHeaders.getOrDefault("content-type","");return path.matches("(?i).*(\\.(css|js|png|jpg|jpeg|gif|svg|ico|woff2?|map)|/(health|heartbeat|notifications|session|refresh))$")||mime.startsWith("image/")||mime.contains("javascript")||mime.contains("text/css");}
    static boolean identifier(String key,String value){String k=key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");return (k.endsWith("id")||k.endsWith("uuid")||k.endsWith("key"))&&value.length()>=1&&value.length()<=128&&!Set.of("null","true","false").contains(value)&&!k.matches(".*(session|token|auth|secret|csrf).*");}
}
