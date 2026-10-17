package lab.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.*;

/** Common authentication, declared feature permission and CSRF enforcement. */
public final class CommunityAccessInterceptor implements HandlerInterceptor {
    private final CommunityAccessPolicy policy;
    private final ObjectMapper json=new ObjectMapper();
    public CommunityAccessInterceptor(CommunityAccessPolicy policy) {this.policy=policy;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception {
        String path=CommunityAccessPolicy.path(request);
        if(!path.startsWith("/api/"))return true;
        response.setHeader("Cache-Control","no-store");
        try {
            policy.current(request); // Account suspension applies to legacy training APIs too.
            if(!(handler instanceof HandlerMethod method))return true;
            var rule=method.getMethodAnnotation(AccessRule.class);
            String pattern=String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
            boolean scoped=List.of("/api/community/","/api/workspace/","/api/access/","/api/auth/").stream().anyMatch(pattern::startsWith);
            if(rule==null) {if(scoped)CommunityAccessPolicy.deny(HttpStatus.FORBIDDEN,"권한 정책이 등록되지 않은 기능입니다.");return true;}
            @SuppressWarnings("unchecked") var variables=(Map<String,String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            String mode=variables==null?null:variables.get("mode");
            // The older community controller names its template variable 'mode' but binds it as m.
            if(mode!=null&&!Set.of("fixed","vuln").contains(mode))CommunityAccessPolicy.deny(HttpStatus.NOT_FOUND,"실습 모드 없음");
            request.setAttribute(CommunityAccessPolicy.DECLARED,rule.value());
            if(rule.value()==CommunityAccessPolicy.Permission.PUBLIC)return true;
            boolean training="vuln".equals(mode);
            policy.require(request,training?CommunityAccessPolicy.Permission.MEMBER:rule.value());
            if(!training&&!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()))policy.csrf(request);
            return true;
        } catch(ResponseStatusException error) {
            int status=error.getStatusCode().value();policy.record(request,"DENIED",status);
            response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(json.writeValueAsString(Map.of("error",Objects.requireNonNullElse(error.getReason(),"접근할 수 없습니다."),"trainingOnly",true)));
            return false;
        }
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
        if(!(request.getAttribute(CommunityAccessPolicy.DECLARED) instanceof CommunityAccessPolicy.Permission permission))return;
        int status=response.getStatus();
        if(status==401||status==403)policy.record(request,"DENIED",status);
        else if(permission!=CommunityAccessPolicy.Permission.PUBLIC&&request.getMethod().equals("POST")&&status<400)policy.record(request,"ALLOWED",status);
    }
}
