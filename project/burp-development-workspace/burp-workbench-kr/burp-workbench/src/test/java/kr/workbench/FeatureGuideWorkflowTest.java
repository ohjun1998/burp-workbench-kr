package kr.workbench;

import burp.*;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Renamed entry points must still open their engine, and guidance must preserve the user's work. */
public final class FeatureGuideWorkflowTest extends WorkbenchTest {
    public static void main(String[] args) throws Exception {
        ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
        BurpExtender entry=new BurpExtender();
        entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
        edt(()->entry.initialize((MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class)));edt(()->{});
        WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");
        JPanel root=(JPanel)get(extension,"root");JTabbedPane tabs=(JTabbedPane)get(extension,"tabs");
        Object runner=get(entry,"payloadRunner");
        var source=request("https://guide.test/app/one","GET",true,List.of(header("Cookie","session=GUIDE_FIXTURE")));
        seedTraffic(extension,source,response(200,"fixture response"));
        AtomicReference<Component> dialog=new AtomicReference<>();AtomicReference<String> dialogTitle=new AtomicReference<>();
        AtomicInteger opened=new AtomicInteger();List<String> guides=new ArrayList<>();
        try {
            edt(()->{
                extension.dialogOpener=(owner,title,content,modal,closed)->{
                    check(!modal,"feature guidance remains modeless and does not block the selected tool");
                    opened.incrementAndGet();dialog.set(content);dialogTitle.set(title);return null;
                };
                JTable traffic=(JTable)unchecked(extension,"logTable");traffic.setRowSelectionInterval(0,0);
                Object selected=unchecked(extension,"current");
                JButton help=(JButton)UiLayoutTest.named(root,"wb.feature.help");
                check(help!=null&&help.isEnabled(),"feature help is available without configuring or executing a scan");
                Map<String,Component> routes=new LinkedHashMap<>();
                routes.put("요청·응답 목록",traffic);
                routes.put("계정 권한 비교",(Component)unchecked(extension,"authCompare"));
                routes.put("데이터 변환·비교",(Component)unchecked(extension,"dataTabs"));
                routes.put("JS에서 API 찾기",(Component)unchecked(extension,"jsAnalysis"));
                routes.put("서버·경로 탐색",(Component)unchecked(extension,"environments"));
                routes.put("URL 후보 수집",(Component)unchecked(extension,"passiveScan"));
                routes.put("점검 진행 현황",(Component)unchecked(extension,"activity"));
                routes.put("응답 보안 분석",(Component)unchecked(extension,"responseRecon"));
                routes.put("입력값 보안 검사",(Component)unchecked(runner,"panel"));
                routes.put("재현·증거 정리",(Component)unchecked(extension,"bountySupport"));
                routes.put("기능 자동 매핑",((kr.featuremapper.FeatureMapperWorkspace)unchecked(extension,"featureMapper")).component());
                for(var route:routes.entrySet()) {
                    String id=FeatureGuide.all().stream().filter(f->f.title().equals(route.getKey())).findFirst().orElseThrow().id();
                    extension.openFeature(id);
                    int index=tabs.getSelectedIndex();
                    check(index>=0,"task-oriented navigation exists: "+route.getKey());
                    Component selectedPage=tabs.getSelectedComponent();
                    check(route.getValue()==selectedPage||SwingUtilities.isDescendingFrom(route.getValue(),selectedPage),"renamed tab still opens its actual engine: "+route.getKey());
                    String tip=tabs.getToolTipTextAt(index);
                    check(tip!=null&&!tip.isBlank()&&!tip.equals(route.getKey()),"tab explains its purpose rather than only repeating its title: "+route.getKey());
                    int before=opened.get();help.doClick();
                    check(opened.get()==before+1&&dialog.get()!=null,"one help action opens guidance for selected feature: "+route.getKey());
                    String body=text(dialog.get());guides.add(body);
                    check((dialogTitle.get()+body).contains(route.getKey()),"guidance identifies the current feature after navigation: "+route.getKey());
                    check(tabs.getSelectedComponent()==selectedPage&&unchecked(extension,"current")==selected&&traffic.getSelectedRow()==0,"opening guidance preserves tool and selected request: "+route.getKey());
                }
                check(new HashSet<>(guides).size()==routes.size(),"different tools receive their own guidance instead of a reused previous screen");
                check(guides.get(0).contains("SCOPE")&&guides.get(0).contains("History"),"first-step guidance connects target scope with existing captured requests");
                extension.openFeature("data");
                JTabbedPane data=(JTabbedPane)unchecked(extension,"dataTabs");data.setSelectedIndex(0);
                JTextArea input=(JTextArea)unchecked(extension,"codecIn");input.setText("MANUALLY_EDITED_GUIDE_INPUT");
                help.doClick();help.doClick();
                check(input.getText().equals("MANUALLY_EDITED_GUIDE_INPUT")&&data.getSelectedIndex()==0,"repeated guidance preserves manual tool input and nested selection");
                Component guide=dialog.get();
                JComboBox<?> choice=(JComboBox<?>)UiLayoutTest.named(guide,"wb.feature.guide.choice");
                JButton openFeature=(JButton)UiLayoutTest.named(guide,"wb.feature.guide.open");
                check(choice!=null&&openFeature!=null,"feature guide offers direct navigation without closing and searching tabs");
                choice.setSelectedItem(FeatureGuide.feature("start"));
                String firstSteps=text(UiLayoutTest.named(guide,"wb.feature.guide.text"));
                check(firstSteps.contains("SCOPE")&&firstSteps.contains("History")&&firstSteps.contains("Repeater"),"beginner selection explains scope, existing requests and manual verification");
                Component beforeStart=tabs.getSelectedComponent();openFeature.doClick();
                check(!openFeature.isEnabled()&&tabs.getSelectedComponent()==beforeStart,"beginner explanation preserves the current work instead of selecting a non-tool page");
                choice.setSelectedItem(FeatureGuide.feature("auth"));openFeature.doClick();
                check(tabs.getSelectedComponent()==unchecked(extension,"authCompare"),"guide account-comparison link reaches the real comparison panel");
                JTextArea payloadInput=(JTextArea)unchecked(runner,"request");payloadInput.setText("MANUALLY_EDITED_PAYLOAD_INPUT");
                choice.setSelectedItem(FeatureGuide.feature("payload"));openFeature.doClick();
                check(tabs.getSelectedComponent()==unchecked(runner,"panel")&&payloadInput.getText().equals("MANUALLY_EDITED_PAYLOAD_INPUT"),"guide opens input testing without importing over edited request content");
                check(unchecked(extension,"current")==selected&&traffic.getSelectedRow()==0&&PayloadIntegrationTest.sent.get()==0,"guide shortcuts preserve the selected source and never run a check");
                choice.setSelectedItem(FeatureGuide.feature("data"));openFeature.doClick();
                check(input.getText().equals("MANUALLY_EDITED_GUIDE_INPUT")&&tabs.getSelectedIndex()==tabs.indexOfTab("요청·응답 목록")&&((JTabbedPane)unchecked(extension,"trafficTools")).getSelectedIndex()==1,"guide return to data preserves the user's local work");
                Component selectedPage=tabs.getSelectedComponent();int before=opened.get();
                choice.setSelectedItem(FeatureGuide.feature("settings"));openFeature.doClick();
                check(opened.get()==before+1&&tabs.getSelectedComponent()==selectedPage,"guide settings shortcut opens its dialog and keeps the current tool");
                before=opened.get();tabs.setSelectedIndex(tabs.indexOfTab("설정"));
                check(opened.get()==before+1&&tabs.getSelectedComponent()==selectedPage,"settings remains a separate action after navigation names change");
                help.doClick();
                check((dialogTitle.get()+text(dialog.get())).contains("데이터 변환·비교"),"help still describes the active tool after returning from settings");
                choice.setSelectedItem(FeatureGuide.feature("start"));
                JPanel guideWindow=WorkbenchUi.settingsBody(guide,()->{});
                var guideScroll=(JScrollPane)((BorderLayout)guideWindow.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                for(Dimension size:List.of(new Dimension(760,480),new Dimension(560,300))) {
                    guideWindow.setSize(size);
                    for(int pass=0;pass<6;pass++) {
                        UiLayoutTest.layout(guideWindow);
                        var loop=Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
                        SwingUtilities.invokeLater(loop::exit);loop.enter();
                    }
                    check(!guideScroll.getHorizontalScrollBar().isVisible()&&guide.getWidth()<=guideScroll.getViewport().getExtentSize().width,"real guide wrapper wraps long first-step instructions at "+size.width+"px without horizontal scrolling");
                    Rectangle controls=SwingUtilities.convertRectangle(openFeature.getParent(),openFeature.getBounds(),guide);
                    check(controls.x>=0&&controls.x+controls.width<=guide.getWidth(),"guide navigation control remains inside narrow content at "+size.width+"px");
                }
                check(PayloadIntegrationTest.sent.get()==0,"renamed navigation, all guidance and settings send no HTTP requests");
            });
        } finally {
            for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();
            for(var unload:PayloadIntegrationTest.legacyUnloads)unload.extensionUnloaded();edt(()->{});
        }
        int prior=opened.get();
        edt(()->((JButton)UiLayoutTest.named(root,"wb.feature.help")).doClick());
        check(opened.get()==prior,"unloaded extension cannot reopen a feature guide");
        System.out.println("FeatureGuideWorkflowTest: "+checks+" checks passed (headless Swing navigation; zero HTTP)");
    }
    private static Object unchecked(Object owner,String name){try{return get(owner,name);}catch(Exception error){throw new AssertionError(error);}}
    private static String text(Component component){
        StringBuilder result=new StringBuilder();
        if(component instanceof javax.swing.text.JTextComponent input)result.append(input.getText());
        else if(component instanceof JLabel label)result.append(label.getText());
        if(component instanceof Container container)for(Component child:container.getComponents())result.append('\n').append(text(child));
        return result.toString();
    }
}
