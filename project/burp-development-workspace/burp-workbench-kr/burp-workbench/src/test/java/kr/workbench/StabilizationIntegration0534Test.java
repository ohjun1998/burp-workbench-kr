package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.*;
import burp.api.montoya.persistence.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Runs the visible traffic workflow through the production workspace, with isolated host adapters. */
public final class StabilizationIntegration0534Test extends WorkbenchTest {
  static final class Host {
    final AtomicReference<Map<String,Object>> project=new AtomicReference<>(new HashMap<>());
    final MontoyaApi api;
    Host() {
      project.get().put(WorkspaceResume.PROJECT_KEY,WorkspaceResumeTest.A);
      PersistedObject data=proxy(PersistedObject.class,(p,m,a)-> {
        if(m.getName().startsWith("get")&&a.length==1)return project.get().get(a[0]);
        if(m.getName().startsWith("set")&&a.length==2){project.get().put(a[0].toString(),a[1]);return null;}
        return def(m);
      });
      Object fallbackPersistence=PayloadIntegrationTest.host(Persistence.class);
      Persistence persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?data:m.invoke(fallbackPersistence,a));
      Object fallback=PayloadIntegrationTest.host(MontoyaApi.class);
      api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:m.invoke(fallback,a));
    }
  }

  public static void main(String[] args)throws Exception {
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    Host host=new Host();
    BurpExtender entry=new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(host.api));edt(()->{});
    WorkbenchExtension ext=(WorkbenchExtension)get(entry,"workbench");
    TrafficBrowser browser=(TrafficBrowser)get(ext,"trafficBrowser");
    JTable table=(JTable)get(ext,"logTable");
    CaptureTableModel model=(CaptureTableModel)get(ext,"logModel");
    DiskTrafficStore store=(DiskTrafficStore)get(ext,"trafficStore");
    int sent=PayloadIntegrationTest.sent.get();
    try {
      for(int i=0;i<40;i++)append(ext,request("https://stabilize.test/items/"+i,"GET",true,List.of()),response(200,"{\"id\":"+i+"}"));
      TrafficIncrementalSearchTest.query(browser,TrafficQuery.defaults(),"");
      AtomicReference<String> chosen=new AtomicReference<>();
      AtomicReference<Point> position=new AtomicReference<>();
      edt(()->{
        ext.openFeature("traffic");
        Container root=(Container)unchecked(ext,"root");root.setSize(1024,720);UiLayoutTest.layout(root);
        table.setRowSelectionInterval(12,12);table.getRowSorter().toggleSortOrder(0);
        var viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,table);
        viewport.setViewPosition(new Point(0,120));
        browser.setViewFrozen(true);
        chosen.set(selectedId(table,store));position.set(new Point(viewport.getViewPosition()));
      });
      int visible=model.getRowCount();
      for(int i=40;i<90;i++)append(ext,request("https://stabilize.test/items/"+i,"GET",true,List.of()),response(200,"{\"id\":"+i+"}"));
      edt(()->{
        browser.publishCommittedRows();
        check(store.size()==90,"frozen visible list keeps recording all committed originals");
        check(model.getRowCount()==visible&&browser.pendingRows()==50,"frozen list exposes the exact new request count without publishing rows");
        check(chosen.get().equals(selectedId(table,store)),"live capture does not replace the selected original while the view is frozen");
        check(position.get().equals(((JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,table)).getViewPosition()),"frozen capture retains the reader's scroll position");
        check(browser.newTraffic.getText().contains("50"),"production new-requests action displays the pending captured count");
        check(browser.viewFrozen(),"capture publication does not silently unfreeze the reader's view");
        browser.newTraffic.doClick(0);
      });
      TrafficUsability0532Test.await(()->browser.positionReady(browser.capturePosition()),"unfrozen traffic search did not settle");
      edt(()->{
        check(model.getRowCount()==90&&browser.pendingRows()==0,"explicit new-requests action publishes every retained row");
        check(chosen.get().equals(selectedId(table,store)),"explicit refresh retains the exact selected original under active sorting");
        table.getRowSorter().setSortKeys(List.of());table.setRowSelectionInterval(0,1);
        browser.note(0,"first note");browser.note(1,"second note");browser.toggleStar(0);
      });
      TrafficUsability0532Test.await(()->browser.positionReady(browser.capturePosition()),"annotated traffic search did not settle");
      edt(()->{
        table.setRowSelectionInterval(0,1);browser.setSelectedStars(true);
        check(mark(browser,store,0).star()&&mark(browser,store,1).star(),"mark-all handles mixed selected states without reversing an already marked row");
        browser.undoStarEdit();
        check(mark(browser,store,0).star()&&!mark(browser,store,1).star(),"bulk undo restores each selected original's prior importance state");
        check(mark(browser,store,0).note().equals("first note")&&mark(browser,store,1).note().equals("second note"),"importance editing and undo preserve both original observation notes");
        table.setRowSelectionInterval(0,1);fire(table,"wb.traffic.unmark");
        check(!mark(browser,store,0).star()&&!mark(browser,store,1).star(),"keyboard clear-all produces a predictable uniform state");
        fire(table,"wb.traffic.mark");
        check(mark(browser,store,0).star()&&mark(browser,store,1).star(),"keyboard mark-all applies to the same selected original identities");
        check("wb.traffic.popup".equals(table.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_F10,InputEvent.SHIFT_DOWN_MASK))),"row context menu has the standard keyboard binding");
        check(table.getActionMap().get("wb.traffic.note")!=null&&table.getActionMap().get("wb.traffic.group")!=null,"row note and group actions are available without a pointer-only listener");
        table.setRowSelectionInterval(1,1);
        ext.trafficNotePrompt=(owner,old)->{check(old.equals("second note"),"keyboard note editor opens the selected original's current note");return "keyboard observation";};
        fire(table,"wb.traffic.note");
        check(mark(browser,store,1).note().equals("keyboard observation")&&mark(browser,store,1).star(),"keyboard note editing updates the exact selected original without dropping importance");
        fire(table,"wb.traffic.popup");
        check(store.ref(1).id().equals(selectedId(table,store)),"keyboard context menu preserves the selected source identity");
      });
      TrafficIncrementalSearchTest.query(browser,new TrafficQuery("전체","전체",0,false,false,true,true,false,true,"",0),"");
      edt(()->{
        check(table.getRowCount()==1,"similar normalized paths form one visible request group");
        table.setRowSelectionInterval(0,0);fire(table,"wb.traffic.group");
      });
      TrafficUsability0532Test.await(()->browser.positionReady(browser.capturePosition())&&table.getRowCount()==90,"keyboard group expansion did not reveal retained originals");
      edt(()->{
        check(table.getRowCount()==90,"keyboard group action reveals every retained member without recapture");
        table.setRowSelectionInterval(0,0);fire(table,"wb.traffic.group");
      });
      TrafficUsability0532Test.await(()->browser.positionReady(browser.capturePosition())&&table.getRowCount()==1,"keyboard group collapse did not settle");
      edt(()->check(table.getRowCount()==1&&store.size()==90,"keyboard collapse changes the view while keeping all original packets"));
      check(PayloadIntegrationTest.sent.get()==sent,"frozen capture, bulk edits and keyboard annotations send zero HTTP requests");
    } finally {
      for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();
      for(var unload:PayloadIntegrationTest.legacyUnloads)unload.extensionUnloaded();edt(()->{});
    }
    check(browser.closed,"workspace unload closes frozen-view and keyboard workflow state");
    System.out.println("StabilizationIntegration0534Test: "+checks+" checks passed");
  }
  static TrafficBrowser.Mark mark(TrafficBrowser browser,DiskTrafficStore store,int raw) {
    return browser.marks.getOrDefault(store.ref(raw).id(),new TrafficBrowser.Mark(false,""));
  }
  static void fire(JTable table,String key) {
    Action action=table.getActionMap().get(key);check(action!=null,"production row action installed: "+key);
    action.actionPerformed(new ActionEvent(table,ActionEvent.ACTION_PERFORMED,key));
  }
  static String selectedId(JTable table,DiskTrafficStore store) {
    int row=table.getSelectedRow();return row<0?"":store.ref(table.convertRowIndexToModel(row)).id();
  }
  static Object unchecked(Object owner,String field){try{return get(owner,field);}catch(Exception error){throw new AssertionError(error);}}
  static void append(WorkbenchExtension ext,HttpRequest request,HttpResponse response)throws Exception {
    registerTrafficPacket(request,response);
    edt(()->{try {
      Method save=WorkbenchExtension.class.getDeclaredMethod("storeTraffic",String.class,HttpRequest.class,HttpResponse.class);save.setAccessible(true);
      Object row=save.invoke(ext,"통합 검사",request,response);
      Method add=WorkbenchExtension.class.getDeclaredMethod("addTraffic",row.getClass());add.setAccessible(true);add.invoke(ext,row);
    }catch(Exception error){throw new AssertionError(error);}});
  }
}
