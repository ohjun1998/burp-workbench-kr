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
 @Bean public CommunityAccessPolicy accessPolicy(){return new CommunityAccessPolicy(jdbc());}
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new CommunityAccessInterceptor(accessPolicy()));}
}
