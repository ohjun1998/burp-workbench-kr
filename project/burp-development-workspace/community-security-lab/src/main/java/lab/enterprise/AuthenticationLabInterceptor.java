package lab.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.*;

/** Every authentication-lab API declares a policy; only the explicit vuln path relaxes roles. */
public final class AuthenticationLabInterceptor implements HandlerInterceptor {
    private final AuthenticationLab auth;
    private final ObjectMapper json=new ObjectMapper();
    public AuthenticationLabInterceptor(AuthenticationLab auth){this.auth=auth;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        try{
            if(!(handler instanceof HandlerMethod method))return true;
            AccessRule rule=method.getMethodAnnotation(AccessRule.class);
            if(rule==null)throw AuthenticationLab.failure(HttpStatus.FORBIDDEN,"UNDECLARED_POLICY");
            if(rule.value()==CommunityAccessPolicy.Permission.PUBLIC)return true;
            @SuppressWarnings("unchecked") Map<String,String> vars=(Map<String,String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            String profile=vars.get("profile"),mode=vars.get("mode");
            if(mode!=null&&!Set.of("fixed","vuln").contains(mode))throw AuthenticationLab.failure(HttpStatus.NOT_FOUND,"MODE_NOT_FOUND");
            var identity=auth.authenticate(profile,request);request.setAttribute(AuthenticationLab.IDENTITY,identity);
            auth.require(identity,"vuln".equals(mode)?CommunityAccessPolicy.Permission.MEMBER:rule.value());
            if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()))auth.csrf(identity,request);
            return true;
        }catch(ResponseStatusException error){
            response.setStatus(error.getStatusCode().value());response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(json.writeValueAsString(Map.of("error",Objects.toString(error.getReason(),"ACCESS_DENIED"),"trainingOnly",true)));return false;
        }
    }
}
