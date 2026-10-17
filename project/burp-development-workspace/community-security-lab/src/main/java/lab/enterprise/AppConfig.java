package lab.enterprise;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@Configuration @EnableWebMvc @ComponentScan("lab.enterprise")
public class AppConfig implements WebMvcConfigurer {
 public static String env(String key,String fallback){return System.getenv().getOrDefault(key,fallback);}
 @Bean public JdbcTemplate jdbc(){var d=new DriverManagerDataSource();String url=env("DB_URL","jdbc:h2:mem:lab;MODE=Oracle;DB_CLOSE_DELAY=-1");d.setDriverClassName(url.startsWith("jdbc:oracle")?"oracle.jdbc.OracleDriver":"org.h2.Driver");d.setUrl(url);d.setUsername(env("DB_USER","sa"));d.setPassword(env("DB_PASSWORD",""));return new JdbcTemplate(d);}
 @Override public void addResourceHandlers(ResourceHandlerRegistry r){r.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/");}
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor(){
  @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler)throws Exception{
   if(!request.getRequestURI().startsWith("/api/"))return true;
   response.setHeader("Cache-Control","no-store");
   var session=request.getSession(false);
   if(session!=null&&session.getAttribute("uid") instanceof Integer id&&!request.getRequestURI().equals("/api/auth/logout")&&jdbc().queryForObject("SELECT COUNT(*) FROM community_account_states WHERE user_id=? AND state='SUSPENDED'",Integer.class,id)>0){
    session.invalidate();response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"error\":\"정지된 계정입니다.\"}");return false;
   }
   return true;
  }
 });}
}
