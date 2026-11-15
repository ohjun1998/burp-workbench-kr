package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import java.awt.Component;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Hosts the pinned upstream artifact in its own loader; no second Burp extension is registered. */
final class CustomAgentBridge implements AutoCloseable {
  static final String VERSION="1.1.0";
  static final String SHA="bb6c098f3ca525f94032759435033627945fef5d091a1597b80e2c442515eae5";
  static final String RESOURCE="custom-agent/Custom-AI-Agent-full-1.1.0.jar";
  private final MontoyaApi host;
  final CustomAgentGate gate;
  private final Consumer<Component> ui;
  private final List<Registration> registrations=new CopyOnWriteArrayList<>();
  private final List<ExtensionUnloadingHandler> unloaders=new CopyOnWriteArrayList<>();
  final List<ContextMenuItemsProvider> menus=new CopyOnWriteArrayList<>();
  private final Map<String,Object> preferences=new ConcurrentHashMap<>();
  private volatile boolean closed;
  private URLClassLoader loader;
  private Path extracted;

  CustomAgentBridge(MontoyaApi host,CustomAgentGate gate,String model,Consumer<Component> ui){
    this.host=host;this.gate=gate;this.ui=ui;
    preferences.put("backend.preferred","openai-compatible");
    preferences.put("openai.compat.url",gate.modelBase.toString());
    preferences.put("openai.compat.model",model);
    preferences.put("ollama.autostart",false);preferences.put("lmstudio.autostart",false);
    preferences.put("mcp.enabled",false);preferences.put("mcp.scope.only",true);
    preferences.put("privacy.mode","STRICT");preferences.put("audit.enabled",false);
    preferences.put("passive.ai.persistent.cache.enabled",false);
    preferences.put("active.ai.max.concurrent",1);preferences.put("active.ai.risk.level","SAFE");
  }
  synchronized void load() throws Exception {
    if(closed||loader!=null)throw new IllegalStateException("Agent가 이미 시작되었거나 종료되었습니다.");
    if(Runtime.version().feature()<21)throw new IllegalStateException("Custom AI Agent에는 Java 21 이상이 필요합니다.");
    extracted=Files.createTempFile("workbench-custom-agent-",".jar");
    try(InputStream in=getClass().getClassLoader().getResourceAsStream(RESOURCE)){
      if(in==null)throw new FileNotFoundException("내장 Agent JAR가 없습니다. 0.56.0 통합 배포 파일을 사용하세요.");
      Files.copy(in,extracted,StandardCopyOption.REPLACE_EXISTING);
    }
    verify(extracted);
    loader=new URLClassLoader(new URL[]{extracted.toUri().toURL()},getClass().getClassLoader()) {
      @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
        synchronized(getClassLoadingLock(name)) {
          if(name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("jdk.")||name.startsWith("sun.")||name.startsWith("burp.api.")||name.startsWith("kr.workbench."))return super.loadClass(name,resolve);
          Class<?> found=findLoadedClass(name);
          if(found==null)try{found=findClass(name);}catch(ClassNotFoundException e){found=super.loadClass(name,false);}
          if(resolve)resolveClass(found);return found;
        }
      }
      // External CLI/drop-in/MCP-client plug-ins are outside Workbench's request boundary.
      // Keep the full-build local HTTP access policy; use the core HTTP backends and core tools.
      @Override public Enumeration<URL> getResources(String name)throws IOException {
        if(name.startsWith("META-INF/services/com.six2dez.")&&!name.endsWith(".AiAccessPolicy")&&!name.endsWith(".FlavorProfile"))return Collections.emptyEnumeration();
        return super.getResources(name);
      }
    };
    Class<?> entry=loader.loadClass("com.six2dez.burp.aiagent.BurpAiAgentExtension");
    Object extension=entry.getConstructor().newInstance();
    try{entry.getMethod("initialize",MontoyaApi.class).invoke(extension,api());}
    catch(InvocationTargetException e){close();throw new IllegalStateException("Custom AI Agent 초기화 실패",e.getCause());}
  }
  static void verify(Path path)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}
    if(!HexFormat.of().formatHex(digest.digest()).equals(SHA))throw new SecurityException("Custom AI Agent SHA-256 불일치");
  }
  MontoyaApi api(){return proxy(MontoyaApi.class,(p,m,a)->{
    if(closed)throw new IllegalStateException("Agent 종료");
    String service=m.getName();
    if(Set.of("intruder","websockets","bambda","collaborator").contains(service))throw denied(service);
    Object real=invoke(host,m,a);
    return switch(service){
      case "http","extension","userInterface","persistence","scope","scanner","ai","burpSuite" -> service(m.getReturnType(),real,service);
      default -> real;
    };
  });}
  private Object service(Class<?> type,Object real,String kind){return proxy(type,(p,m,a)->{
    String name=m.getName();
    if(name.equals("toString"))return "Workbench Custom AI Agent "+kind;
    if(closed&&!name.startsWith("deregister"))throw new IllegalStateException("Agent 종료");
    switch(kind){
      case "http":
        if(name.equals("sendRequest"))return gate.send((HttpRequest)a[0]);
        if(name.equals("sendRequests")||name.equals("createRequestEngine")||name.startsWith("register"))throw denied(name);
        break;
      case "extension":
        if(name.equals("setName"))return null;
        if(name.equals("unload"))throw denied("Workbench 전체 종료");
        if(name.equals("registerUnloadingHandler")){var h=(ExtensionUnloadingHandler)a[0];unloaders.add(h);return registration(()->unloaders.remove(h));}
        break;
      case "userInterface":
        if(name.equals("registerSuiteTab")){ui.accept((Component)a[1]);return registration(()->{});}
        if(name.equals("registerContextMenuItemsProvider")){menus.add((ContextMenuItemsProvider)a[0]);}
        break;
      case "persistence":
        if(name.equals("preferences"))return memoryPreferences();
        throw denied("Agent 영구 데이터 저장");
      case "scope":
        if(name.equals("isInScope"))return WorkbenchScope.contains(host,HttpRequest.httpRequestFromUrl((String)a[0]));
        if(name.equals("includeInScope")||name.equals("excludeFromScope"))throw denied("SCOPE 변경은 Workbench에서 수행하세요");
        break;
      case "scanner":
        if(!name.startsWith("register")&&!name.equals("bChecks"))throw denied("Burp Scanner 작업 시작");
        if(name.equals("bChecks"))throw denied(name);
        break;
      case "ai":
        if(name.equals("isEnabled"))return false;
        throw denied("내장 Burp AI 대신 로컬 HTTP 백엔드를 사용하세요");
      case "burpSuite":
        if(!Set.of("version","edition").contains(name))throw denied("Burp 전역 설정 변경");
        break;
    }
    Object value=invoke(real,m,a);if(value instanceof Registration r)registrations.add(r);return value;
  });}
  Preferences memoryPreferences(){return proxy(Preferences.class,(p,m,a)->{
    String name=m.getName();
    if(name.endsWith("Keys")){
      String prefix=name.substring(0,name.length()-4);Class<?> valueType=switch(prefix){case "string"->String.class;case "boolean"->Boolean.class;case "integer"->Integer.class;case "long"->Long.class;case "byte"->Byte.class;case "short"->Short.class;default->Object.class;};
      Set<String> keys=new HashSet<>();preferences.forEach((k,v)->{if(valueType.isInstance(v))keys.add(k);});return keys;
    }
    String key=(String)a[0];
    if(name.startsWith("get"))return preferences.get(key);
    if(name.startsWith("delete")){preferences.remove(key);return null;}
    if(name.startsWith("set")){preferences.put(key,a[1]);return null;}
    throw denied(name);
  });}
  static Registration registration(Runnable undo){return new Registration(){boolean registered=true;public boolean isRegistered(){return registered;}public void deregister(){if(registered){registered=false;undo.run();}}};}
  static IllegalStateException denied(String name){return new IllegalStateException("Workbench 통합판에서 지원하지 않는 동작: "+name);}
  static Object invoke(Object real,Method m,Object[] a)throws Throwable{try{return m.invoke(real,a);}catch(InvocationTargetException e){throw e.getCause();}}
  @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler h){return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->{if(m.getDeclaringClass()==Object.class)return switch(m.getName()){case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->"Workbench Agent "+type.getSimpleName();};return h.invoke(p,m,a==null?new Object[0]:a);});}
  public synchronized void close(){
    if(closed)return;
    gate.close();
    // Allow the upstream shutdown handler to finish its logging and teardown before closing API access.
    for(var h:unloaders)try{h.extensionUnloaded();}catch(Throwable ignored){}
    closed=true;
    for(var r:registrations)try{r.deregister();}catch(RuntimeException ignored){}
    registrations.clear();menus.clear();unloaders.clear();preferences.clear();
    if(loader!=null)try{loader.close();}catch(IOException ignored){}
    if(extracted!=null)try{Files.deleteIfExists(extracted);}catch(IOException e){extracted.toFile().deleteOnExit();}
  }
}
