import java.nio.file.*;
import javax.servlet.http.HttpServlet;
import javax.servlet.annotation.WebServlet;
import org.apache.catalina.startup.Tomcat;

/** Hosts the unmodified, explicitly selected upstream servlets on loopback. */
public class BenchmarkXssServer {
  public static void main(String[] args) throws Exception {
    Tomcat tomcat = new Tomcat();tomcat.setBaseDir(args[0]);tomcat.setHostname("127.0.0.1");tomcat.setPort(0);
    tomcat.getConnector().setProperty("address","127.0.0.1");
    var context = tomcat.addContext("/benchmark",Files.createDirectories(Path.of(args[0],"web")).toString());
    for (String id : Files.readAllLines(Path.of(args[2]))) {
      Class<?> type=Class.forName("org.owasp.benchmark.testcode."+id);
      Tomcat.addServlet(context,id,(HttpServlet)type.getConstructor().newInstance());
      for(String path:type.getAnnotation(WebServlet.class).value())context.addServletMappingDecoded(path,id);
    }
    tomcat.start();Files.writeString(Path.of(args[1]),Integer.toString(tomcat.getConnector().getLocalPort()));
    tomcat.getServer().await();
  }
}
