package kr.featuremapper;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Authenticated loopback metadata bridge. Never forwards or modifies target requests. */
public final class BrowserBridge implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"feature-mapper-browser");t.setDaemon(true);return t;});
    final String token;
    BrowserBridge(Consumer<Map<String,Object>> sink)throws IOException{
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);token=HexFormat.of().formatHex(random);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setExecutor(executor);
        server.createContext("/health",x->{if(!authorized(x)){reply(x,401,"unauthorized");return;}reply(x,200,"Feature Mapper KR 0.1.0");});
        server.createContext("/event",x->{try{
            if(!authorized(x)){reply(x,401,"unauthorized");return;}
            if(!x.getRequestMethod().equals("POST")){reply(x,405,"POST required");return;}
            if(!String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).startsWith("application/json")){reply(x,415,"JSON required");return;}
            byte[] body=x.getRequestBody().readNBytes(65537);if(body.length>65536){reply(x,413,"event too large");return;}
            Object value=Json.parse(new String(body,StandardCharsets.UTF_8),65536,1000);if(!(value instanceof Map<?,?>)){reply(x,400,"object required");return;}
            @SuppressWarnings("unchecked") Map<String,Object> event=(Map<String,Object>)value;sink.accept(event);reply(x,200,"accepted");
        }catch(Exception e){reply(x,400,"invalid event");}finally{x.close();}});
        server.start();
    }
    private boolean authorized(HttpExchange x){String header=x.getRequestHeaders().getFirst("X-Feature-Mapper-Token");return header!=null&&MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),header.getBytes(StandardCharsets.US_ASCII));}
    private static void reply(HttpExchange x,int code,String value)throws IOException{byte[] body=value.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(code,body.length);try(OutputStream out=x.getResponseBody()){out.write(body);}}
    String baseUrl(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public void close(){server.stop(0);executor.shutdownNow();}
}
