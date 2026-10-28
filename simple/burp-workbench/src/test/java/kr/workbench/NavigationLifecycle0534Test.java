package kr.workbench;

import burp.*;
import burp.api.montoya.internal.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Actual host unload between a queued navigation event and its original-store lookup. */
public final class NavigationLifecycle0534Test extends WorkbenchTest {
  static Object capture(WorkbenchExtension ext)throws Exception {
    Method method=WorkbenchExtension.class.getDeclaredMethod("captureNavigationPosition",String.class);method.setAccessible(true);return method.invoke(ext,"traffic");
  }
  public static void main(String[] args)throws Exception {
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    var host=new StabilizationIntegration0534Test.Host();var entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(host.api));edt(()->{});
    var ext=(WorkbenchExtension)get(entry,"workbench");var table=(JTable)get(ext,"logTable");
    var disk=(DiskTrafficStore)get(ext,"trafficStore");var navigation=(FolderNavigation)get(ext,"navigation");var root=(JComponent)get(ext,"root");
    StabilizationIntegration0534Test.append(ext,request("https://lifecycle.test/original","GET",true,List.of()),response(200,"original"));
    AtomicReference<Object> saved=new AtomicReference<>();AtomicReference<Throwable> asynchronousFailure=new AtomicReference<>();
    Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->asynchronousFailure.compareAndSet(null,error));
    try {
      edt(()->{try {
        table.setRowSelectionInterval(0,0);saved.set(capture(ext));check(saved.get()!=null,"live selected original has a restorable navigation bookmark");
        root.putClientProperty("wb.skin.closed",true);check(capture(ext)==null,"disposed host skin rejects navigation capture before touching the live store");root.putClientProperty("wb.skin.closed",false);
        navigation.navigate("auth");navigation.navigate("traffic"); // queues delayed destination recording
        for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();
        for(var unload:PayloadIntegrationTest.legacyUnloads)unload.extensionUnloaded();
        check(table.getSelectedRow()>=0,"queued navigation still sees selected rows before queued Swing cleanup");
        boolean closed=false;try{disk.ref(0);}catch(DiskTrafficStore.StorageException expected){closed=true;}
        check(closed,"host unload has already closed the authoritative store before queued cleanup");
        check(capture(ext)==null,"navigation capture safely declines the selected but closed-store original");
        Method restore=WorkbenchExtension.class.getDeclaredMethod("restoreNavigationPosition",Object.class);restore.setAccessible(true);restore.invoke(ext,saved.get());
      }catch(Exception error){throw new AssertionError(error);}});
      edt(()->{});edt(()->{});
      check(asynchronousFailure.get()==null,"queued destination callbacks after real unload do not raise EDT storage errors");
      check(((TrafficBrowser)get(ext,"trafficBrowser")).closed,"late restore cannot reopen navigation or its disposed traffic worker");
    } finally {Thread.setDefaultUncaughtExceptionHandler(previous);}
    System.out.println("NavigationLifecycle0534Test: "+checks+" checks passed");
  }
}
