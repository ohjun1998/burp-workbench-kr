package lab.enterprise;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Isolated training authentication. No normal JSESSIONID or client role is trusted. */
public final class AuthenticationLab {
    public static final String COOKIE="AUTHLABSID", ISSUER="loop-auth-lab", AUDIENCE="loop-community";
    static final String IDENTITY=AuthenticationLab.class.getName()+".identity";
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Base64.Encoder B64=Base64.getUrlEncoder().withoutPadding();
    private final JdbcTemplate db;
    private final CommunityAccessPolicy policy;
    private final byte[] signingKey;
    private final Map<String,Bundle> sessions=new HashMap<>(),tokens=new HashMap<>(),bundles=new HashMap<>();

    public enum Profile {
        SESSION("session","세션 쿠키",1), TOKEN("token","불투명 토큰",2), JWT("jwt","JWT",4),
        SESSION_TOKEN("session-token","세션 + 토큰",3), SESSION_JWT("session-jwt","세션 + JWT",5),
        TOKEN_JWT("token-jwt","토큰 + JWT",6), ALL("all","세션 + 토큰 + JWT",7);
        public final String id,label;public final int mask;
        Profile(String id,String label,int mask){this.id=id;this.label=label;this.mask=mask;}
        public boolean has(int bit){return (mask&bit)!=0;}
        public static Profile of(String id){return Arrays.stream(values()).filter(p->p.id.equals(id)).findFirst().orElseThrow(()->failure(HttpStatus.NOT_FOUND,"PROFILE_NOT_FOUND"));}
        public List<String> parts(){var out=new ArrayList<String>();if(has(1))out.add("Cookie: "+COOKIE);if(has(2))out.add("X-Auth-Token");if(has(4))out.add("Authorization: Bearer JWT");return out;}
    }
    private record Bundle(String id,int userId,String profile,String csrf,long expires) {}
    public record Identity(CommunityAccessPolicy.Principal principal,String bundleId,String csrf,Set<Integer> supplied) {}

    public AuthenticationLab(JdbcTemplate db,CommunityAccessPolicy policy){
        this.db=db;this.policy=policy;
        String configured=System.getenv("AUTH_LAB_SIGNING_KEY_B64");
        signingKey=configured==null?randomBytes(32):Base64.getDecoder().decode(configured);
        if(signingKey.length<32)throw new IllegalStateException("AUTH_LAB_SIGNING_KEY_B64 requires at least 32 bytes");
    }
    static ResponseStatusException failure(HttpStatus status,String code){return new ResponseStatusException(status,code);}
    private static byte[] randomBytes(int n){byte[] value=new byte[n];RANDOM.nextBytes(value);return value;}
    private static String random(){return B64.encodeToString(randomBytes(32));}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private static boolean password(String value,String stored){
        try{var parts=stored.split(":");var spec=new PBEKeySpec(value.toCharArray(),Base64.getDecoder().decode(parts[0]),120000,256);
            try{return MessageDigest.isEqual(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),Base64.getDecoder().decode(parts[1]));}finally{spec.clearPassword();}
        }catch(Exception error){return false;}
    }
    CommunityAccessPolicy.Principal user(int id){
        var rows=db.queryForList("SELECT u.id,u.username,u.role,COALESCE(s.state,'ACTIVE') AS state FROM lab_users u LEFT JOIN community_account_states s ON s.user_id=u.id WHERE u.id=?",id);
        if(rows.isEmpty())throw failure(HttpStatus.UNAUTHORIZED,"ACCOUNT_NOT_FOUND");
        var row=rows.get(0);String role=(String)row.get("ROLE");
        if("SUSPENDED".equals(row.get("STATE"))||!CommunityAccessPolicy.knownRole(role))throw failure(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        return new CommunityAccessPolicy.Principal(id,(String)row.get("USERNAME"),role,policy.forRole(role));
    }
    public Map<String,Object> login(String profileId,String name,String password,String lifetime,HttpServletRequest request,HttpServletResponse response){
        Profile profile=Profile.of(profileId);sameOrigin(request);
        if(name.length()>50||password.length()>120)throw failure(HttpStatus.UNAUTHORIZED,"LOGIN_FAILED");
        var rows=db.queryForList("SELECT id,password_hash FROM lab_users WHERE username=?",name);
        if(rows.isEmpty()||!password(password,(String)rows.get(0).get("PASSWORD_HASH")))throw failure(HttpStatus.UNAUTHORIZED,"LOGIN_FAILED");
        var principal=user(((Number)rows.get(0).get("ID")).intValue());
        int seconds=switch(lifetime){case "normal"->900;case "short"->2;default->throw failure(HttpStatus.BAD_REQUEST,"INVALID_LIFETIME");};
        long now=Instant.now().getEpochSecond();String sid=random(),opaque=random();var bundle=new Bundle(random(),principal.id(),profile.id,random(),now+seconds);
        synchronized(this){purge();if(bundles.size()>=4096)throw failure(HttpStatus.TOO_MANY_REQUESTS,"LOGIN_CAPACITY");bundles.put(bundle.id(),bundle);sessions.put(hash(sid),bundle);tokens.put(hash(opaque),bundle);}
        Map<String,String> headers=new LinkedHashMap<>();if(profile.has(1))headers.put("Cookie",COOKIE+"="+sid);if(profile.has(2))headers.put("X-Auth-Token",opaque);
        if(profile.has(4))headers.put("Authorization","Bearer "+jwt(bundle,now));headers.put("X-CSRF-Token",bundle.csrf());
        response.addHeader("Set-Cookie",COOKIE+"="+(profile.has(1)?sid:"")+"; Path=/api/auth-lab; Max-Age="+(profile.has(1)?seconds:0)+"; HttpOnly; SameSite=Lax"+(request.isSecure()?"; Secure":""));
        return Map.of("profile",profile.id,"user",identityMap(principal),"headers",headers,"expiresAt",bundle.expires(),"csrf",bundle.csrf(),"trainingOnly",true);
    }
    static Map<String,Object> identityMap(CommunityAccessPolicy.Principal p){return Map.of("id",p.id(),"username",p.username(),"role",p.role());}
    private byte[] signature(String message){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));}catch(Exception error){throw new IllegalStateException(error);}}
    private String jwt(Bundle bundle,long now){try{
        String header=B64.encodeToString(JSON.writeValueAsBytes(Map.of("alg","HS256","typ","JWT")));
        String body=B64.encodeToString(JSON.writeValueAsBytes(Map.of("iss",ISSUER,"aud",AUDIENCE,"sub",String.valueOf(bundle.userId()),"jti",bundle.id(),"profile",bundle.profile(),"iat",now,"nbf",now,"exp",bundle.expires())));
        String signed=header+"."+body;return signed+"."+B64.encodeToString(signature(signed));
    }catch(Exception error){throw new IllegalStateException(error);}}
    private Bundle jwtIdentity(String value){
        try{
            String[] parts=value.split("\\.",-1);if(parts.length!=3||value.length()>4096)throw new IllegalArgumentException();
            for(String part:parts)if(!part.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
            JsonNode header=JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(!header.isObject()||header.size()!=2||!"HS256".equals(header.path("alg").asText())||!"JWT".equals(header.path("typ").asText()))throw new IllegalArgumentException();
            if(!MessageDigest.isEqual(signature(parts[0]+"."+parts[1]),Base64.getUrlDecoder().decode(parts[2])))throw new IllegalArgumentException();
            JsonNode body=JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));long now=Instant.now().getEpochSecond();
            if(!body.isObject()||!ISSUER.equals(body.path("iss").asText())||!AUDIENCE.equals(body.path("aud").asText()))throw new IllegalArgumentException();
            for(String key:List.of("exp","nbf","iat"))if(!body.path(key).isIntegralNumber()||!body.path(key).canConvertToLong())throw new IllegalArgumentException();
            if(body.path("exp").asLong()<=now||body.path("nbf").asLong()>now||body.path("iat").asLong()>now)throw new IllegalArgumentException();
            Bundle bundle=bundles.get(body.path("jti").asText());
            if(bundle==null||!body.path("sub").isTextual()||!String.valueOf(bundle.userId()).equals(body.path("sub").asText())||body.path("exp").asLong()!=bundle.expires()||!bundle.profile().equals(body.path("profile").asText()))throw new IllegalArgumentException();
            return bundle;
        }catch(Exception error){throw failure(HttpStatus.UNAUTHORIZED,"INVALID_JWT");}
    }
    private static String one(HttpServletRequest request,String header){
        var values=Collections.list(request.getHeaders(header));if(values.isEmpty())return null;
        if(values.size()!=1||values.get(0).isBlank()||values.get(0).length()>8192)throw failure(HttpStatus.UNAUTHORIZED,"AMBIGUOUS_CREDENTIAL");
        return values.get(0);
    }
    private static String session(HttpServletRequest request){
        String found=null;for(String line:Collections.list(request.getHeaders("Cookie")))for(String item:line.split(";")){
            String[] pair=item.strip().split("=",2);if(!pair[0].equals(COOKIE))continue;
            if(found!=null||pair.length!=2||!pair[1].matches("[A-Za-z0-9_-]{43}"))throw failure(HttpStatus.UNAUTHORIZED,"AMBIGUOUS_SESSION");found=pair[1];
        }return found;
    }
    public synchronized Identity authenticate(String profileId,HttpServletRequest request){
        Profile profile=Profile.of(profileId);purge();String sid=session(request),opaque=one(request,"X-Auth-Token"),authorization=one(request,"Authorization");
        Map<Integer,Bundle> supplied=new LinkedHashMap<>();
        if(sid!=null)supplied.put(1,sessions.get(hash(sid)));
        if(opaque!=null)supplied.put(2,tokens.get(hash(opaque)));
        if(authorization!=null){if(!authorization.regionMatches(true,0,"Bearer ",0,7))throw failure(HttpStatus.UNAUTHORIZED,"INVALID_AUTHORIZATION");supplied.put(4,jwtIdentity(authorization.substring(7)));}
        for(int bit:List.of(1,2,4))if(profile.has(bit)&&!supplied.containsKey(bit))throw failure(HttpStatus.UNAUTHORIZED,"MISSING_CREDENTIAL");
        Bundle principal=null;for(Bundle b:supplied.values()){
            if(b==null||b.expires()<=Instant.now().getEpochSecond())throw failure(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIAL");
            if(principal!=null&&!principal.id().equals(b.id()))throw failure(HttpStatus.UNAUTHORIZED,"CREDENTIAL_BINDING_MISMATCH");principal=b;
        }
        if(principal==null)throw failure(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED");
        if(!profile.id.equals(principal.profile()))throw failure(HttpStatus.UNAUTHORIZED,"PROFILE_BINDING_MISMATCH");
        return new Identity(user(principal.userId()),principal.id(),principal.csrf(),Set.copyOf(supplied.keySet()));
    }
    public Identity identity(HttpServletRequest request){if(request.getAttribute(IDENTITY) instanceof Identity identity)return identity;throw failure(HttpStatus.UNAUTHORIZED,"LOGIN_REQUIRED");}
    public void require(Identity identity,CommunityAccessPolicy.Permission permission){if(!identity.principal().permissions().contains(permission))throw failure(HttpStatus.FORBIDDEN,"PERMISSION_DENIED");}
    public void csrf(Identity identity,HttpServletRequest request){
        sameOrigin(request);
        if(identity.supplied().contains(1)&&!MessageDigest.isEqual(identity.csrf().getBytes(StandardCharsets.UTF_8),Objects.toString(request.getHeader("X-CSRF-Token"),"").getBytes(StandardCharsets.UTF_8)))throw failure(HttpStatus.FORBIDDEN,"CSRF_MISMATCH");
    }
    private static void sameOrigin(HttpServletRequest request){
        String origin=request.getHeader("Origin");if(origin==null)return;
        String expected=request.getScheme()+"://"+request.getServerName()+((request.getServerPort()==80&&request.getScheme().equals("http"))||(request.getServerPort()==443&&request.getScheme().equals("https"))?"":":"+request.getServerPort());
        if(!expected.equals(origin))throw failure(HttpStatus.FORBIDDEN,"ORIGIN_MISMATCH");
    }
    public synchronized void logout(Identity identity){Bundle bundle=bundles.remove(identity.bundleId());if(bundle!=null){sessions.values().removeIf(b->b.id().equals(bundle.id()));tokens.values().removeIf(b->b.id().equals(bundle.id()));}}
    private void purge(){long now=Instant.now().getEpochSecond();bundles.values().removeIf(b->b.expires()<=now);sessions.values().removeIf(b->!bundles.containsKey(b.id()));tokens.values().removeIf(b->!bundles.containsKey(b.id()));}
}
