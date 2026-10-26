package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Exercises actual chip/preset actions, exact filtered originals, and host project changes. */
public final class TrafficSearchConditionsUiTest extends WorkbenchTest {
  static Component find(Component component,String name){
    if(name.equals(component.getName()))return component;
    if(component instanceof Container parent)for(Component child:parent.getComponents()){Component found=find(child,name);if(found!=null)return found;}
    return null;
  }
  static PersistedObject data(Map<String,String> values){
    return proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){
      case "getString"->values.get(a[0]);case "setString"->{values.put(a[0].toString(),a[1].toString());yield null;}default->def(m);
    });
  }
  public static void main(String[] args)throws Exception{
    installTrafficPacketFactory();
    Map<String,String> first=new HashMap<>(),second=new HashMap<>();
    first.put(BuildInfo.storageKey("auth-setup-library-project-v1"),"11111111-1111-1111-1111-111111111111");
    second.put(BuildInfo.storageKey("auth-setup-library-project-v1"),"22222222-2222-2222-2222-222222222222");
    AtomicReference<PersistedObject> current=new AtomicReference<>(data(first));
    var persistence=proxy(Persistence.class,(p,m,a)->m.getName().equals("extensionData")?current.get():def(m));
    var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:def(m));
    try(var disk=new DiskTrafficStore()){
      disk.append(request("https://conditions.test/items/1","GET",true,List.of()),response(403,"get"));
      disk.append(request("https://conditions.test/items/2","POST",true,List.of()),response(403,"post denied"));
      disk.append(request("https://conditions.test/items/3","POST",true,List.of()),response(200,"post success"));
      AtomicReference<TrafficBrowser> created=new AtomicReference<>();
      edt(()->{var model=new CaptureTableModel(disk::ref);model.publish(3);var table=new JTable(model);table.setRowSorter(new CaptureRowSorter(model,e->{throw e;}));created.set(new TrafficBrowser(table,model,new JTextField(),api,()->disk,()->{}));});
      TrafficBrowser browser=created.get();AtomicInteger changes=new AtomicInteger();AtomicReference<JButton> oldStatusChip=new AtomicReference<>();
      try{
        edt(()->{browser.onSearchStateChanged(changes::incrementAndGet);browser.method.setSelectedItem("POST");browser.response.setSelectedItem("4xx");browser.apply();browser.search.setText("items");});
        TrafficIncrementalSearchTest.idle(browser);
        check(browser.table.getRowCount()==1&&browser.table.convertRowIndexToModel(0)==1,"applied filters show the exact matching original");
        edt(()->check(find(browser.conditionBar(),"wb.traffic.condition.method") instanceof JButton&&find(browser.conditionBar(),"wb.traffic.condition.status") instanceof JButton,"active filters are visible removable controls"));
        edt(()->((JButton)find(browser.conditionBar(),"wb.traffic.condition.method")).doClick());
        TrafficIncrementalSearchTest.idle(browser);
        check(browser.query.method().equals("전체")&&browser.query.status().equals("4xx")&&browser.search.getText().equals("items"),"method chip clears only method and preserves status/search word");
        check(browser.table.getRowCount()==2,"clearing method really widens the row filter to matching GET and POST originals");
        String firstProject=browser.projectIdentity();TrafficQuery saved=browser.query;
        edt(()->browser.savePreset("오류 응답",saved,firstProject));
        check(first.get(BuildInfo.storageKey("traffic-search-presets-v1")).contains("오류 응답"),"named filters save to current project extension data");
        check(first.values().stream().noneMatch(v->v.contains("items")),"free search word never enters project storage");
        edt(()->{browser.method.setSelectedItem("GET");browser.response.setSelectedItem("전체");browser.apply();browser.search.setText("/items");((JComboBox<?>)find(browser.conditionBar(),"wb.traffic.conditions.saved")).setSelectedItem("오류 응답");((JButton)find(browser.conditionBar(),"wb.traffic.conditions.apply")).doClick();});
        TrafficIncrementalSearchTest.idle(browser);
        check(browser.query.equals(saved)&&browser.search.getText().equals("/items"),"preset button restores saved conditions and keeps current keyword");
        edt(()->oldStatusChip.set((JButton)find(browser.conditionBar(),"wb.traffic.condition.status")));
        int callbacks=changes.get();edt(()->browser.search.setText("new word"));
        check(changes.get()==callbacks,"typing a keyword does not persist workspace search state");
        edt(()->{current.set(data(second));browser.refresh();});
        check(browser.projectIdentity().equals("22222222-2222-2222-2222-222222222222")&&browser.query.equals(TrafficQuery.defaults()),"project switch loads its own default filter state");
        check(browser.search.getText().isEmpty(),"project switch clears the previous project's in-memory keyword");
        edt(()->check(((JComboBox<?>)find(browser.conditionBar(),"wb.traffic.conditions.saved")).getItemCount()==1,"another project cannot see the prior project's named filters"));
        edt(()->rejects(()->browser.savePreset("오래된 창",saved,firstProject),"stale project operation is refused"));
        check(!second.containsKey(BuildInfo.storageKey("traffic-search-presets-v1")),"stale save does not write into the new project");
        edt(()->{browser.method.setSelectedItem("POST");browser.response.setSelectedItem("200");browser.apply();});
        TrafficQuery secondQuery=browser.query;
        edt(()->oldStatusChip.get().doClick());
        check(browser.query.equals(secondQuery)&&browser.query.status().equals("200"),"retained chip from previous project cannot clear the new project's condition");
        edt(()->rejects(()->browser.restoreSearchState(TrafficSearchPresets.capture(saved),firstProject),"resume state must match the caller's expected project"));
        check(browser.query.equals(secondQuery),"rejected cross-project resume leaves active filters intact");
        String secondProject=browser.projectIdentity();
        edt(()->browser.savePreset("두 번째 프로젝트",TrafficQuery.defaults(),secondProject));
        String externallySaved=TrafficSearchPresets.encode(List.of(new TrafficSearchPresets.Preset("다른 창",saved)));
        second.put(BuildInfo.storageKey("traffic-search-presets-v1"),externallySaved);
        edt(()->rejects(()->browser.savePreset("뒤늦은 저장",saved,secondProject),"concurrent named-search modification is not overwritten"));
        check(externallySaved.equals(second.get(BuildInfo.storageKey("traffic-search-presets-v1"))),"newer saved-search library survives conflict");
        edt(()->{current.set(data(first));browser.refresh();});
        check(browser.query.equals(saved),"returning to first project restores its last applied filter conditions");
        edt(()->check(((JComboBox<?>)find(browser.conditionBar(),"wb.traffic.conditions.saved")).getItemCount()==2,"returning project restores its named filters"));
        edt(()->{((JButton)find(browser.conditionBar(),"wb.traffic.conditions.clear")).doClick();});
        TrafficIncrementalSearchTest.idle(browser);
        check(browser.query.equals(TrafficQuery.defaults())&&browser.search.getText().isEmpty()&&browser.table.getRowCount()==3,"clear-all action restores all originals and default conditions");
        edt(()->{browser.method.setSelectedItem("PATCH");browser.account.setText("Alice");browser.apply();browser.search.setText("old project keyword");});
        var failedRead=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()){
          case "getString"->{if(a[0].equals(BuildInfo.storageKey("auth-setup-library-project-v1")))yield "33333333-3333-3333-3333-333333333333";throw new IllegalStateException("project read failed");}
          default->def(m);
        });
        edt(()->{current.set(failedRead);browser.refresh();});
        check(browser.query.equals(TrafficQuery.defaults())&&browser.search.getText().isEmpty(),"new-project storage failure cannot retain the prior project's method/account/keyword filters");
        edt(()->check(!((JButton)find(browser.conditionBar(),"wb.traffic.conditions.save")).isEnabled(),"unreadable stored search library is preserved instead of overwritten"));
      }finally{edt(browser::close);}
    }
    System.out.println("TrafficSearchConditionsUiTest: "+checks+" checks passed");
  }
}
