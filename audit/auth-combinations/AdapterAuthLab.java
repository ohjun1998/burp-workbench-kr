package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static kr.workbench.WorkbenchTest.*;

/** CI fallback: production controller with real loopback HTTP and explicit API adapters. */
public final class AdapterAuthLab {
    public static void main(String[] args)throws Exception{
        BountyFeaturesTest.factory();AtomicInteger transport=new AtomicInteger();
        var client=java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var http=proxy(Http.class,(p,m,a)->{
            if(!m.getName().equals("sendRequest"))return def(m);HttpRequest q=(HttpRequest)a[0];transport.incrementAndGet();
            var builder=java.net.http.HttpRequest.newBuilder(URI.create(q.url())).method(q.method(),q.bodyToString().isEmpty()?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(q.bodyToString()));
            for(var h:q.headers())if(!Set.of("host","content-length").contains(h.name().toLowerCase(Locale.ROOT)))builder.header(h.name(),h.value());
            var r=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());var headers=r.headers().map().entrySet().stream().flatMap(e->e.getValue().stream().map(value->header(e.getKey(),value))).toList();
            return AuthRecordTest.rr(q,BountyFeaturesTest.rs(r.statusCode(),r.body(),headers));
        });
        var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));AtomicReference<AuthComparePanel> box=new AtomicReference<>();edt(()->box.set(new AuthComparePanel(api,List::of)));
        Path out=Path.of(args[1]);try{var result=new LinkedHashMap<String,Object>(new AuthLabCases(api,box.get(),args[0],out).run());result.put("transport","real loopback HTTP with Montoya API adapters");result.put("nativeExtensionLoaded",false);result.put("targetHttpRequests",transport.get());Files.writeString(out.resolve("extension-results.json"),MetadataExport.json(result)+"\n");System.out.println("ADAPTER_AUTH_LAB_SUCCESS");}finally{edt(box.get()::close);WorkbenchScope.release(api);}System.exit(0);
    }
}
