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
        final AtomicInteger responseReads=new AtomicInteger();
        final AtomicBoolean nativeFailure=new AtomicBoolean();
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
                case "isInScope"->{if(nativeFailure.get())throw new IllegalStateException("unavailable");String url=(String)a[0];yield !url.contains("/excluded")&&BurpScopeConfig.parse(json.get()).stream().anyMatch(e->e.enabled()&&e.matches(url));}
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
            var event=proxy(HttpResponseReceived.class,(p,m,a)->switch(m.getName()){case "initiatingRequest"->q;case "toolSource"->source;case "annotations"->null;default->{if(Set.of("body","bodyToString","toByteArray").contains(m.getName()))responseReads.incrementAndGet();yield HttpResponse.class.getMethod(m.getName(),m.getParameterTypes()).invoke(r,a);}});
            ext.handleHttpResponseReceived(event);
        }
        int rows(){return ((List<?>)unchecked(ext,"traffic")).size();}
        void bring(String url)throws Exception {
            HttpRequest q=request(url,"GET",false,List.of());HttpResponse response=response(200,"{}");registerTrafficPacket(q,response);
            var rr=proxy(burp.api.montoya.http.message.HttpRequestResponse.class,(p,m,a)->switch(m.getName()){case "request"->q;case "response"->response;default->def(m);});
            var event=proxy(burp.api.montoya.ui.contextmenu.ContextMenuEvent.class,(p,m,a)->switch(m.getName()){case "selectedRequestResponses"->List.of(rr);case "messageEditorRequestResponse"->Optional.empty();default->def(m);});
            edt(()->((JMenuItem)((JMenu)ext.provideMenuItems(event).get(0)).getMenuComponent(0)).doClick(0));edt(()->{});
        }
        int queued(){return ((Queue<?>)unchecked(ext,"incoming")).size();}
        void click(String name)throws Exception {edt(()->((JButton)UiLayoutTest.named((JPanel)unchecked(ext,"scopeContent"),name)).doClick(0));}
        @Override public void close()throws Exception {unload.get().extensionUnloaded();edt(()->{});}
    }
    static String excludedConfig(String prefix){return MetadataExport.json(Map.of("target",Map.of("scope",Map.of("advanced_mode",false,"include",List.of(Map.of("enabled",true,"prefix","https://burp.test/api/")),"exclude",List.of(Map.of("enabled",true,"prefix",prefix))))));}
    static void select(Fixture f,String url)throws Exception {
        edt(()->{for(int row=0;row<f.table().getRowCount();row++)if(url.equals(f.table().getValueAt(row,2))){f.table().setRowSelectionInterval(row,row);return;}throw new AssertionError("missing scope "+url);});
    }
    static Object unchecked(Object o,String field){try{return get(o,field);}catch(Exception e){throw new RuntimeException(e);}}
    public static void main(String[] args)throws Exception {
        try(Fixture f=new Fixture()) {
            check(f.table().getRowCount()==1&&f.table().getValueAt(0,2).equals("https://burp.test/api/"),"existing Burp Scope loaded without waiting for traffic");
            check(Boolean.TRUE.equals(f.table().getValueAt(0,0)),"new native Scope starts checked");
            edt(()->{var root=(JPanel)unchecked(f.ext,"root");check(PacketUiTest.buttons(((JTabbedPane)unchecked(f.ext,"tabs")).getComponentAt(0)).stream().noneMatch(b->Set.of("Scope만","SCOPE만","검색","검색 초기화","목록 비우기","내보내기","수집 ON").contains(b)),"traffic controls replaced by one mode button and live search");});
            check(Arrays.equals(WorkbenchExtension.CaptureMode.values(),new WorkbenchExtension.CaptureMode[]{WorkbenchExtension.CaptureMode.OFF,WorkbenchExtension.CaptureMode.SCOPE}),"only OFF and SCOPE exist");
            f.send("https://outside.test/a");f.send("https://burp.test/api/a");check(f.queued()==0&&f.mode().getText().equals("OFF"),"default OFF rejects native and outside packets");
            check(((DiskTrafficStore)unchecked(f.ext,"trafficStore")).size()==0,"OFF writes no traffic to disk");
            check(f.responseReads.get()==0,"OFF does not pass responses to passive analyzers");
            f.bring("https://burp.test/api/manual-off");check(f.rows()==0,"OFF also rejects HTTP history manual import");
            edt(()->f.mode().doClick(0));f.send("https://outside.test/a");check(f.responseReads.get()==0,"SCOPE outsiders do not enter passive analyzers");f.send("https://burp.test/api/a");f.send("https://burp.test/api/excluded");
            check(f.queued()==1&&f.mode().getText().equals("SCOPE"),"SCOPE captures checked rules and respects native exclusions");
            edt(()->f.table().setValueAt(false,0,0));f.send("https://burp.test/api/b");check(f.queued()==1,"unchecked Scope immediately stops capture");check(WorkbenchScope.registeredRules(f.api).isEmpty(),"disabled scopes are excluded from shared target choices");
            f.changed(config("https://burp.test/api/","https://new.test/"));
            check(f.table().getRowCount()==2&&!f.scope().enabled(f.scope().rules().get(0)),"Add to scope notification imports URL without re-enabling unchecked rows");
            f.send("https://new.test/a");check(f.queued()==2,"native Add to scope capture starts automatically");
            edt(()->{f.table().getRowSorter().toggleSortOrder(2);f.table().getRowSorter().toggleSortOrder(2);f.table().setRowSelectionInterval(0,0);});
            f.click("wb.traffic.scope.remove");check(f.table().getRowCount()==1&&!f.scope().matches("https://new.test/a"),"selected delete removes checked rules only, independent of row sorting and highlight");
            f.changed(config("https://burp.test/api/","https://new.test/"));check(f.table().getRowCount()==1,"deleted native entry stays deleted on unrelated Scope notifications");
            String saved=f.scope().save();MontoyaApi reloadApi=proxy(MontoyaApi.class,(p,m,a)->m.invoke(f.api,a));WorkbenchScope restored=WorkbenchScope.install(reloadApi);restored.syncBurp(f.json.get());
            check(restored.rules().size()==1&&!restored.enabled(restored.rules().get(0)),"checkbox and deleted native entry persist across reload");WorkbenchScope.release(reloadApi);
            f.scope().load(saved);f.scope().syncBurp(f.json.get());
            f.click("wb.traffic.scope.clear");check(f.table().getRowCount()==0,"bulk delete removes unchecked and checked rules");
            f.changed(f.json.get());f.send("https://new.test/a");check(f.table().getRowCount()==0&&f.queued()==2,"bulk delete suppresses native entries and empty SCOPE captures nothing");
            f.changed(config());f.changed(config("https://new.test/"));check(f.table().getRowCount()==1,"removing then re-adding native Scope restores it as a new checked entry");
            f.bring("https://new.test/manual");check(f.rows()==3,"SCOPE manual import accepts a native URL and publishes retained live rows");
            f.nativeFailure.set(true);f.send("https://new.test/unavailable");check(f.queued()==0&&f.rows()==3,"native Scope lookup failures reject capture");f.nativeFailure.set(false);
            f.json.set(config());f.send("https://new.test/stale");check(f.queued()==0&&f.rows()==3,"native removal rejects stale local rows immediately before notification");
            edt(()->f.mode().doClick(0));f.send("https://outside.test/new");f.send("https://new.test/new");check(f.queued()==0&&f.rows()==3&&f.mode().getText().equals("OFF"),"second click completes OFF SCOPE OFF cycle without discarding retained traffic");
            check(f.mutations.get()==0,"Workbench Scope edits never mutate global Burp Scope");
        }
        try(Fixture f=new Fixture()) {
            JTextField input=(JTextField)unchecked(f.ext,"scopeUrlInput");
            JButton edit=(JButton)unchecked(f.ext,"scopeEditButton");
            check(!edit.isEnabled(),"URL edit disabled without one manual selection");
            edt(()->input.setText("https://manual.test/api?secret=hidden"));f.click("wb.traffic.scope.add");
            f.send("https://manual.test/api/child");f.bring("https://manual.test/api/child");
            check(f.queued()==0&&f.rows()==0,"adding manual scope leaves OFF capture and import blocked");
            check(!f.scope().save().contains("hidden"),"manual scope strips query secrets");
            edt(()->f.mode().doClick(0));
            f.send("https://manual.test/api/child");f.send("https://manual.test/apievil");f.send("https://manual.test.evil/api");
            check(f.queued()==1,"URL added in production Scope UI immediately authorizes only its path and descendants");
            select(f,"https://manual.test/api");
            check(edit.isEnabled()&&input.getText().equals("https://manual.test/api"),"selecting manual URL loads the editor");
            edt(()->f.table().setValueAt(false,f.table().getSelectedRow(),0));
            f.send("https://manual.test/api/unchecked");check(f.queued()==1,"unchecking manual scope immediately stops capture");
            edt(()->{f.table().getRowSorter().toggleSortOrder(2);f.table().getRowSorter().toggleSortOrder(2);});
            select(f,"https://manual.test/api");edt(()->input.setText("https://edited.test/v2?token=hidden"));
            f.changed(config("https://burp.test/api/","https://new.test/"));
            check(input.getText().equals("https://edited.test/v2?token=hidden"),"native notifications preserve an in-progress manual edit");
            f.click("wb.traffic.scope.edit");
            var updated=f.scope().rules().stream().filter(r->r.url().equals("https://edited.test/v2")).findFirst().orElseThrow();
            check(!f.scope().enabled(updated)&&f.table().getValueAt(f.table().getSelectedRow(),2).equals(updated.url()),"sorted URL edit preserves unchecked state and selection");
            f.send("https://manual.test/api/old");f.send("https://edited.test/v2/disabled");check(f.queued()==1,"edited old URL revoked and new unchecked URL remains blocked");
            edt(()->f.table().setValueAt(true,f.table().getSelectedRow(),0));
            f.send("https://edited.test/v2/item");f.send("https://edited.test/v2evil");check(f.queued()==2,"enabling edited URL captures new descendants with path boundaries");
            f.bring("https://outside.test/manual");check(f.rows()==0,"unregistered manual import stays blocked");
            f.bring("https://edited.test/v2/manual");check(f.rows()==3,"manual scope authorizes History import through the same capture gate");
            String saved=f.scope().save();WorkbenchScope copy=WorkbenchScope.install(proxy(MontoyaApi.class,(p,m,a)->def(m)));copy.load(saved);
            check(copy.matchesTraffic("https://edited.test/v2/reloaded")&&!copy.matchesTraffic("https://manual.test/api/old")&&!saved.contains("hidden"),"edited URL and checkbox persist without query secrets");
            select(f,"https://burp.test/api/");check(!edit.isEnabled(),"native Scope rows direct editing to Burp instead of changing global configuration");
            edt(()->f.table().setRowSelectionInterval(0,1));check(!edit.isEnabled(),"multiple row selection disables URL edit");
            f.changed(excludedConfig("https://edited.test/v2/private"));
            f.send("https://edited.test/v2/private/item");check(f.queued()==0&&f.rows()==3,"manual URLs respect enabled exclusions exported from native Burp Scope");
            f.send("https://edited.test/v2/public");check(f.queued()==1,"native exclusion does not block other paths in manual scope");
            select(f,"https://edited.test/v2");edt(()->input.setText("https://edited.test/v3"));f.click("wb.traffic.scope.edit");
            f.send("https://edited.test/v2/revoked");f.send("https://edited.test/v3/new");
            check(f.queued()==2&&f.scope().enabled(f.scope().rules().stream().filter(r->r.url().equals("https://edited.test/v3")).findFirst().orElseThrow()),"editing an enabled URL atomically revokes old capture and enables its replacement");
            check(f.mutations.get()==0,"manual scope registration and edit never mutate global Burp Scope");
        }
        try(Fixture f=new Fixture(true)) {
            check(f.scope().rules().isEmpty(),"missing native Scope export is supported without inventing unobserved URLs");
            edt(()->f.mode().doClick(0));
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
            f.scope().add("https://manual-fallback.test/api",false);f.send("https://manual-fallback.test/api/child");
            check(f.queued()==3,"manual Scope capture also works when Burp cannot export Scope settings");
        }
        var advanced=BurpScopeConfig.parse("{\"target\":{\"scope\":{\"advanced_mode\":true,\"include\":[{\"enabled\":true,\"protocol\":\"https\",\"host\":\"^api[.]test$\",\"port\":\"^8443$\",\"file\":\"^/v1/\"}]}}}");
        check(advanced.get(0).matches("https://api.test:8443/v1/items")&&!advanced.get(0).matches("https://api.test/v1/items")&&!advanced.get(0).matches("https://api.test.evil:8443/v1/items"),"advanced native constraints retain host port and path matching");
        System.out.println("TrafficScopeModesTest: "+checks+" checks passed");
    }
}
