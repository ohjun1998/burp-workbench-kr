package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.awt.event.*;
import javax.swing.*;

public final class JsCompactUpgradeTest extends WorkbenchTest {
  static void edt(Runnable r)throws Exception{SwingUtilities.invokeAndWait(r);}
  public static void main(String[] args)throws Exception{
    var a=JsAnalysis.analyze("fetch('/users',{method:'GET'});fetch('/gone');","https://m.test/app.js");
    var b=JsAnalysis.analyze("fetch('/users',{method:'POST',body:JSON.stringify({name:'demo'})});fetch('/new');","https://m.test/app.js");
    JsVersions versions=new JsVersions();versions.record("https://m.test/app.js","one",a);versions.record("https://m.test/app.js","two",b);
    var list=versions.forSource("https://m.test/app.js");var diff=JsVersions.compare(list.get(0),list.get(1));
    check(diff.stream().anyMatch(c->c.url().endsWith("/new")&&c.state().equals("신규")),"version comparison detects newly declared APIs");
    check(diff.stream().anyMatch(c->c.url().endsWith("/gone")&&c.state().equals("삭제")),"version comparison detects removed APIs");
    check(diff.stream().anyMatch(c->c.url().endsWith("/users")&&c.state().equals("메서드 변경")),"version comparison detects a method change at the same URL");
    versions.record("https://other.test/app.js","three",a);check(versions.forSource("https://m.test/app.js").size()==2,"versions are isolated by full source URL and origin");
    versions.record("https://m.test/app.js","two",b);check(versions.forSource("https://m.test/app.js").size()==2,"duplicate version does not fabricate a change");
    JsModuleLinks links=new JsModuleLinks();String page="https://m.test/index.html";
    links.remember("https://m.test/client.js","export const root='/api'; export const api=axios.create({baseURL:root});",page);
    String code="import {api} from './client.js';\napi.post('/users',{name:'demo'});";
    var linked=links.link("https://m.test/main.js",code,page);check(linked.used(),"captured relative ES module is connected without a network request");
    var result=JsModuleLinks.analyze(linked,"https://m.test/main.js","application/javascript",page);
    var endpoint=result.endpoints().stream().filter(e->e.url().endsWith("/api/users")).findFirst().orElseThrow(()->new AssertionError(result));
    check(endpoint.method().equals("POST")&&endpoint.line()==2&&endpoint.start()<code.length(),"cross-file Axios base URL retains importer method and original line");
    check(result.requestDrafts().values().stream().anyMatch(d->d.body().contains("demo")),"cross-file request body constants are retained in the in-memory draft");
    check(endpoint.evidence().contains("client.js"),"cross-file provenance identifies the captured dependency");
    links.remember("https://m.test/wrapper.js","export function send(url,data){return fetch(url,{method:'POST',body:JSON.stringify(data)});}",page);
    String wrapped="import {send} from './wrapper.js';\nsend('/wrapped',{n:7});";
    var wr=JsModuleLinks.analyze(links.link("https://m.test/main.js",wrapped,page),"https://m.test/main.js","application/javascript",page);
    check(wr.endpoints().stream().anyMatch(e->e.url().endsWith("/wrapped")&&e.method().equals("POST")),"captured fetch wrapper resolves its imported call site");
    check(!links.link("https://m.test/main.js","import {api} from 'https://outside.test/client.js';api.get('/x');",page).used(),"external imports are not silently fetched or linked");
    check(!links.link("https://m.test/main.js","const s=\"import {api} from './client.js';\";",page).used(),"a string literal cannot fabricate an import declaration");
    var fake=JsAnalysis.analyze("const apiKey='AIza"+"Z".repeat(35)+"';","https://m.test/main.js");check(fake.secrets().get(0).confidence().contains("반복 패턴 의심"),"pattern candidates retain a visible example-pattern qualification");
    AtomicReference<JsAnalysisPanel> ref=new AtomicReference<>();edt(()->ref.set(new JsAnalysisPanel(null,()->{})));var p=ref.get();
    try{
      edt(()->p.load("fetch('/api/admin/users');fetch('/api/posts');","https://m.test/main.js"));JsAnalysisPanelTest.waitDone(p);
      edt(()->{
        p.setSize(1280,800);for(int i=0;i<5;i++)UiLayoutTest.layout(p);
        JButton more=(JButton)UiLayoutTest.named(p,"wb.js.more");check(more.getText().contains("분석 관리")&&more.getIcon()!=null&&more.getAccessibleContext().getAccessibleName().contains("분석 관리"),"analysis management has a meaningful visible caption and accessible name");
        JPopupMenu menu=(JPopupMenu)more.getClientProperty("wb.js.more.menu");check(((JMenuItem)menu.getComponent(0)).getText().equals("트래픽 대조")&&((JMenuItem)menu.getComponent(1)).getText().equals("버전 비교"),"traffic and version comparison live only inside the more menu");
        check(p.urlTable.getColumnCount()==4&&p.urlTable.getColumnName(2).equals("호출 상태")&&p.urlTable.convertColumnIndexToModel(3)==6,"main API list has four compact method/path/state/call-location columns while changes remain in the full model");
        JPanel selectionHeader=(JPanel)UiLayoutTest.named(p,"wb.workflow.js.selection");
        check(p.resultView.summary.getPreferredSize().height<=30&&selectionHeader!=null
            &&SwingUtilities.isDescendingFrom(p.resultView.summary,selectionHeader),"PDF selected-path context occupies one line in the actual request-action header");
        JPanel sourceCard=(JPanel)UiLayoutTest.named(p,"wb.pdf.js.call.source");
        check(sourceCard!=null&&SwingUtilities.isDescendingFrom(p.input,sourceCard)
            &&SwingUtilities.isDescendingFrom(p.sourceCallLocation,sourceCard),"PDF call-source card retains the actual source reader and source-location context");
        KeyStroke ctrlR=KeyStroke.getKeyStroke(KeyEvent.VK_R,InputEvent.CTRL_DOWN_MASK);
        Object panelAction=p.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ctrlR);
        Object tableAction=p.urlTable.getInputMap(JComponent.WHEN_FOCUSED).get(ctrlR);
        check("wb.js.repeater".equals(panelAction)&&"wb.js.repeater".equals(tableAction)
            &&p.getActionMap().get(panelAction)==p.toRepeater&&p.urlTable.getActionMap().get(tableAction)==p.toRepeater,
            "both actual Ctrl+R bindings still invoke the exact retained Repeater action");
        check(p.urlTable.getToolTipText().contains("Ctrl+R")&&p.resolve.getText().equals("Repeater 준비")
            &&SwingUtilities.isDescendingFrom(p.resolve,selectionHeader),"actual path-table guidance advertises Ctrl+R beside the retained visible Repeater preparation control");
        check(!p.resultTabs.previous.isVisible()&&!p.resultTabs.next.isVisible()&&p.resultTabs.previous.getParent()==null,"tab arrows are removed from the visible layout");
        JButton types=(JButton)UiLayoutTest.named(p,"wb.workflow.js.types");JPopupMenu categories=types.getComponentPopupMenu();
        check(p.results.getTabCount()==7&&categories!=null&&categories.getComponentCount()==4,"all seven result models remain available with four secondary categories grouped in a menu");
        for(int index=0;index<3;index++)check(p.resultTabs.titles.get(index).isVisible(),"primary result category stays directly visible: "+index);
        for(int index=3;index<7;index++){
          check(!p.resultTabs.titles.get(index).isVisible(),"secondary result category leaves the crowded direct tab strip: "+index);
          JMenuItem item=(JMenuItem)categories.getComponent(index-3);item.doClick(0);
          check(p.results.getSelectedIndex()==index&&types.getText().contains(p.results.getTitleAt(index)),"secondary menu opens its retained result model and names the active category: "+index);
        }
        p.results.setSelectedIndex(0);check(p.repeater.sessions.isEmpty(),"category navigation never creates or sends a request");
        int x=p.resultTabs.viewport.getViewPosition().x;p.resultTabs.strip.dispatchEvent(new MouseWheelEvent(p.resultTabs.strip,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,5,5,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,1));check(p.resultTabs.viewport.getViewPosition().x>=x,"wheel gestures move only the result tab strip");
        p.versions.record("https://m.test/main.js","baseline",JsAnalysis.analyze("fetch('/api/posts');","https://m.test/main.js"));p.versions.record("https://m.test/main.js","current",p.currentResult());p.versionHashes.put(p.currentSourceKey(),"current");p.resultView.refresh();
        p.resultView.call.setSelectedItem("미호출");p.resultView.fresh.setSelected(true);check(p.urlTable.getRowCount()==1&&p.endpoints.getValueAt(p.urlTable.convertRowIndexToModel(0),0).toString().endsWith("/api/admin/users"),"combined missed-call and new-version filters use an intersection");
        p.urlTable.setRowSelectionInterval(0,0);check(p.toRepeater.isEnabled()&&p.repeater.sessions.isEmpty(),"filtered selection enables Ctrl+R without creating or sending a packet");
        p.resultView.priority.setSelected(true);p.resultView.apply();check(p.urlTable.getRowCount()==1,"priority combines with other filters without adding a toolbar row");
        p.resultView.fresh.setSelected(false);p.resultView.priority.setSelected(false);p.resultView.call.setSelectedIndex(0);p.resultView.apply();check(p.urlTable.getRowCount()==2,"resetting conditions restores every endpoint");
      });
    }finally{edt(p::close);}
    System.out.println("JsCompactUpgradeTest: "+checks+" checks passed");
  }
}
