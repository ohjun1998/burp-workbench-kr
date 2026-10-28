package kr.payloadrunner;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;

/** Optional loopback-only HTTP callback collector. It never requests its own probe URL. */
public final class CallbackProbe implements AutoCloseable {
 public record Hit(String source,long at){}
 private final ServerSocket listener;private final String base;
 private final ConcurrentMap<String,Hit> hits=new ConcurrentHashMap<>();private final Set<String> expected=ConcurrentHashMap.newKeySet();
 private volatile boolean closed;private volatile Socket active;private final Thread worker;
 public CallbackProbe(int port)throws IOException{
  listener=new ServerSocket();listener.setReuseAddress(true);try{listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port));listener.setSoTimeout(300);}catch(IOException e){listener.close();throw e;}
  base="http://127.0.0.1:"+listener.getLocalPort();worker=new Thread(this::serve,"PayloadRunner-HTTP-Callback");worker.setDaemon(true);worker.start();
 }
 public String url(String marker){if(!marker.matches("PR_[0-9a-f]{16}")||expected.size()>=1000)throw new IllegalArgumentException("callback marker/limit");expected.add(marker);return base+"/pr/"+marker;}
 public Hit await(String marker,int millis,java.util.concurrent.atomic.AtomicBoolean stop)throws InterruptedException{long end=System.nanoTime()+millis*1_000_000L;while(!closed&&!stop.get()&&System.nanoTime()<end){Hit h=hits.get(marker);if(h!=null)return h;Thread.sleep(25);}return hits.get(marker);}
 private void serve(){while(!closed){try(Socket s=listener.accept()){
   active=s;if(closed)continue;
   s.setSoTimeout(600);var in=s.getInputStream();var bytes=new ByteArrayOutputStream();for(int n=0;n<2048;n++){int b=in.read();if(b<0||b=='\n')break;bytes.write(b);}String line=bytes.toString(StandardCharsets.US_ASCII).strip();String[] parts=line.split(" ");String token=parts.length==3&&parts[0].equals("GET")&&parts[1].startsWith("/pr/")?parts[1].substring(4):"";
   int consecutive=1;for(int n=0;n<8192;n++){int b=in.read();if(b<0)break;if(b=='\n')consecutive++;else if(b!='\r')consecutive=0;if(consecutive>=2)break;}
   boolean known=expected.contains(token);if(known)hits.putIfAbsent(token,new Hit(s.getInetAddress().getHostAddress(),System.currentTimeMillis()));
   byte[] body=(known?"PR_CALLBACK_OK":"unknown probe").getBytes(StandardCharsets.US_ASCII);s.getOutputStream().write(("HTTP/1.1 "+(known?"200 OK":"404 Not Found")+"\r\nContent-Type: text/plain\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(body);s.getOutputStream().flush();
  }catch(SocketTimeoutException ignored){}catch(IOException e){if(closed)return;}finally{active=null;}}}
 public void close(){closed=true;try{listener.close();}catch(IOException ignored){}
  Socket connection=active;if(connection!=null)try{connection.close();}catch(IOException ignored){}
  worker.interrupt();if(Thread.currentThread()!=worker)try{worker.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  hits.clear();expected.clear();
 }
}
