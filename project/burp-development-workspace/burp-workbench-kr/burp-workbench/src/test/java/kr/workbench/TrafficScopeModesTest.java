package kr.workbench;

import burp.api.montoya.*;
import burp.api.montoya.burpsuite.BurpSuite;
import burp.api.montoya.scope.*;
import burp.api.montoya.core.*;
import burp.api.montoya.extension.*;
import burp.api.montoya.persistence.*;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.http.message.responses.*;
import burp.api.montoya.internal.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Mode and Scope notifications exercised through the HTTP callbacks and production Swing UI. */
public final class TrafficScopeModesTest extends WorkbenchTest {
    static String config(String... prefixes){return MetadataExport.json(Map.of("target",Map.of("scope",Map.of("advanced_mode",false,"include",Arrays.stream(prefixes).map(p->Map.of("enabled",true,"prefix",p)).toList()))));}
    static final class Fixture implements AutoCloseable {
        final AtomicReference<String> json=new AtomicReference<>(config("https://burp.test/api/"));
        final AtomicReference<ScopeChangeHandler> handler=new AtomicReference<>();
        final AtomicReference<ExtensionUnloadingHandler> unload=new AtomicReference<>();
        final AtomicInteger deregistered=new AtomicInteger(),mutations=new AtomicInteger();
        final Map<String,String> saved=new HashMap<>();
        final WorkbenchExtension ext=new WorkbenchExtension();
        final MontoyaApi api;
        Fixture()throws Exception {this(false);}
        Fixture(boolean omitScopeExport)throws Exception {
            ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));installTrafficPacketFactory();
            var registration=proxy(Registration.class,(p,m,a)->{if(m.getName().equals("deregister"))deregistered.incrementAndGet();return def(m);});
            var scope=proxy(Scope.class,(p,m,a)->switch(m.getName()){
                case "registerScopeChangeHandler"->{handler.set((ScopeChangeHandler)a[0]);yield registration;}
                case "isInScope"->{String url=(String)a[0];yield !url.contains("/excluded")&&BurpScopeConfig.parse(json.get()).stream().anyMatch(e->e.enabled()&&e.matches(url));}
                case "includeInScope","excludeFromScope"->{mutations.incrementAndGet();yield null;}
                default->def(m);
            });
            var suite=proxy(BurpSuite.class,(p,m,a)->m.getName().equals("exportProjectOptionsAsJson")?(omitScopeExport?"{}":json.get()):def(m));
            var data=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){case "getString"->saved.get(a[0]);case "setString"->{saved.put((String)a[0],(String)a[1]);yield null;}default->def(m);});
            var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?data:PayloadIntegrationTest.host(m.getReturnType()));
            var extension=proxy(Extension.class,(p,m,a)->{if(m.getName().equals("registerUnloadingHandler"))unload.set((ExtensionUnloadingHandler)a[0]);return m.getReturnType().isInterface()?PayloadIntegrationTest.host(m.getReturnType()):def(m);});
            api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){case "scope"->scope;case "burpSuite"->suite;case "persistence"->persistence;case "extension"->extension;default->PayloadIntegrationTest.host(m.getReturnType());});
            ext.initialize(api);edt(()->{});edt(()->((javax.swing.Timer)unchecked(ext,"timer")).stop());
        }
        WorkbenchScope scope(){return (WorkbenchScope)unchecked(ext,"localScope");}
        JTable table(){return (JTable)unchecked(ext,"scopeTable");}
        JButton mode(){return (JButton)unchecked(ext,"captureButton");}
        void changed(String next)throws Exception {json.set(next);handler.get().scopeChanged(null);edt(()->{});}
        void send(String url)throws Exception {
            HttpRequest q=request(url,"GET",false,List.of());HttpResponse r=response(200,"{}");
            ToolSource source=proxy(ToolSource.class,(p,m,a)->m.getName().equals("toolType")?ToolType.PROXY:def(m));
            var event=proxy(HttpResponseReceived.class,(p,m,a)->switch(m.getName()){case "initiatingRequest"->q;case "toolSource"->source;case "annotations"->null;default->HttpResponse.class.getMethod(m.getName(),m.getParameterTypes()).invoke(r,a);});
            ext.handleHttpResponseReceived(event);
        }
        int queued(){return ((Queue<?>)unchecked(ext,"incoming")).size();}
        void click(String name)throws Exception {edt(()->((JButton)UiLayoutTest.named((JPanel)unchecked(ext,"scopeContent"),name)).doClick(0));}
        @Override public void close()throws Exception {unload.get().extensionUnloaded();edt(()->{});}
    }
    static Object unchecked(Object o,String field){try{return get(o,field);}catch(Exception e){throw new RuntimeException(e);}}
    public static void main(String[] args)throws Exception {
        try(Fixture f=new Fixture()) {
            check(f.table().getRowCount()==1&&f.table().getValueAt(0,2).equals("https://burp.test/api/"),"existing Burp Scope loaded without waiting for traffic");
            check(Boolean.TRUE.equals(f.table().getValueAt(0,0)),"new native Scope starts checked");
            edt(()->{var root=(JPanel)unchecked(f.ext,"root");check(PacketUiTest.buttons(((JTabbedPane)unchecked(f.ext,"tabs")).getComponentAt(0)).stream().noneMatch(b->Set.of("Scope만","SCOPE만","검색","검색 초기화","목록 비우기","내보내기","수집 ON").contains(b)),"traffic controls replaced by one mode button and live search");});
            f.send("https://outside.test/a");check(f.queued()==1&&f.mode().getText().equals("ON"),"ON captures outside scope");
            edt(()->f.mode().doClick(0));f.send("https://burp.test/api/a");check(f.queued()==1&&f.mode().getText().equals("OFF"),"OFF rejects new packets while retaining previous packets");
            edt(()->f.mode().doClick(0));f.send("https://outside.test/a");f.send("https://burp.test/api/a");f.send("https://burp.test/api/excluded");
            check(f.queued()==2&&f.mode().getText().equals("SCOPE"),"SCOPE captures checked rules and respects native exclusions");
            edt(()->f.table().setValueAt(false,0,0));f.send("https://burp.test/api/b");check(f.queued()==2,"unchecked Scope immediately stops capture");check(WorkbenchScope.registeredRules(f.api).isEmpty(),"disabled scopes are excluded from shared target choices");
            f.changed(config("https://burp.test/api/","https://new.test/"));
            check(f.table().getRowCount()==2&&!f.scope().enabled(f.scope().rules().get(0)),"Add to scope notification imports URL without re-enabling unchecked rows");
            f.send("https://new.test/a");check(f.queued()==3,"native Add to scope capture starts automatically");
            edt(()->{f.table().getRowSorter().toggleSortOrder(2);f.table().getRowSorter().toggleSortOrder(2);f.table().setRowSelectionInterval(0,0);});
            f.click("wb.traffic.scope.remove");check(f.table().getRowCount()==1&&!f.scope().matches("https://new.test/a"),"selected delete removes checked rules only, independent of row sorting and highlight");
            f.changed(config("https://burp.test/api/","https://new.test/"));check(f.table().getRowCount()==1,"deleted native entry stays deleted on unrelated Scope notifications");
            String saved=f.scope().save();MontoyaApi reloadApi=proxy(MontoyaApi.class,(p,m,a)->m.invoke(f.api,a));WorkbenchScope restored=WorkbenchScope.install(reloadApi);restored.syncBurp(f.json.get());
            check(restored.rules().size()==1&&!restored.enabled(restored.rules().get(0)),"checkbox and deleted native entry persist across reload");WorkbenchScope.release(reloadApi);
            f.scope().load(saved);f.scope().syncBurp(f.json.get());
            edt(()->((JTextField)UiLayoutTest.named((JPanel)unchecked(f.ext,"scopeContent"),"wb.traffic.scope.url")).setText("https://manual.test/api?secret=hidden"));f.click("wb.traffic.scope.add");
            f.send("https://manual.test/api/child");f.send("https://manual.test/apievil");check(f.queued()==4&&!f.scope().save().contains("hidden"),"URL add activates exact path/descendants and strips query");
            f.click("wb.traffic.scope.clear");check(f.table().getRowCount()==0,"bulk delete removes unchecked and checked rules");
            f.changed(f.json.get());f.send("https://new.test/a");check(f.table().getRowCount()==0&&f.queued()==4,"bulk delete suppresses native entries and empty SCOPE captures nothing");
            f.changed(config());f.changed(config("https://new.test/"));check(f.table().getRowCount()==1,"removing then re-adding native Scope restores it as a new checked entry");
            edt(()->f.mode().doClick(0));f.send("https://outside.test/new");check(f.queued()==5&&f.mode().getText().equals("ON"),"third click completes ON OFF SCOPE ON cycle");
            check(f.mutations.get()==0,"Workbench Scope edits never mutate global Burp Scope");
        }
        try(Fixture f=new Fixture(true)) {
            check(f.scope().rules().isEmpty(),"missing native Scope export is supported without inventing unobserved URLs");
            edt(()->{f.mode().doClick(0);f.mode().doClick(0);});
            f.send("https://burp.test/api/items?secret=hidden");
            check(f.queued()==1&&f.scope().rules().get(0).url().equals("https://burp.test/api/"),"native predicate recovers an observed in-scope prefix when export omits target");
            check(!f.scope().save().contains("hidden"),"observed Scope fallback never persists query values");
            edt(()->f.table().setValueAt(false,0,0));f.send("https://burp.test/api/new-child");
            check(f.queued()==1&&f.scope().rules().size()==1,"unchecked fallback prefix cannot be bypassed by a newly observed child");
            f.changed(config("https://burp.test/api/","https://new.test/"));f.send("https://burp.test/api/still-unchecked");
            check(f.queued()==1&&!f.scope().enabled(f.scope().rules().get(0)),"fallback checkbox survives native Scope notifications");
            edt(()->f.table().setValueAt(true,0,0));f.click("wb.traffic.scope.remove");f.send("https://burp.test/api/deleted");
            check(f.queued()==1&&f.scope().rules().isEmpty(),"deleted fallback prefix cannot be resurrected by incoming traffic");
            f.changed(config());f.changed(config("https://burp.test/api/"));f.send("https://burp.test/api/readded");
            check(f.queued()==2,"native removal and re-addition can register a fresh fallback prefix");
            f.send("https://burp.test/api/excluded");f.send("https://outside.test/a");
            check(f.queued()==2,"fallback still enforces native exclusions and rejects outside traffic");
        }
        var advanced=BurpScopeConfig.parse("{\"target\":{\"scope\":{\"advanced_mode\":true,\"include\":[{\"enabled\":true,\"protocol\":\"https\",\"host\":\"^api[.]test$\",\"port\":\"^8443$\",\"file\":\"^/v1/\"}]}}}");
        check(advanced.get(0).matches("https://api.test:8443/v1/items")&&!advanced.get(0).matches("https://api.test/v1/items")&&!advanced.get(0).matches("https://api.test.evil:8443/v1/items"),"advanced native constraints retain host port and path matching");
        System.out.println("TrafficScopeModesTest: "+checks+" checks passed");
    }
}
