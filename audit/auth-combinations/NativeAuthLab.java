package kr.workbench;

import burp.*;
import burp.api.montoya.*;
import burp.api.montoya.http.handler.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Load the unmodified shipped extension into actual Burp with a private profile. */
public final class NativeAuthLab implements IBurpExtender,BurpExtension {
    private final BurpExtender delegate=new BurpExtender();
    private String version="";
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks){version=String.join(" ",callbacks.getBurpVersion());delegate.registerExtenderCallbacks(callbacks);}
    public void initialize(MontoyaApi api){
        try{delegate.initialize(api);}catch(Throwable error){finish(api,null,error);return;}
        new Thread(()->{
            try{
                String base=System.getProperty("qa.base");AtomicInteger transport=new AtomicInteger();
                api.http().registerHttpHandler(new HttpHandler(){public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent q){if(q.url().startsWith(base+"/"))transport.incrementAndGet();return RequestToBeSentAction.continueWith(q);}public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived r){return ResponseReceivedAction.continueWith(r);}});
                var field=BurpExtender.class.getDeclaredField("workbench");field.setAccessible(true);var extension=(WorkbenchExtension)field.get(delegate);
                var auth=WorkbenchExtension.class.getDeclaredField("authCompare");auth.setAccessible(true);var panel=(AuthComparePanel)auth.get(extension);
                var result=new LinkedHashMap<String,Object>(new AuthLabCases(api,panel,base,Path.of(System.getProperty("qa.out"))).run());result.put("transport","actual Burp Montoya HTTP");result.put("burpVersion",version);result.put("javaVersion",System.getProperty("java.version"));result.put("nativeExtensionLoaded",true);result.put("targetHttpRequests",transport.get());finish(api,result,null);
            }catch(Throwable error){finish(api,null,error);}
        },"native-auth-lab").start();
    }
    private static void finish(MontoyaApi api,Map<String,Object> result,Throwable error){
        try{
            Path out=Path.of(System.getProperty("qa.out"));Files.createDirectories(out);
            if(error!=null){error.printStackTrace();result=Map.of("result","failed","transport","actual Burp Montoya HTTP","error",error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),""));}
            Files.writeString(out.resolve("extension-results.json"),MetadataExport.json(result)+"\n");
            if(api!=null)api.extension().unload();
        }catch(Throwable failure){failure.printStackTrace();error=failure;}
        System.out.println(error==null?"NATIVE_AUTH_LAB_SUCCESS":"NATIVE_AUTH_LAB_FAILED");System.exit(error==null?0:1);
    }
}
