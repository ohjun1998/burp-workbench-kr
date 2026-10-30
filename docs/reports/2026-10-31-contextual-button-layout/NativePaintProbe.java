package kr.workbench;
import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.lang.management.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
public final class NativePaintProbe extends WorkbenchTest {
  static final class Counts extends RepaintManager {
    AtomicLong dirty=new AtomicLong(), paints=new AtomicLong();
    Map<String,AtomicLong> origins=new java.util.concurrent.ConcurrentHashMap<>();
    Map<String,AtomicLong> events=new java.util.concurrent.ConcurrentHashMap<>();
    Map<String,StackTraceElement[]> traces=new java.util.concurrent.ConcurrentHashMap<>();
    void watch(Component c){if(c instanceof JComponent j){j.addPropertyChangeListener(e->{String key=e.getPropertyName();if(List.of("UI","cellRenderer","rowHeight").contains(key)){String name=c.getClass().getName()+":"+c.getName()+":"+key;events.computeIfAbsent(name,k->new AtomicLong()).incrementAndGet();traces.putIfAbsent(name,Thread.currentThread().getStackTrace());}});}if(c instanceof Container x)for(Component ch:x.getComponents())watch(ch);}
    public void addDirtyRegion(JComponent c,int x,int y,int w,int h){dirty.incrementAndGet();origins.computeIfAbsent(c.getClass().getName()+":"+c.getName(),k->new AtomicLong()).incrementAndGet();super.addDirtyRegion(c,x,y,w,h);}
    public void paintDirtyRegions(){paints.incrementAndGet();super.paintDirtyRegions();}
    void reset(){dirty.set(0);paints.set(0);origins.clear();events.clear();traces.clear();}
  }
  public static void main(String[] args)throws Exception {
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    Counts counts=new Counts(); RepaintManager.setCurrentManager(counts);
    var entry=new BurpExtender(); entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi())); edt(()->{});
    var ext=(WorkbenchExtension)get(entry,"workbench"); var desktop=(WorkbenchWindow)get(ext,"workbenchWindow"); JFrame[] host={null};
    edt(()->{host[0]=new JFrame("Synthetic idle repaint probe");host[0].setBounds(10,10,800,600);JTabbedPane tabs=new JTabbedPane();tabs.addTab("Workbench",desktop.launcher);host[0].add(tabs);host[0].setVisible(true);});
    Thread.sleep(1500); edt(()->{counts.watch((Component)SimpleWorkflowTest.unchecked(ext,"root"));});
    ThreadMXBean mx=ManagementFactory.getThreadMXBean(); if(!mx.isThreadCpuTimeEnabled())mx.setThreadCpuTimeEnabled(true);
    long edtId=Arrays.stream(mx.getAllThreadIds()).filter(id->{ThreadInfo t=mx.getThreadInfo(id);return t!=null&&t.getThreadName().startsWith("AWT-EventQueue");}).findFirst().orElseThrow();
    System.out.println("JAR "+WorkbenchExtension.class.getProtectionDomain().getCodeSource().getLocation());
    try {
      for(String route:List.of("traffic","payload","data")){
        edt(()->ext.openFeature(route));Thread.sleep(500);edt(()->{});counts.reset();long cpu=mx.getThreadCpuTime(edtId),start=System.nanoTime();Thread.sleep(3000);long elapsed=System.nanoTime()-start,used=mx.getThreadCpuTime(edtId)-cpu;
        System.out.println(route+" elapsedMs="+(elapsed/1000000)+" edtCpuMs="+(used/1000000)+" dirty="+counts.dirty+" paints="+counts.paints);
        counts.origins.entrySet().stream().sorted((a,b)->Long.compare(b.getValue().get(),a.getValue().get())).limit(7).forEach(e->System.out.println("  "+e.getKey()+"="+e.getValue()));
        ThreadInfo info=mx.getThreadInfo(edtId,6);System.out.println(info);counts.events.entrySet().stream().sorted((a,b)->Long.compare(b.getValue().get(),a.getValue().get())).limit(4).forEach(e->{System.out.println("EVENT "+e.getKey()+"="+e.getValue());for(StackTraceElement frame:counts.traces.get(e.getKey()))System.out.println("  "+frame);});
      }
    } finally {edt(()->{desktop.close();for(var h:PayloadIntegrationTest.modernUnloads)h.extensionUnloaded();for(var h:PayloadIntegrationTest.legacyUnloads)h.extensionUnloaded();for(Window w:Window.getWindows())w.dispose();});}
  }
}
