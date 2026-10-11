import java.net.*;
public class HealthCheck { public static void main(String[] args)throws Exception { var c=(HttpURLConnection)URI.create("http://127.0.0.1:8080/health").toURL().openConnection(); c.setConnectTimeout(2000); c.setReadTimeout(2000); if(c.getResponseCode()!=200) System.exit(1); }}
