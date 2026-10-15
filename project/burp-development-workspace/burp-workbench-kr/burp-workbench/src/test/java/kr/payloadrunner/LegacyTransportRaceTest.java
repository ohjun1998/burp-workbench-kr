package kr.payloadrunner;

import burp.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.*;

/** A completed FutureTask must not leave or later clear another request's busy flag. */
public final class LegacyTransportRaceTest {
 @SuppressWarnings("unchecked") static <T>T proxy(Class<T> c,java.lang.reflect.InvocationHandler h){return (T)Proxy.newProxyInstance(c.getClassLoader(),new Class[]{c},h);}
 public static void run()throws Exception {
  byte[] response="HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  AtomicInteger calls=new AtomicInteger(),active=new AtomicInteger(),maximum=new AtomicInteger();AtomicBoolean fail=new AtomicBoolean();
  IHttpRequestResponse packet=proxy(IHttpRequestResponse.class,(p,m,a)->m.getName().equals("getResponse")?response:null);
  IBurpExtenderCallbacks callbacks=proxy(IBurpExtenderCallbacks.class,(p,m,a)->{
   if(!m.getName().equals("makeHttpRequest"))return null;
   int n=active.incrementAndGet();maximum.accumulateAndGet(n,Math::max);
   try{calls.incrementAndGet();if(fail.getAndSet(false))throw new IllegalArgumentException("controlled callback failure");return packet;}
   finally{active.decrementAndGet();}
  });
  IHttpService service=proxy(IHttpService.class,(p,m,a)->null);AtomicBoolean stop=new AtomicBoolean();
  try(PayloadHttp transport=new PayloadHttp(callbacks,null)) {
   int rejected=0;boolean idle=true;
   for(int i=0;i<5000;i++) {
    try{transport.send(service,new byte[0],1000,stop);idle&=!transport.busy();}
    catch(IllegalStateException e){rejected++;Thread.sleep(1);}
   }
   EngineTest.assertTest("5000 completed sequential legacy requests have no false busy rejection",rejected==0&&calls.get()==5000);
   EngineTest.assertTest("legacy completion publishes idle state before returning",idle&&!transport.busy());
   EngineTest.assertTest("legacy transport never overlaps callback execution",maximum.get()==1);
   fail.set(true);boolean failed=false;try{transport.send(service,new byte[0],1000,stop);}catch(IllegalArgumentException e){failed=true;}
   EngineTest.assertTest("legacy callback failure releases its busy state",failed&&!transport.busy());
   EngineTest.assertTest("legacy callback failure permits the next request",transport.send(service,new byte[0],1000,stop)==response&&!transport.busy());
  }
 }
}
