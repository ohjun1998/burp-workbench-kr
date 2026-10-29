package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.repeater.Repeater;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Method;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/** Same complete eight-tool task for every product: production Swing and original LOOP HTTP, no OS clicks. */
public final class WholeWorkbenchWorkflowAudit extends WorkbenchTest {
  static final String BASE="http://127.0.0.1:18888";
  static final List<Map<String,Object>> assertions=new CopyOnWriteArrayList<>(),actions=new CopyOnWriteArrayList<>(),transport=new CopyOnWriteArrayList<>();
  static final Map<String,Object> modules=new LinkedHashMap<>(),extra=new LinkedHashMap<>();
  static final AtomicInteger calls=new AtomicInteger(),loginCalls=new AtomicInteger(),repeaterPreparations=new AtomicInteger();
  static int failedModules;
  static final AtomicReference<HttpRequest> repeated=new AtomicReference<>();
  static final AtomicReference<Component> dialog=new AtomicReference<>();
  static String module="setup";
  static MontoyaApi api;static WorkbenchExtension extension;static JPanel root;
  static DiskTrafficStore disk;static BountySupportPanel bounty;static AuthComparePanel auth;
  static Object runner;static Path output;static Login alice,bob;
  static final Set<String> realOriginalIds=new LinkedHashSet<>();
  static final Map<String,Set<String>> usedOriginalIds=new LinkedHashMap<>();
  record Login(String cookie,String label){}
  interface Task{void run()throws Exception;}
  static Map<String,Object> map(Object... values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put(values[i].toString(),values[i+1]);return result;}
  static Object value(Object owner,String field){try{return get(owner,field);}catch(Exception error){throw new IllegalStateException("Missing stable product field: "+field,error);}}
  static Object invoke(Object owner,String name,Class<?>[] types,Object... args){try{Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);}catch(Exception error){throw new IllegalStateException("Product operation failed: "+name,error);}}
  static void require(boolean passed,String name){assertions.add(map("module",module,"name",name,"passed",passed));if(!passed)throw new AssertionError(name);}
  static void waitFor(BooleanSupplier condition,String name)throws Exception{long until=System.nanoTime()+Duration.ofSeconds(22).toNanos();while(System.nanoTime()<until){edt(()->{});if(condition.getAsBoolean())return;Thread.sleep(20);}throw new AssertionError("Timeout: "+name);}
  static void act(String name,Runnable action)throws Exception{
    int before=calls.get();long began=System.nanoTime();long[] allocation={-1};
    edt(()->{var bean=java.lang.management.ManagementFactory.getThreadMXBean();long allocated=bean instanceof com.sun.management.ThreadMXBean b&&b.isThreadAllocatedMemorySupported()?b.getThreadAllocatedBytes(Thread.currentThread().getId()):-1;action.run();if(allocated>=0)allocation[0]=((com.sun.management.ThreadMXBean)bean).getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;});
    edt(()->{});actions.add(map("module",module,"name",name,"httpDelta",calls.get()-before,"elapsedMillis",(System.nanoTime()-began)/1e6,"edtAllocatedBytes",allocation[0]));
  }
  static void wholeModule(String id,Task task)throws Exception{
    module=id;int first=assertions.size(),firstAction=actions.size(),before=calls.get(),firstEvidence=bounty.evidence.size();long began=System.nanoTime();
    var record=map("coverage",false,"status","running");modules.put(id,record);
    System.out.println("MODULE_START "+id);
    try{task.run();record.put("coverage",true);record.put("status","PASS");}
    catch(Throwable error){failedModules++;record.put("status","FAIL");record.put("failure",map("class",error.getClass().getSimpleName(),"label",error instanceof AssertionError?Objects.toString(error.getMessage(),""):"Unexpected product/adapter exception"));System.out.println("MODULE_FAIL "+id+" "+error.getClass().getSimpleName());}
    finally{record.putAll(map("actions",List.copyOf(actions.subList(firstAction,actions.size())),"actionCount",actions.size()-firstAction,
        "assertions",List.copyOf(assertions.subList(first,assertions.size())),"actualHttpRequests",calls.get()-before,
        "elapsedMillis",(System.nanoTime()-began)/1e6,"originalIds",List.copyOf(usedOriginalIds.getOrDefault(id,Set.of())),
        "evidencePacketIds",bounty.evidence.subList(Math.min(firstEvidence,bounty.evidence.size()),bounty.evidence.size()).stream().map(BountyEvidence.Item::packetId).distinct().toList(),
        "identityNamespaces","originalIds are Workbench immutable originals used by this module; evidencePacketIds are the separate evidence store namespace"));}
    System.out.println("MODULE_END "+id+" "+record.get("status"));
  }
  static String safeUrl(String url){URI uri=URI.create(url);return EnvironmentCatalog.origin(uri)+Objects.toString(uri.getRawPath(),"/")+(uri.getRawQuery()==null?"":"?[query omitted]");}
  static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
  static int totalEvidence(){return bounty.evidence.size();}
  static void tool(String id){SimpleWorkflowTest.click(root,"wb.simple.tool."+id);}
  static void layout(Component c){if(c instanceof Container p){p.doLayout();for(Component child:p.getComponents())layout(child);}}
  static void selectEnum(Object owner,String field,String name){JComboBox<?> box=(JComboBox<?>)value(owner,field);for(int i=0;i<box.getItemCount();i++)if(box.getItemAt(i).toString().equals(name)){box.setSelectedIndex(i);return;}throw new AssertionError("Missing existing enum option: "+field);}
  static void spin(Object owner,String field,int selected){((JSpinner)value(owner,field)).setValue(selected);}
  static void text(Object owner,String field,String text){((javax.swing.text.JTextComponent)value(owner,field)).setText(text);}
  static void checkBox(Object owner,String field,boolean selected){((AbstractButton)value(owner,field)).setSelected(selected);}
  static void click(Object owner,String field){((AbstractButton)value(owner,field)).doClick(0);}
  static HttpRequest requestFor(String path,Login login){return ScopeInspectionFixture.wireRequest(BASE+path,"GET",login==null?List.of():List.of(header("Cookie",login.cookie())),new byte[0]);}
  static HttpRequestResponse fetch(String path,Login login){return api.http().sendRequest(requestFor(path,login));}
  static void useOriginal(DiskTrafficStore.PacketRef ref){realOriginalIds.add(ref.id());usedOriginalIds.computeIfAbsent(module,unused->new LinkedHashSet<>()).add(ref.id());}
  static DiskTrafficStore.PacketRef capture(HttpRequestResponse packet)throws Exception{
    long before=disk.size(),began=System.nanoTime();int requests=calls.get();importTraffic(extension,packet.request(),packet.response());
    actions.add(map("module",module,"name","import the actual received packet through the History context menu","httpDelta",calls.get()-requests,"elapsedMillis",(System.nanoTime()-began)/1e6,"edtAllocatedBytes",-1));
    waitFor(()->disk.size()>before,"History admission writes exact original");MapperIntegrationTest.idle(extension);
    waitFor(()->extension.trafficBrowser.idle(),"traffic publication/search idle");
    DiskTrafficStore.PacketRef ref=disk.ref(before);useOriginal(ref);
    require(Arrays.equals(ref.requestPrefix(Math.toIntExact(ref.requestLength())),packet.request().toByteArray().getBytes()),"History retains exact original request bytes");
    return ref;
  }
  static void selectTraffic(DiskTrafficStore.PacketRef ref)throws Exception{
    useOriginal(ref);
    act("search for and select the exact captured original",()->{tool("traffic");extension.trafficBrowser.search.setText(ref.url());extension.trafficBrowser.method.setSelectedItem("전체");extension.trafficBrowser.response.setSelectedItem("전체");extension.trafficBrowser.apply();});
    waitFor(()->extension.trafficBrowser.idle(),"filtered traffic ready");
    JTable table=(JTable)value(extension,"logTable");
    act("select exact original row after filtering",()->{int view=table.convertRowIndexToView(Math.toIntExact(ref.sequence()));require(view>=0,"the exact immutable original remains visible in search");table.setRowSelectionInterval(view,view);});
    waitFor(()->value(extension,"current")!=null,"selected traffic preview ready");
  }
  static Login login(String username)throws Exception{
    var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    String body="username="+username+"&password="+java.net.URLEncoder.encode("LabOnly!2026",StandardCharsets.UTF_8);
    var q=java.net.http.HttpRequest.newBuilder(URI.create(BASE+"/api/auth/login")).timeout(Duration.ofSeconds(8)).header("Content-Type","application/x-www-form-urlencoded;charset=UTF-8").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    var response=client.send(q,java.net.http.HttpResponse.BodyHandlers.ofString());loginCalls.incrementAndGet();require(response.statusCode()==200,"normal seed login succeeds for "+username);
    String cookie=cookies.getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).reduce((a,b)->a+"; "+b).orElse("");require(!cookie.isEmpty(),"isolated session exists only in memory for "+username);return new Login(cookie,username);
  }
  static IBurpExtenderCallbacks callbacks(){
    var fallback=(IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class);
    var fallbackHelpers=(IExtensionHelpers)PayloadIntegrationTest.host(IExtensionHelpers.class);
    IExtensionHelpers helpers=proxy(IExtensionHelpers.class,(p,m,a)->{
      if(m.getName().equals("buildHttpService")){String host=a[0].toString();int port=((Number)a[1]).intValue();String scheme=a[2] instanceof Boolean b?(b?"https":"http"):a[2].toString();return proxy(IHttpService.class,(pp,mm,aa)->switch(mm.getName()){case "getHost"->host;case "getPort"->port;case "getProtocol"->scheme;default->def(mm);});}
      if(m.getName().equals("analyzeRequest")&&a.length==2){IHttpService service=(IHttpService)a[0];String raw=new String((byte[])a[1],StandardCharsets.ISO_8859_1);String[] first=raw.split("\\r?\\n",2)[0].split(" ");URL url=new URL(service.getProtocol(),service.getHost(),service.getPort(),first[1]);return proxy(IRequestInfo.class,(pp,mm,aa)->switch(mm.getName()){case "getUrl"->url;case "getMethod"->first[0];case "getBodyOffset"->Math.max(0,raw.indexOf("\r\n\r\n")+4);case "getHeaders"->raw.substring(0,Math.max(0,raw.indexOf("\r\n\r\n"))).lines().toList();case "getParameters"->List.of();default->def(mm);});}
      return m.invoke(fallbackHelpers,a);
    });
    return proxy(IBurpExtenderCallbacks.class,(p,m,a)->m.getName().equals("getHelpers")?helpers:m.getName().equals("isInScope")?WorkbenchScope.contains(api,a[0].toString()):m.invoke(fallback,a));
  }
  static void setupTransport(){
    var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(4)).build();
    Http http=proxy(Http.class,(p,m,a)->{
      if(!m.getName().equals("sendRequest"))return def(m);HttpRequest q=(HttpRequest)a[0];
      if(SwingUtilities.isEventDispatchThread())throw new AssertionError("Target HTTP never runs on the Swing EDT");
      if(!EnvironmentCatalog.origin(URI.create(q.url())).equals(BASE)||!WorkbenchScope.contains(api,q.url()))throw new AssertionError("Target HTTP stays inside the prepared LOOP SCOPE");
      if(!Set.of("GET","HEAD").contains(q.method()))throw new AssertionError("Only normal-login setup may send a POST");
      int index=calls.incrementAndGet();long began=System.nanoTime();
      var builder=java.net.http.HttpRequest.newBuilder(URI.create(q.url())).timeout(Duration.ofSeconds(7)).method(q.method(),java.net.http.HttpRequest.BodyPublishers.noBody());
      for(HttpHeader h:q.headers())if(!Set.of("host","connection","content-length","upgrade","http2-settings").contains(h.name().toLowerCase(Locale.ROOT)))builder.header(h.name(),h.value());
      var received=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());var headers=new ArrayList<HttpHeader>();
      // HttpClient already removes transfer chunk framing. Reconstruct a valid byte message
      // while keeping the real status, body and every non-framing response header.
      received.headers().map().forEach((name,values)->{if(!Set.of("transfer-encoding","content-length").contains(name.toLowerCase(Locale.ROOT)))values.forEach(v->headers.add(header(name,v)));});
      headers.add(header("Content-Length",Integer.toString(received.body().length)));
      HttpResponse response=ScopeInspectionFixture.wireResponse(received.statusCode(),headers,received.body());registerTrafficPacket(q,response);
      String cookie=q.headerValue("Cookie");String account=alice!=null&&alice.cookie().equals(cookie)?"alice":bob!=null&&bob.cookie().equals(cookie)?"bob":cookie==null||cookie.isBlank()?"anonymous":"other configured account";
      transport.add(map("index",index,"module",module,"method",q.method(),"url",safeUrl(q.url()),"status",received.statusCode(),"account",account,"elapsedMillis",(System.nanoTime()-began)/1e6));
      return proxy(HttpRequestResponse.class,(pp,mm,aa)->switch(mm.getName()){case "request"->q;case "response"->response;default->def(mm);});
    });
    var fallback=SimpleWorkflowTest.previewApi();var repeater=proxy(Repeater.class,(p,m,a)->{if(m.getName().equals("sendToRepeater")){repeated.set((HttpRequest)a[0]);repeaterPreparations.incrementAndGet();}return def(m);});
    api=proxy(MontoyaApi.class,(p,m,a)->switch(m.getName()){case "http"->http;case "repeater"->repeater;default->m.invoke(fallback,a);});
  }
  static void traffic()throws Exception{
    var packet=fetch("/api/access/fixed/user/requests/501",alice);require(packet.response().statusCode()==200,"traffic task fetches the authenticated owner's real read-only document");
    long before=disk.size();act("navigate traffic and try History import with capture OFF",()->tool("traffic"));importTraffic(extension,packet.request(),packet.response());
    require(disk.size()==before,"capture OFF prevents History admission");
    act("enable shared SCOPE capture",()->click(extension,"captureButton"));var ref=capture(packet);selectTraffic(ref);
    int requests=calls.get();act("prepare the selected traffic original in Repeater",()->SimpleWorkflowTest.click(root,"wb.simple.traffic.repeater"));
    require(repeated.get()!=null&&Arrays.equals(repeated.get().toByteArray().getBytes(),ref.request().toByteArray().getBytes()),"traffic Repeater handoff preserves exact original bytes");
    int evidenceBefore=totalEvidence();act("send selected original to evidence",()->SimpleWorkflowTest.click(root,"wb.simple.traffic.evidence"));require(totalEvidence()>evidenceBefore,"traffic selection reaches the real evidence tool");
    require(calls.get()==requests,"traffic search, selection, Repeater preparation and evidence handoff send no HTTP");
  }
  static void mapper()throws Exception{
    var workspace=(kr.featuremapper.FeatureMapperWorkspace)value(extension,"featureMapper");Object model=value(workspace,"mapper");
    act("open observed feature classifications",()->{tool("mapper");invoke(workspace,"refresh",new Class<?>[0]);});
    require(MapperIntegrationTest.count(model)>0&&!((Map<?,?>)value(model,"features")).isEmpty(),"captured real HTTP is classified into actual Mapper features");
    JTable table=(JTable)value(workspace,"table");JTree tree=(JTree)value(workspace,"tree");
    act("select a classified feature and its original packet",()->{tree.expandRow(0);for(int i=1;i<tree.getRowCount();i++){tree.setSelectionRow(i);if(table.getRowCount()>0)break;}if(table.getRowCount()==0)tree.setSelectionRow(0);require(table.getRowCount()>0,"selected Mapper feature has observed packets");table.setRowSelectionInterval(0,0);});
    waitFor(()->!((JTextArea)value(workspace,"requestText")).getText().isEmpty()&&!((JTextArea)value(workspace,"responseText")).getText().isEmpty(),"Mapper original preview loads");
    long id=((Number)table.getValueAt(0,0)).longValue();byte[][] original=(byte[][])invoke(model,"raw",new Class<?>[]{long.class},id);
    require(original[0].length>0&&original[1].length>0,"Mapper selection resolves retained request and response bytes");
    boolean linked=false;for(long index=0;index<disk.size();index++){var ref=disk.ref(index);if(Arrays.equals(original[0],ref.requestPrefix(Math.toIntExact(ref.requestLength())))){useOriginal(ref);linked=true;break;}}
    require(linked,"the selected Mapper packet bytes match a captured immutable Workbench original");
    Path project=output.resolve("private-mapper.fmkr");int before=calls.get();Class<?> io=Class.forName("kr.featuremapper.ProjectIO");Method save=io.getDeclaredMethod("save",model.getClass(),Path.class),load=io.getDeclaredMethod("load",Path.class);save.setAccessible(true);load.setAccessible(true);
    try{save.invoke(null,model,project);Object restored=load.invoke(null,project);try{require(MapperIntegrationTest.count(restored)==MapperIntegrationTest.count(model),"Mapper project serialization restores every observed packet");require(Arrays.deepEquals(original,(byte[][])invoke(restored,"raw",new Class<?>[]{long.class},id)),"Mapper project round trip retains the selected exact original");}finally{((AutoCloseable)restored).close();}}finally{Files.deleteIfExists(project);}
    require(calls.get()==before&&!Files.exists(project),"Mapper classification, preview and private project round trip send no HTTP and remove the file");
    extra.put("mapperProject",map("serializationApiExercised",true,"osFilePickerExercised",false,"privateProjectRemoved",true,"mapperPacketId",id));
  }
  static void auth()throws Exception{
    int start=calls.get();var bobIdentity=fetch("/api/access/me",bob);var aliceOriginal=disk.ref(0);
    act("observe real authenticated packets and explicitly assign their account slots",()->{tool("auth");auth.observe(aliceOriginal.request(),aliceOriginal.response());auth.observe(bobIdentity.request(),bobIdentity.response());int slot=0;for(Login login:List.of(alice,bob)){var candidate=auth.credentials.values().stream().filter(c->login.cookie().equals(c.headers().get("cookie"))).findFirst().orElseThrow();auth.accounts.put(candidate.key(),new AuthAccount(login.label(),login.label(),slot==0?"1":"2","USER",""));auth.assign(slot,candidate);auth.profileNames[slot].setText(slot==0?"Alice":"Bob");slot++;}});
    for(String mode:List.of("fixed","vuln")){
      String path="/api/access/"+mode+"/user/requests/501";var ref=capture(fetch(path,alice));selectTraffic(ref);
      act("configure declared owner policy and account identities for "+mode,()->{tool("auth");auth.ownerPath.setText("$.OWNER_ID");auth.profileOwners[0].setText("1");auth.profileOwners[1].setText("2");auth.importantFields.setText("$.BODY");auth.accessPolicy.setSelectedIndex(2);auth.matrixRules.setText("GET "+path+" ALLOW DENY UNKNOWN UNKNOWN");});
      if(mode.equals("fixed")){int before=calls.get();act("reject malformed comparison conditions before sending",()->{auth.importantFields.setText("invalid");boolean rejected=false;try{auth.compareSelection();}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"invalid important-field setup is rejected before comparison");auth.importantFields.setText("$.BODY");});require(calls.get()==before,"comparison setup error recovery sends no HTTP");}
      act("explicitly compare the same GET under Alice and Bob for "+mode,auth::compareSelection);AuthRecordTest.idle(auth);edt(()->{});
      var run=auth.runs.get(auth.runs.size()-1);require(run.packets[0]!=null&&run.packets[1]!=null,"both chosen accounts retain their own comparison packets");
      require(run.packets[0].response.statusCode()==200,"owner account remains able to read its document");
      require(run.packets[1].response.statusCode()==(mode.equals("fixed")?404:200),"fixed and vulnerable endpoints expose the documented account difference");
      require(alice.cookie().equals(run.packets[0].request.headerValue("Cookie"))&&bob.cookie().equals(run.packets[1].request.headerValue("Cookie")),"comparison originals preserve distinct account credentials");
      int before=calls.get();act("select the grouped comparison result and inspect its evidence",()->{auth.history.setRowSelectionInterval(auth.history.getRowCount()-1,auth.history.getRowCount()-1);auth.show(run);});require(calls.get()==before,"selecting comparison evidence sends no HTTP");
    }
    require(calls.get()-start<=20,"two declared comparison tasks remain under the explicit 20-request audit bound");
  }
  static void js()throws Exception{
    var ref=capture(fetch("/assets/safe-endpoints.js",null));selectTraffic(ref);var panel=(JsAnalysisPanel)value(extension,"jsAnalysis");
    act("open selected real JavaScript response through its actual traffic menu",()->{JTable table=(JTable)value(extension,"logTable");JPopupMenu menu=(JPopupMenu)table.getClientProperty("wb.traffic.popup");boolean clicked=false;for(Component item:menu.getComponents())if(item instanceof JMenuItem command&&command.getText().equals("선택 응답에서 API 찾기")){command.doClick(0);clicked=true;break;}require(clicked,"the actual selected-response API-analysis menu is available");});JsAnalysisPanelTest.waitDone(panel);
    require(panel.urlTable.getRowCount()>0&&panel.input.getText().contains("/api/fixed/config"),"real LOOP JavaScript produces API candidates and retains source text");
    int row=-1;for(int i=0;i<panel.urlTable.getRowCount();i++)if(Objects.toString(panel.urlTable.getValueAt(i,0),"").contains("/api/fixed/config")){row=i;break;}
    require(row>=0,"expected read-only API literal is present among findings");int selected=row;int before=calls.get();
    act("select API candidate and inspect its source original",()->{panel.urlTable.setRowSelectionInterval(selected,selected);panel.packetWindow.opener=(o,t,c,m,x)->{dialog.set(c);return null;};panel.openPacket(panel.urlTable,selected);});
    require(dialog.get()!=null&&SimpleWorkflowTest.named(dialog.get(),"wb.js.packet.response.text") instanceof JTextArea text&&text.getText().contains("/api/fixed/config"),"API finding opens the actual source-JS packet rather than an invented API response");
    act("prepare the selected API candidate without sending",()->{panel.repeater.opener=(o,t,c,m,x)->{dialog.set(c);return null;};panel.prepareRepeater();});
    require(calls.get()==before,"API parsing, source location and draft preparation send no HTTP");
  }
  static void environment()throws Exception{
    var env=(EnvironmentPanel)value(extension,"environments");int before=calls.get();var passiveWorkspace=(PassiveScanWorkspace)value(extension,"passiveScan");var selected=new AtomicReference<PassiveScanPanel>();
    act("open server/path tool and prepare local URL collection",()->{tool("environment");selected.set(passiveWorkspace.open(BASE+"/",false));});var passive=selected.get();
    act("import the same 1000 URL candidates without requesting them",()->{var text=new StringBuilder();for(int i=0;i<999;i++)text.append(BASE).append("/api/js-lab/workflow?q=WB_SAFE_").append(i).append('\n');text.append(BASE).append("/api/js-lab/workflow?q=WB_SAFE_TARGET\n");passive.importUrls(text.toString());});
    require(passive.model.getRowCount()==1000,"URL collection retains every distinct query candidate");require(calls.get()==before,"preparing and importing URL candidates sends no HTTP");
    act("filter to one original candidate and explicitly verify its GET",()->{passive.search.setText("WB_SAFE_TARGET");require(passive.table.getRowCount()==1,"URL filtering exposes exactly the chosen query candidate");passive.table.setRowSelectionInterval(0,0);passive.single.doClick(0);});
    waitFor(()->passive.job==null&&!passive.packets.isEmpty(),"explicit candidate GET completes");
    require(calls.get()==before+1&&passive.packets.values().iterator().next().source().response().statusCode()==200,"one explicit selected-candidate verification performs exactly one actual LOOP GET");
    require(passive.request.text.getText().contains("WB_SAFE_TARGET")&&passive.response.text.getText().contains("WB_SAFE_TARGET"),"selected candidate preview retains its exact query and reflected real response");
    capture(passive.packets.values().iterator().next().source());
    int beforeHandoff=calls.get(),evidenceBefore=totalEvidence();act("add verified URL original to evidence",()->passive.captureEvidence.doClick(0));require(totalEvidence()>evidenceBefore&&calls.get()==beforeHandoff,"verified URL evidence handoff is local and retains its original");
    act("select a passively observed environment path",()->{env.origins.setSelectedItem(BASE);env.compareMissing.setSelected(false);env.follow.setSelected(false);env.refresh();int index=-1;for(int i=0;i<env.candidates.size();i++)if(env.candidates.get(i).url().equals(BASE+"/api/fixed/config")){index=i;break;}require(index>=0,"observed JS yields the real config path in environment candidates");int view=env.pathTable.convertRowIndexToView(index);env.pathTable.setRowSelectionInterval(view,view);env.single.doClick(0);});
    waitFor(()->env.job==null&&!env.packets.isEmpty(),"environment selected path completes");require(env.packets.containsKey(BASE+"/api/fixed/config"),"environment retains the selected actual path packet");
    int noSend=calls.get();act("open selected environment result evidence",()->{env.resultDetails.opener=(o,t,c,m,x)->{dialog.set(c);return null;};env.resultDetails.open();});require(dialog.get()!=null&&calls.get()==noSend,"environment result review is local");
    Path path=output.resolve("private-environment.json");try{EnvironmentProject snapshot=env.projectSnapshot();snapshot.write(path);var restored=EnvironmentProject.read(path);require(restored.latest.size()==snapshot.latest.size()&&!restored.latest.isEmpty(),"environment project metadata survives a local save/read round trip");}finally{Files.deleteIfExists(path);}
    extra.put("environmentVerification",map("missingControlEnabled",false,"followDiscoveredPaths",false,"selectedCandidateOnly",true,"projectSerializationApi",true,"osFilePickerExercised",false));
  }
  static void response()throws Exception{
    var real=capture(fetch("/api/diagnostics/vuln/headers",null));capture(fetch("/api/diagnostics/fixed/headers",null));capture(fetch("/v3/api-docs",null));
    var recon=(ResponseReconPanel)value(extension,"responseRecon");
    waitFor(()->recon.findingTable.getRowCount()>0&&recon.apiTable.getRowCount()>0,"real response observations and API declarations are published");
    int before=calls.get(),evidenceBefore=totalEvidence();
    act("select actual response security observation and preserve its packet",()->{tool("response.recon");int selected=-1;for(int i=0;i<recon.displayedFindingKeys.size();i++){var rr=recon.displayedFindingPackets.get(recon.displayedFindingKeys.get(i));if(rr!=null&&rr.request().url().equals(real.url())){selected=recon.findingTable.convertRowIndexToView(i);break;}}require(selected>=0,"real vulnerable-header response has original-linked security observations");recon.findingTable.setRowSelectionInterval(selected,selected);require(recon.findingCapture.isEnabled(),"observed response enables its exact-packet evidence action");recon.findingCapture.doClick(0);});
    require(totalEvidence()>evidenceBefore,"response security observation reaches evidence with its captured packet");
    act("inspect a declared API and prepare its request draft",()->{recon.apiTable.setRowSelectionInterval(0,0);recon.draftWindow.opener=(o,t,c,m,x)->{dialog.set(c);return null;};recon.apiDraft.doClick(0);});require(dialog.get()!=null&&calls.get()==before,"API documentation and response evidence review do not execute declared APIs");
  }
  static void payload()throws Exception{
    int before=calls.get();
    act("prepare one harmless query diagnostic with total request budget two",()->{tool("payload");selectEnum(runner,"profile","CUSTOM");selectEnum(runner,"encoding","URL");((JComboBox<?>)value(runner,"mode")).setSelectedIndex(1);text(runner,"host","127.0.0.1");spin(runner,"port",18888);spin(runner,"budget",2);spin(runner,"interval",0);spin(runner,"responseTimeout",5);checkBox(runner,"scope",true);checkBox(runner,"sessionControl",false);checkBox(runner,"callbackMode",false);text(runner,"base","audit-baseline");text(runner,"payloads","audit-query");text(runner,"request","GET /api/js-lab/workflow?q=§PAYLOAD§ HTTP/1.1\r\nHost: 127.0.0.1:18888\r\nCookie: "+alice.cookie()+"\r\n\r\n");});
    require(calls.get()==before,"configuring query diagnostics sends no HTTP");
    act("explicitly run baseline and one safe query value",()->click(runner,"start"));waitFor(()->!((Boolean)value(runner,"running"))&&((JTable)value(runner,"table")).getRowCount()>=2,"query diagnostic completes");
    require(calls.get()==before+2,"query diagnostic obeys its two-request budget including baseline");
    JTable table=(JTable)value(runner,"table");int evidenceBefore=totalEvidence(),noSend=calls.get();act("select finished query result and add exact diagnostic evidence",()->{table.setRowSelectionInterval(table.getRowCount()-1,table.getRowCount()-1);click(runner,"evidenceButton");});require(totalEvidence()>evidenceBefore&&calls.get()==noSend,"finished query results and exact packets are handed to evidence locally");
    int start=calls.get();act("start a bounded safe repeat for explicit stop/recovery",()->{spin(runner,"interval",5000);spin(runner,"budget",3);text(runner,"payloads","audit-stop-one\naudit-stop-two");click(runner,"start");});waitFor(()->calls.get()>start,"safe repeat begins");act("stop follow-up query requests",()->click(runner,"stopButton"));waitFor(()->!((Boolean)value(runner,"running")),"payload stop completes");Thread.sleep(150);int stopped=calls.get();Thread.sleep(150);require(calls.get()==stopped&&stopped-start<=2,"stopping prevents remaining harmless query requests within the three-request bound");
    extra.put("payloadMeaning","Harmless query-string comparison only; no browser execution or confirmed vulnerability claim.");
  }
  static void bounty()throws Exception{
    int before=calls.get();act("prepare two read-only reproduction steps",()->{tool("bounty");SimpleWorkflowTest.click(root,"wb.simple.bounty.replay");bounty.flowOrigin.setText(BASE);bounty.workflow.setText(requestFor("/api/community/fixed/people",alice)+"\n===STEP===\n"+requestFor("/api/community/fixed/posts",alice));bounty.flowBudget.setValue(2);bounty.flowTimeout.setValue(5);bounty.flowWrites.setSelected(false);bounty.extract.setText("");bounty.flowChecks.setText("1.HTTP=200\n2.HTTP=200");});require(calls.get()==before,"preparing sequential reproduction sends no HTTP");
    act("explicitly reproduce the two GET steps",()->bounty.flowRun.doClick(0));waitFor(()->!bounty.running&&bounty.flowResults.size()==2,"reproduction completes");require(calls.get()==before+2,"sequential reproduction performs exactly its two budgeted requests");require(bounty.flowResults.get(0).step()==1&&bounty.flowResults.get(1).step()==2,"reproduction results retain their original step order");
    int evidenceBefore=totalEvidence(),noSend=calls.get();act("retain reproduction results and inspect redacted evidence",()->{bounty.flowCapture.doClick(0);String preview=bounty.evidencePreview();require(!preview.contains(alice.cookie())&&!preview.contains(bob.cookie()),"evidence preview hides both live authentication sessions");require(!preview.isBlank(),"redacted evidence still has useful reproduction content");});require(totalEvidence()>=evidenceBefore+2&&calls.get()==noSend,"reproduction evidence transfer and masking send no HTTP");
    Path zip=output.resolve("private-evidence.zip");try{bounty.writeEvidence(zip,"Whole workflow audit","Read-only fixture evidence","Two explicit GET steps",List.copyOf(bounty.evidence),Set.copyOf(bounty.evidenceHeaders),Set.copyOf(bounty.evidenceSecrets),true,bounty.activity.tracker.save(),null,List.of());
      try(var archive=new ZipFile(zip.toFile())){require(archive.size()>2,"production evidence export creates a readable populated ZIP");boolean content=false;for(var entries=archive.entries();entries.hasMoreElements();){var entry=entries.nextElement();if(entry.isDirectory())continue;String text=new String(archive.getInputStream(entry).readAllBytes(),StandardCharsets.UTF_8);require(!text.contains(alice.cookie())&&!text.contains(bob.cookie()),"ZIP entry masks live sessions: "+entry.getName());content|=text.contains("/api/community/fixed/");}require(content,"exported ZIP retains the actual reproduction paths");}extra.put("evidenceZip",map("bytes",Files.size(zip),"sha256",sha(Files.readAllBytes(zip)),"osFilePickerExercised",false,"productionSerializationApi",true));
    }finally{Files.deleteIfExists(zip);}require(calls.get()==noSend&&!Files.exists(zip),"local evidence export sends no HTTP and private ZIP is removed");
  }
  static void inspectionAndDashboard()throws Exception{
    module="inspection-dashboard";int before=calls.get();act("open actual dashboard and integrated inspection",()->{SimpleWorkflowTest.click(root,"wb.simple.dashboard.open");var dashboard=(ScopeDashboardPanel)value(extension,"scopeDashboard");dashboard.inspect.doClick(0);});
    var panel=(ScopeInspectionPanel)value(extension,"scopeInspectionPanel");var dashboard=(ScopeDashboardPanel)value(extension,"scopeDashboard");require(calls.get()==before,"opening integrated inspection and dashboard sends no HTTP");
    act("configure bounded real inspection of API documentation",()->{panel.refreshTargets();panel.url.setText(BASE+"/v3/api-docs");panel.refreshProfiles();panel.compareAccounts.setSelected(true);panel.budget.setValue(20);panel.depth.setValue(0);panel.delay.setValue(0);panel.timeout.setValue(5);panel.katanaMode.setSelected(false);panel.browserMode.setSelected(false);});
    int start=calls.get();act("explicitly run integrated inspection",()->panel.start.doClick(0));waitFor(()->panel.runner!=null&&Set.of(ScopeInspection.State.COMPLETED,ScopeInspection.State.STOPPED,ScopeInspection.State.FAILED).contains(panel.runner.snapshot().state()),"bounded integrated inspection terminal");
    var completed=panel.runner.snapshot();String run=completed.id();waitFor(()->panel.current.id().equals(run)&&panel.current.state()==completed.state()&&panel.current.sent()==completed.sent()&&!dashboard.aggregating&&dashboard.current.id().equals(run)&&dashboard.current.sent()==completed.sent(),"same terminal inspection reaches dashboard");require(panel.current.sent()==calls.get()-start&&panel.current.sent()<=20,"inspection shares and reports its actual strict request budget");
    int noSend=calls.get();act("filter real dashboard paths to the selected login account",()->{SimpleWorkflowTest.click(root,"wb.simple.dashboard.open");dashboard.cards.get(ScopeInspectionDashboard.Group.PATHS).doClick(0);for(int i=0;i<dashboard.account.getItemCount();i++)if(dashboard.account.getItemAt(i).value().contains("Alice")){dashboard.account.setSelectedIndex(i);break;}});waitFor(()->!dashboard.aggregating&&dashboard.table.getRowCount()>0,"dashboard account original ready");
    var row=dashboard.selectedRow();require(row!=null&&!row.originalId().isEmpty(),"dashboard selection keeps an immutable current-run original ID");
    act("inspect original and prepare Repeater from dashboard",()->{dashboard.original.doClick(0);dashboard.repeater.doClick(0);dashboard.evidence.doClick(0);});
    require(repeated.get()!=null&&panel.current.pages().stream().anyMatch(p->p.original()!=null&&p.original().id().equals(row.originalId())&&p.original().request()==repeated.get()),"dashboard Repeater prepares the exact selected original object");
    act("recover from an empty importance filter and return",()->{dashboard.severityButtons.get("HIGH").doClick(0);dashboard.reset.doClick(0);dashboard.cards.get(ScopeInspectionDashboard.Group.API).doClick(0);dashboard.cards.get(ScopeInspectionDashboard.Group.PATHS).doClick(0);});require(calls.get()==noSend,"dashboard cards, filters, empty recovery and original handoffs send no HTTP");
    extra.put("inspection",map("run",run,"sent",panel.current.sent(),"budget",20,"state",panel.current.state().name(),"originalId",row.originalId(),"wholeSiteVerified",false));
  }
  static void utilities()throws Exception{
    module="management-help";int before=calls.get();act("open help, appearance and diagnostics through the shared management menu",()->{var nav=(SimpleNavigation)SimpleWorkflowTest.named(root,"wb.simple.navigation");var menu=nav.optionsMenu();var names=List.of("wb.feature.guide.text","wb.settings.layout.common","diagnostics.table");for(int i=0;i<3;i++){dialog.set(null);((JMenuItem)menu.getComponent(i)).doClick(0);require(dialog.get()!=null&&SimpleWorkflowTest.named(dialog.get(),names.get(i))!=null,"shared management option opens actual content: "+i);}});require(calls.get()==before,"management/help actions preserve the no-target-request boundary");
  }
  public static void main(String[] args)throws Exception{
    if(args.length!=2||!args[1].matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Usage: WholeWorkbenchWorkflowAudit output-directory jarSHA256");
    output=Path.of(args[0]);Files.createDirectories(output);long began=System.nanoTime();String started=Instant.now().toString();BurpExtender entry=null;boolean passed=false;
    var metadata=map("schemaVersion",1,"productionVersion",BuildInfo.VERSION,"jarSHA256",args[1],"baseURL",BASE,"startedAt",started,"nativeBurp",false,"browserDOM",false,"osClicks",false,
        "fixedConditions",map("application","original LOOP 3.10.0","accounts",List.of("alice","bob"),"methodsAfterLogin",List.of("GET","HEAD"),"workspaceWidth",1280,"workspaceHeight",800,"theme","default Swing host adapter","targetNetworkTimeoutSeconds",7,"osFilePickers",false,"syntheticImportedUrlCandidates",1000,"importedUrlMeaning","1000 synthetic query candidates for the same real LOOP read-only endpoint; only the explicitly selected candidate is fetched","responseAdapterFraming","HttpClient dechunked bytes plus Content-Length; real status/body and non-framing headers retained"),
        "scope","All eight retained production tools, their original/evidence handoffs, integrated inspection/dashboard and shared utilities. Actual LOOP HTTP via Montoya adapter; GUI/DOM/Katana/external services are not exercised.");
    try{
      alice=login("alice");bob=login("bob");setupTransport();ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));entry=new BurpExtender();entry.registerExtenderCallbacks(callbacks());BurpExtender owner=entry;act("initialize actual Workbench extension",()->owner.initialize(api));
      extension=(WorkbenchExtension)value(entry,"workbench");root=(JPanel)value(extension,"root");disk=(DiskTrafficStore)value(extension,"trafficStore");bounty=(BountySupportPanel)value(extension,"bountySupport");auth=(AuthComparePanel)value(extension,"authCompare");runner=value(entry,"payloadRunner");
      extension.dialogOpener=(o,t,c,m,x)->{dialog.set(c);return null;};ScopeInspectionFixture.installFactory();
      act("register the prepared LOOP SCOPE",()->{root.setSize(1280,800);layout(root);((WorkbenchScope)value(extension,"localScope")).add(BASE+"/",true);});
      require(calls.get()==0,"extension initialization, SCOPE and account preparation do not contact targets");
      wholeModule("traffic",WholeWorkbenchWorkflowAudit::traffic);wholeModule("mapper",WholeWorkbenchWorkflowAudit::mapper);wholeModule("auth",WholeWorkbenchWorkflowAudit::auth);wholeModule("js",WholeWorkbenchWorkflowAudit::js);wholeModule("environment",WholeWorkbenchWorkflowAudit::environment);wholeModule("response.recon",WholeWorkbenchWorkflowAudit::response);wholeModule("payload",WholeWorkbenchWorkflowAudit::payload);wholeModule("bounty",WholeWorkbenchWorkflowAudit::bounty);inspectionAndDashboard();utilities();
      module="lifecycle";var passivePanels=List.copyOf(((PassiveScanWorkspace)value(extension,"passiveScan")).panels.values());int before=calls.get();long unloadBegan=System.nanoTime();SimpleWorkflowTest.unload();actions.add(map("module",module,"name","explicitly unload all production tools outside the EDT","httpDelta",calls.get()-before,"elapsedMillis",(System.nanoTime()-unloadBegan)/1e6,"edtAllocatedBytes",-1));entry=null;Thread.sleep(300);edt(()->{});
      require(calls.get()==before,"unload leaves no further target requests");require(((AtomicBoolean)value(extension,"unloaded")).get(),"production Workbench lifecycle is unloaded");require((Boolean)value(runner,"closed")&&((ExecutorService)value(runner,"worker")).isShutdown(),"payload worker closes");require(bounty.closed.get()&&bounty.worker.isShutdown()&&auth.worker.isShutdown(),"comparison, replay and evidence workers close");
      var mapper=(kr.featuremapper.FeatureMapperWorkspace)value(extension,"featureMapper");require(Boolean.TRUE.equals(mapper.component().getClientProperty("fm.closed"))&&((ExecutorService)value(mapper,"io")).isShutdown()&&((ExecutorService)value(mapper,"previews")).isShutdown(),"Mapper file and preview workers close");
      var js=(JsAnalysisPanel)value(extension,"jsAnalysis");require(((AtomicBoolean)value(js,"closed")).get()&&((ExecutorService)value(js,"worker")).isShutdown(),"API analysis workers close");
      var recon=(ResponseReconPanel)value(extension,"responseRecon");require(recon.closed.get()&&recon.worker.isShutdown(),"response analysis workers close");
      var env=(EnvironmentPanel)value(extension,"environments");require(env.closed.get()&&(env.job==null||env.job.isDone())&&passivePanels.stream().allMatch(p->p.closed.get()&&(p.job==null||p.job.isDone())),"environment and URL verification jobs close");
      require(extension.trafficBrowser.closed&&((ExecutorService)value(extension.trafficBrowser,"worker")).isShutdown(),"traffic search workers close");
      Object observer=value(extension,"captureObservations");waitFor(()->!((Thread)value(observer,"thread")).isAlive(),"capture observation thread terminates");require(((AtomicBoolean)value(observer,"closed")).get(),"shared capture observation worker terminates");
      var inspection=(ScopeInspectionPanel)value(extension,"scopeInspectionPanel");var dashboard=(ScopeDashboardPanel)value(extension,"scopeDashboard");require((Boolean)value(inspection,"closed")&&((ExecutorService)value(inspection,"historyWorker")).isShutdown()&&((ExecutorService)value(inspection,"katanaChecks")).isShutdown()&&((ExecutorService)value(inspection,"reportWorker")).isShutdown(),"integrated inspection workers close");
      require((Boolean)value(dashboard,"closed")&&((ExecutorService)value(dashboard,"projectionWorker")).isShutdown(),"dashboard aggregation worker closes");
      require(modules.size()==8&&modules.values().stream().allMatch(v->Boolean.TRUE.equals(((Map<?,?>)v).get("coverage"))),"all eight complete workflows were exercised");passed=true;
    }catch(Throwable error){metadata.put("failure",map("module",module,"class",error.getClass().getSimpleName(),"label",error instanceof AssertionError?Objects.toString(error.getMessage(),""):"Unexpected product/adapter exception"));System.out.println("AUDIT_FAILURE module="+module+" class="+error.getClass().getSimpleName());for(StackTraceElement frame:error.getStackTrace())System.out.println(" at "+frame);if(error.getCause()!=null){System.out.println("cause class="+error.getCause().getClass().getSimpleName());for(StackTraceElement frame:error.getCause().getStackTrace())System.out.println(" at "+frame);}}
    finally{
      if(entry!=null)try{SimpleWorkflowTest.unload();}catch(Exception ignored){}
      for(String name:List.of("private-mapper.fmkr","private-environment.json","private-evidence.zip"))Files.deleteIfExists(output.resolve(name));
      metadata.putAll(map("result",passed?"PASS":"FAIL","finishedAt",Instant.now().toString(),"elapsedMillis",(System.nanoTime()-began)/1e6,"passed",assertions.stream().filter(a->Boolean.TRUE.equals(a.get("passed"))).count(),"failed",assertions.stream().filter(a->!Boolean.TRUE.equals(a.get("passed"))).count(),"failedModules",failedModules,"loginSetupHttpRequests",loginCalls.get(),"engineHttpRequests",calls.get(),"repeaterPreparations",repeaterPreparations.get(),"modules",modules,"assertions",assertions,"actions",actions,"transport",transport,"details",extra,"originalIds",List.copyOf(realOriginalIds),"credentialsInPublicEvidence",false,"privateArtifactExport",false,"osFilePickersExercised",false,"timingsAreSingleObservations",true));
      Files.writeString(output.resolve("summary.json"),MetadataExport.json(metadata)+"\n",StandardCharsets.UTF_8);System.out.println(MetadataExport.json(map("result",metadata.get("result"),"version",BuildInfo.VERSION,"coveredModules",modules.size(),"passed",metadata.get("passed"),"failed",metadata.get("failed"),"httpRequests",calls.get(),"elapsedMillis",metadata.get("elapsedMillis"))));
    }
    System.exit(passed?0:1);
  }
}
