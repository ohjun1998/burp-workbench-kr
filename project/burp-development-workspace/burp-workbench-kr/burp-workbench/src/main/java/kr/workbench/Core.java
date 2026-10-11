package kr.workbench;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.util.zip.*;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;

/** Independent utilities; no upstream extension code is bundled. */
public final class Core {
    private Core() {}
    public static final int TEXT_LIMIT = 128 * 1024;
    public static Map<String,String> headers(String input) {
        Map<String,String> out = new LinkedHashMap<>();
        for (String line : input.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon < 1) throw new IllegalArgumentException("헤더는 Name: value 형식이어야 합니다.");
            String name=line.substring(0,colon).trim().toLowerCase(Locale.ROOT);
            String value=line.substring(colon+1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+") || value.indexOf('\r')>=0 || value.indexOf('\0')>=0)
                throw new IllegalArgumentException("유효하지 않은 헤더입니다.");
            if (Set.of("host","content-length","transfer-encoding","connection",":authority").contains(name))
                throw new IllegalArgumentException("세션 설정에서 변경할 수 없는 헤더: "+name);
            if (out.put(name,value)!=null) throw new IllegalArgumentException("중복 헤더: "+name);
        }
        return out;
    }
    public static Set<String> removedHeaders(String input) {
        Set<String> out=new LinkedHashSet<>(Set.of("cookie","authorization","proxy-authorization"));
        for(String s:input.split(",")) {
            s=s.trim().toLowerCase(Locale.ROOT);
            if(!s.isEmpty()) {
                if(!s.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw new IllegalArgumentException("제거할 헤더 이름을 확인하세요.");
                if(Set.of("host","content-length","transfer-encoding","connection").contains(s)) throw new IllegalArgumentException("제거할 수 없는 헤더: "+s);
                out.add(s);
            }
        }
        return out;
    }
    public static HttpRequest sessionRequest(HttpRequest request, Map<String,String> profile, Set<String> remove) {
        HttpRequest next=request;
        // Remove every duplicate credential header before inserting exactly one replacement.
        Set<String> names=new LinkedHashSet<>(remove); names.addAll(profile.keySet());
        for(HttpHeader h:request.headers()) if(names.contains(h.name().toLowerCase(Locale.ROOT))) next=next.withRemovedHeader(h);
        for(var e:profile.entrySet()) next=next.withHeader(e.getKey(),e.getValue());
        return next;
    }
    public static boolean readMethod(String method) { return Set.of("GET","HEAD","OPTIONS").contains(method.toUpperCase(Locale.ROOT)); }
    public static String compare(int originalStatus, byte[] original, int replayStatus, byte[] replay) {
        if(originalStatus<200 || originalStatus>=300) return "기준 응답 확인 필요";
        if(replayStatus==401 || replayStatus==403) return "차단 응답 · 본문 확인";
        if(replayStatus>=300 && replayStatus<400) return "리다이렉트 · 로그인 여부 확인";
        if(replayStatus>=200 && replayStatus<300 && Arrays.equals(original,replay)) return "동일 응답 · 권한 검증 필요";
        if(replayStatus>=200 && replayStatus<300) return "성공 응답 · 데이터/실행 결과 확인";
        return "판정 보류";
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public static String transform(String op,String text) throws Exception {
        if(text.length()>TEXT_LIMIT) throw new IllegalArgumentException("입력은 128 KiB 이하로 제한됩니다.");
        return switch(op) {
            case "URL 인코딩 (form)" -> URLEncoder.encode(text,StandardCharsets.UTF_8);
            case "URL 디코딩 (form)" -> URLDecoder.decode(text,StandardCharsets.UTF_8);
            case "Base64 인코딩" -> Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            case "Base64 디코딩" -> new String(Base64.getDecoder().decode(text.strip()),StandardCharsets.UTF_8);
            case "Base64URL 인코딩" -> Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            case "Base64URL 디코딩" -> new String(Base64.getUrlDecoder().decode(text.strip()),StandardCharsets.UTF_8);
            case "Hex 인코딩" -> HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
            case "Hex 디코딩" -> new String(HexFormat.of().parseHex(text.replaceAll("\\s","")),StandardCharsets.UTF_8);
            case "SHA-256" -> hash(text.getBytes(StandardCharsets.UTF_8));
            case "Gzip → Base64" -> {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(GZIPOutputStream gzip=new GZIPOutputStream(bytes)) { gzip.write(text.getBytes(StandardCharsets.UTF_8)); }
                yield Base64.getEncoder().encodeToString(bytes.toByteArray());
            }
            case "Base64 → Gzip 해제" -> {
                try(GZIPInputStream gzip=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(text.strip())))) {
                    byte[] bytes=gzip.readNBytes(TEXT_LIMIT+1);
                    if(bytes.length>TEXT_LIMIT) throw new IllegalArgumentException("압축 해제 결과가 128 KiB를 초과합니다.");
                    yield new String(bytes,StandardCharsets.UTF_8);
                }
            }
            default -> throw new IllegalArgumentException("지원하지 않는 변환");
        };
    }
    public static String jwt(String text) {
        String token=text.strip().replaceFirst("(?i)^Bearer\\s+","");
        if(token.length()>TEXT_LIMIT) throw new IllegalArgumentException("JWT가 너무 큽니다.");
        String[] p=token.split("\\.",-1);
        if(p.length!=3 && p.length!=5) throw new IllegalArgumentException("JWT는 3개(JWS) 또는 5개(JWE) 구간입니다.");
        String header=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8);
        StringBuilder out=new StringBuilder("HEADER\n").append(header);
        if(p.length==5) return out.append("\n\nJWE: 암호화된 토큰입니다. 이 버전에서는 복호화하지 않습니다.").toString();
        String payload=new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8);
        out.append("\n\nPAYLOAD\n").append(payload).append("\n\n서명 검증: 수행하지 않음. 디코딩만으로 신뢰할 수 없습니다.");
        if(Pattern.compile("\"alg\"\\s*:\\s*\"none\"",Pattern.CASE_INSENSITIVE).matcher(header).find()) out.append("\n검토 항목: alg=none. 서버가 실제로 수용하는지 별도 확인하세요.");
        if(p[2].isEmpty()) out.append("\n검토 항목: 서명 구간이 비어 있습니다.");
        return out.append(JwtTimes.describe(payload,java.time.Instant.now())).toString();
    }
    public static String jwtFromMessage(String message) {
        Matcher m=Pattern.compile("[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*").matcher(message);
        return m.find()?m.group():"";
    }
    public static List<String> jsPaths(String js) {
        if(js.length()>TEXT_LIMIT) js=js.substring(0,TEXT_LIMIT);
        LinkedHashSet<String> paths=new LinkedHashSet<>();
        Matcher m=Pattern.compile("[\"'`]((?:https?://|/)[^\"'`\\s<>]{1,300})[\"'`]").matcher(js);
        while(m.find() && paths.size()<250) paths.add(m.group(1));
        Matcher map=Pattern.compile("sourceMappingURL=([^\\s]+)").matcher(js);
        while(map.find() && paths.size()<250) paths.add("[source map] "+map.group(1));
        return new ArrayList<>(paths);
    }
    public static String html(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
    public static String csv(String s) {
        if(s.matches("^[\\s]*[=+@-].*")) s="'"+s;
        return "\""+s.replace("\"","\"\"")+"\"";
    }
    public static String safeUrl(String url) { return url.split("[?#]",2)[0]; }
}
