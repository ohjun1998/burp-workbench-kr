package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Real disk originals, frozen search boundary, explicit mixed edits and project/store guards. */
public final class TrafficStabilization0534Test extends WorkbenchTest {
  static void await(BooleanSupplier condition,String message)throws Exception{
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<deadline){AtomicBoolean done=new AtomicBoolean();edt(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(10);}
    throw new AssertionError(message);
  }
  static TrafficBrowser browser(AtomicReference<DiskTrafficStore> disk,MontoyaApi api)throws Exception{
    AtomicReference<TrafficBrowser> result=new AtomicReference<>();
    edt(()->{var model=new CaptureTableModel(row->disk.get().ref(row));model.publish(Math.toIntExact(disk.get().size()));var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,error->{throw error;}));result.set(new TrafficBrowser(table,model,new JTextField(),api,disk::get,()->{}));});return result.get();
  }
  static void settled(TrafficBrowser browser)throws Exception{await(()->browser.positionReady(browser.capturePosition()),"traffic view did not finish");}
  static PersistedObject project(Map<String,String> values){return proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){case "getString"->values.get(a[0]);case "setString"->{values.put(a[0].toString(),a[1].toString());yield null;}default->def(m);});}
  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    frozenSearch();mixedEdits();projectAndStoreGuards();
    System.out.println("TrafficStabilization0534Test: "+checks+" checks passed");
  }
  static void frozenSearch()throws Exception{
    try(var disk=new DiskTrafficStore()){
      disk.append(request("https://freeze.test/original","GET",true,List.of()),response(200,"original"));
      AtomicReference<DiskTrafficStore> current=new AtomicReference<>(disk);var browser=browser(current,null);
      try{
        edt(()->{browser.refresh();browser.table.setRowSelectionInterval(0,0);browser.setViewFrozen(true);});
        disk.append(request("https://freeze.test/new-only","POST",true,List.of()),response(403,"later"));
        edt(()->{browser.publishCommittedRows();browser.refresh();check(browser.model.getRowCount()==1&&browser.pendingRows()==1,"capture appends remain on disk while published frozen boundary stays fixed");check(browser.freezeView.isSelected()&&browser.newTraffic.getText().contains("1건"),"frozen control exposes exact unshown original count");browser.search.setText("new-only");});settled(browser);
        check(browser.table.getRowCount()==0&&browser.view.count()==1,"editing a search while frozen cannot scan or expose newer originals");
        edt(()->browser.search.setText("original"));settled(browser);
        edt(()->{browser.table.setRowSelectionInterval(0,0);browser.newTraffic.doClick(0);});settled(browser);
        check(!browser.viewFrozen()&&browser.model.getRowCount()==2&&browser.pendingRows()==0,"explicit apply releases view and publishes queued rows");
        check(browser.table.getRowCount()==1&&browser.table.convertRowIndexToModel(browser.table.getSelectedRow())==0,"resume preserves exact selected original through active filtering");
        edt(()->browser.search.setText("new-only"));settled(browser);check(browser.table.getRowCount()==1&&browser.table.convertRowIndexToModel(0)==1,"resumed search can now resolve the later stored original");
        check(disk.size()==2&&disk.ref(0).url().endsWith("original")&&disk.ref(1).url().endsWith("new-only"),"view operations never replace or reorder authoritative disk packets");
      }finally{edt(browser::close);}
    }
  }
  static void mixedEdits()throws Exception{
    try(var disk=new DiskTrafficStore()){
      for(int i=0;i<3;i++)disk.append(request("https://marks.test/"+i,"GET",true,List.of()),response(200,"original"));
      var browser=browser(new AtomicReference<>(disk),null);
      try{
        edt(()->{browser.refresh();browser.note(0,"first note");browser.setStars(new int[]{0},true);browser.table.setRowSelectionInterval(0,2);browser.setSelectedStars(true);});settled(browser);
        edt(()->{check(browser.marks.get(disk.ref(0).id()).star()&&browser.marks.get(disk.ref(1).id()).star()&&browser.marks.get(disk.ref(2).id()).star(),"mixed selected importance values become explicitly all true");check(browser.table.getSelectedRowCount()==3&&browser.feedback.getText().contains("3"),"bulk editing retains selection and displays affected context");browser.note(1,"note after mark");browser.undoStarEdit();});settled(browser);
        check(browser.marks.get(disk.ref(0).id()).star()&&!browser.marks.get(disk.ref(1).id()).star()&&!browser.marks.get(disk.ref(2).id()).star(),"bulk undo restores each original mixed star value");
        check(browser.marks.get(disk.ref(1).id()).note().equals("note after mark")&&browser.marks.get(disk.ref(0).id()).note().equals("first note"),"importance undo preserves notes added before and after the edit");
        edt(()->{browser.setSelectedStars(false);check(browser.marks.values().stream().noneMatch(TrafficBrowser.Mark::star),"explicit clear makes every selected row unimportant");browser.undoStarEdit();check(browser.marks.get(disk.ref(0).id()).star(),"clear undo restores the sole prior important original");browser.clear();browser.publishCommittedRows();
          for(int i=0;i<TrafficBrowser.MAX_MARK_UNDO+4;i++)browser.setStars(new int[]{0},i%2==0);
          for(int i=0;i<TrafficBrowser.MAX_MARK_UNDO;i++)browser.undoStarEdit();
          rejects(browser::undoStarEdit,"importance edit history is bounded to twenty actual edits");
          rejects(()->browser.setStars(new int[]{-1},true),"invalid row validation precedes every bulk mutation");
          check(disk.size()==3,"annotations and their undo never delete originals");
        });
      }finally{edt(browser::close);}
    }
  }
  static void projectAndStoreGuards()throws Exception{
    Map<String,String> one=new HashMap<>(),two=new HashMap<>();String key=BuildInfo.storageKey("auth-setup-library-project-v1");one.put(key,"11111111-1111-1111-1111-111111111111");two.put(key,"22222222-2222-2222-2222-222222222222");
    AtomicReference<PersistedObject> values=new AtomicReference<>(project(one));
    var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?values.get():def(m));
    var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:def(m));
    try(var first=new DiskTrafficStore();var second=new DiskTrafficStore()){
      first.append(request("https://project.test/a","GET",true,List.of()),response(200,"a"));second.append(request("https://project.test/b","GET",true,List.of()),response(200,"b"));
      var current=new AtomicReference<>(first);var browser=browser(current,api);
      try{
        edt(()->{browser.setStars(new int[]{0},true);browser.setViewFrozen(true);values.set(project(two));browser.publishCommittedRows();check(!browser.viewFrozen()&&browser.marks.isEmpty()&&!browser.undoStars.isEnabled(),"project switch discards view freeze and prior annotation undo");rejects(browser::undoStarEdit,"old project edits cannot mutate the new project");browser.setStars(new int[]{0},true);current.set(second);rejects(browser::undoStarEdit,"same numeric sequence in replacement store cannot inherit prior star undo");check(!browser.undoStars.isEnabled(),"store mismatch clears stale undo controls");});
        check(one.values().stream().noneMatch(v->v.contains("project.test"))&&two.values().stream().noneMatch(v->v.contains("project.test")),"freeze and annotation history never persist request data into project settings");
      }finally{edt(browser::close);}
    }
  }
}
