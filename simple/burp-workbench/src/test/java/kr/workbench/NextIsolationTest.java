package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.*;
import java.util.*;
import javax.swing.*;

/** Stable settings remain untouched even when a host returns the same backing store. */
public final class NextIsolationTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    Map<String,Object> saved=new HashMap<>();
    saved.put("wb.layout.horizontal",false);
    saved.put("wb.layout.reference.v1",true);
    saved.put("wb.passive.tool.waybackurls","/stable/tool");
    saved.put("js-analysis-archive-project-v1","11111111-1111-1111-1111-111111111111");
    saved.put("next.wb.layout.horizontal",false);
    saved.put("next.wb.layout.reference.v1",true);
    saved.put("next.wb.passive.tool.waybackurls","/next/tool");
    saved.put("next.js-analysis-archive-project-v1","22222222-2222-2222-2222-222222222222");
    Map<String,Object> stable=Map.copyOf(saved);
    var prefs=proxy(Preferences.class,(p,m,a)->switch(m.getName()) {
      case "getBoolean","getString" -> saved.get(a[0]);
      case "setBoolean","setString" -> {saved.put(a[0].toString(),a[1]);yield null;}
      default -> def(m);
    });
    var data=proxy(PersistedObject.class,(p,m,a)->switch(m.getName()) {
      case "getString" -> saved.get(a[0]);
      case "setString" -> {saved.put(a[0].toString(),a[1]);yield null;}
      default -> def(m);
    });
    var persistence=proxy(Persistence.class,(p,m,a)->switch(m.getName()) {
      case "preferences" -> prefs;case "extensionData" -> data;default -> def(m);
    });
    var api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:def(m));
    edt(()->{
      var layout=new LayoutSettings(api,new JPanel());
      check(layout.choice.getSelectedIndex()==1,"Simple starts with its own layout defaults");
      layout.choice.setSelectedIndex(0);
      check(Boolean.FALSE.equals(saved.get(BuildInfo.storageKey("wb.layout.horizontal"))),"Simple layout saves in its own namespace");
    });
    PassiveToolSettings.save(api,"waybackurls","/simple/tool");
    check(PassiveToolSettings.get(api,"waybackurls").equals("/simple/tool"),"Simple reads its own external tool setting");
    var archive=JsArchive.project(api);
    check(archive!=null&&archive.directory.toString().contains(BuildInfo.STORAGE_DIRECTORY),"Simple history uses a separate disk directory");
    check(!archive.directory.getFileName().toString().equals(stable.get("js-analysis-archive-project-v1")),"Simple generates a separate project archive identity");
    for(var entry:stable.entrySet())check(Objects.equals(saved.get(entry.getKey()),entry.getValue()),"stable value preserved: "+entry.getKey());
    check(BuildInfo.DISPLAY_NAME.contains("Simple")&&FeatureGuide.SUITE_TAB.contains("Simple"),"runtime identity visibly distinguishes Simple from stable and Next");
    System.out.println("NextIsolationTest: "+checks+" checks passed");
  }
}
