package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Exercises the actual single entry point with a Multi-API host adapter. */
public final class PayloadIntegrationTest {
 static int passed;
 static void check(boolean b,String name){if(!b)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
 static final List<ExtensionUnloadingHandler> modernUnloads=new ArrayList<>();
 static final List<IExtensionStateListener> legacyUnloads=new ArrayList<>();
 static final List<IContextMenuFactory> menus=new ArrayList<>();
 static final AtomicInteger suiteTabs=new AtomicInteger(),legacyTabs=new AtomicInteger(),sent=new AtomicInteger();
 static final List<String> names=new ArrayList<>();
 static Object host(Class<?> type){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->{
  if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);
  a=a==null?new Object[0]:a;
  switch(m.getName()){
   case "setName", "setExtensionName":names.add((String)a[0]);return null;
   case "registerUnloadingHandler":modernUnloads.add((ExtensionUnloadingHandler)a[0]);return null;
   case "registerExtensionStateListener":legacyUnloads.add((IExtensionStateListener)a[0]);return null;
   case "registerContextMenuFactory":menus.add((IContextMenuFactory)a[0]);return null;
   case "registerSuiteTab":suiteTabs.incrementAndGet();return null;
   case "addSuiteTab":legacyTabs.incrementAndGet();return null;
   case "makeHttpRequest", "sendRequest":sent.incrementAndGet();return null;
   case "uiComponent":return new JPanel();
   case "createHttpRequestEditor":return host(HttpRequestEditor.class);
   case "createHttpResponseEditor":return host(HttpResponseEditor.class);
  }
  if(m.getReturnType()==Optional.class)return Optional.empty();
  if(m.getReturnType()==List.class)return List.of();
  if(m.getReturnType().isInterface())return host(m.getReturnType());
  return WorkbenchTest.def(m);
 });}
 public static void main(String[] args)throws Exception{
  BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)host(IBurpExtenderCallbacks.class));
  // Initialization may be called on the EDT by a host; no invokeAndWait deadlock.
  SwingUtilities.invokeAndWait(()->entry.initialize((MontoyaApi)host(MontoyaApi.class)));
  SwingUtilities.invokeAndWait(()->{});
  WorkbenchExtension workbench=(WorkbenchExtension)WorkbenchTest.get(entry,"workbench");
  Object runner=WorkbenchTest.get(entry,"payloadRunner");
  JTabbedPane tabs=(JTabbedPane)WorkbenchTest.get(workbench,"tabs");
  check(suiteTabs.get()==1&&legacyTabs.get()==0,"single Workbench suite tab; no standalone Payload Runner tab");
  check(tabs.getTabCount()==11,"ten tools including bounty support plus independent Settings tab");
  check(names.stream().allMatch("Burp Workbench KR · 웹 보안 점검 (0.25.5)"::equals),"embedded runner preserves Workbench extension name");
  check(java.util.stream.IntStream.range(0,tabs.getTabCount()).noneMatch(i->Set.of("도구 가이드","원본 엔진").contains(tabs.getTitleAt(i))),"removed pages absent");
  check(entry.getClass().getResource("/engines/catalog.properties")==null,"engine catalog excluded from installation");
  check(sent.get()==0,"loading integrated extension sends zero requests");
  check(menus.size()==1&&legacyUnloads.size()==1,"one runner menu factory and unload listener without engine menu factory");
  check(WorkbenchTest.get(runner,"panel")==tabs.getComponentAt(tabs.indexOfTab("입력값 보안 검사")),"runner component is embedded in Workbench");
  SwingUtilities.invokeAndWait(workbench::selectPayloadRunnerPage);
  check(tabs.getSelectedIndex()==tabs.indexOfTab("입력값 보안 검사"),"import navigation selects integrated runner page");
  check(WorkbenchTest.get(workbench,"payloadImport")!=null,"Workbench selected-request action connected to runner");
  check(((JComboBox<?>)WorkbenchTest.get(runner,"mode")).getSelectedIndex()==0,"adaptive strategy remains default");
  check(((JComboBox<?>)WorkbenchTest.get(runner,"profile")).getItemCount()==16,"all sixteen payload profiles preserved");
  for(var h:modernUnloads)h.extensionUnloaded();
  for(var h:legacyUnloads)h.extensionUnloaded();
  SwingUtilities.invokeAndWait(()->{});
  check((Boolean)WorkbenchTest.get(runner,"closed")&&((ExecutorService)WorkbenchTest.get(runner,"worker")).isShutdown(),"both unload APIs safely close runner worker");
  check(((AtomicBoolean)WorkbenchTest.get(workbench,"unloaded")).get(),"existing Workbench unload still runs");
  System.out.println("PayloadIntegrationTest: "+passed+" checks passed");
 }
}
