package lab.enterprise;
import org.apache.catalina.startup.Tomcat;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import java.nio.file.Files;
public class DevServer {
 public static void main(String[] args)throws Exception{
  var t=new Tomcat();t.setBaseDir(Files.createTempDirectory("enterprise-tomcat").toString());t.setPort(Integer.parseInt(AppConfig.env("PORT","8088")));t.getConnector().setProperty("address","127.0.0.1");
  var c=t.addContext("",Files.createTempDirectory("enterprise-web").toString());c.setParentClassLoader(DevServer.class.getClassLoader());
  var a=new AnnotationConfigWebApplicationContext();a.register(AppConfig.class);
  Tomcat.addServlet(c,"mvc",new DispatcherServlet(a)).setLoadOnStartup(1);c.addServletMappingDecoded("/","mvc");
  t.start();t.getServer().await();
 }
}
