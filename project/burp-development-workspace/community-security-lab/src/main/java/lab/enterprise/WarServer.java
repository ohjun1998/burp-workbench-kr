package lab.enterprise;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.core.StandardContext;
import java.nio.file.*;
public class WarServer {
 public static void main(String[] args)throws Exception{
  var t=new Tomcat();t.setBaseDir(Files.createTempDirectory("war-tomcat").toString());t.setPort(8088);t.getConnector().setProperty("address","127.0.0.1");
  var c=(StandardContext)t.addWebapp("",Path.of(args[0]).toAbsolutePath().toString());c.setDefaultWebXml(t.noDefaultWebXmlPath());
  t.start();t.getServer().await();
 }
}
