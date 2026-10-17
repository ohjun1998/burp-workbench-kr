package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Container;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/** Guide illustrations: production Swing UI + synthetic, local demonstration metadata.
 * This is not a native Burp GUI test and sends no network requests. */
public final class UiLayoutPreview extends WorkbenchTest {
    static final java.util.List<String> rows = new java.util.ArrayList<>();
    static final java.util.List<String> contentRows = new java.util.ArrayList<>();
    static final java.util.List<String> textRows = new java.util.ArrayList<>();
    static final java.util.List<Throwable> renderingErrors = new java.util.concurrent.CopyOnWriteArrayList<>();
    static String theme;
    static int width, height;
    static void render(Container root, Path path, int ignoredWidth, int ignoredHeight) throws Exception {
        root.setSize(width, height);
        for(int pass=0;pass<6;pass++) {
            UiLayoutTest.layout(root);
            var loop=java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            javax.swing.SwingUtilities.invokeLater(loop::exit);
            loop.enter();
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); root.paint(graphics); graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
        inspect(root, root, path.getFileName().toString());
    }
    static String csv(String value){return "\""+value.replace("\"","\"\"")+"\"";}
    static void inspect(Container root, java.awt.Component c, String view){
        if(!c.isVisible())return;
        if(c instanceof javax.swing.JTree tree && "wb.navigation.tree".equals(c.getName())) {
            for(int row=0;row<tree.getRowCount();row++) {
                var path=tree.getPathForRow(row);
                var local=tree.getRowBounds(row);
                if(path==null||local==null)continue;
                var rect=javax.swing.SwingUtilities.convertRectangle(tree,local,root);
                var clipped=visible(root,tree,rect);
                var renderer=tree.getCellRenderer().getTreeCellRendererComponent(tree,path.getLastPathComponent(),tree.isRowSelected(row),tree.isExpanded(row),tree.getModel().isLeaf(path.getLastPathComponent()),row,false);
                var preferred=renderer.getPreferredSize();
                textRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv("folder-menu"),csv(path.getLastPathComponent().toString()),"true",""+rect.width,""+clipped.width,""+rect.height,""+clipped.contains(rect),""+(clipped.width>=preferred.width && rect.width>=preferred.width)));
            }
        }
        if(c instanceof JTabbedPane tabs && !"wb.navigation.pages".equals(c.getName())
                && !Boolean.TRUE.equals(tabs.getClientProperty("wb.external.tabs"))) {
            for(int i=0;i<tabs.getTabCount();i++) {
                var local=tabs.getBoundsAt(i);if(local==null)continue;
                var rect=javax.swing.SwingUtilities.convertRectangle(tabs,local,root);
                var visible=visible(root,tabs,rect);
                // Swing scrolls tab captions within a private viewport, independently of its page area.
                for(var child:tabs.getComponents())if(child instanceof javax.swing.JViewport viewport&&viewport.isVisible())
                    visible=visible.intersection(javax.swing.SwingUtilities.convertRectangle(tabs,viewport.getBounds(),root));
                String label=tabs.getTitleAt(i);int textWidth=tabs.getFontMetrics(tabs.getFont()).stringWidth(label);
                int textHeight=tabs.getFontMetrics(tabs.getFont()).getHeight();
                boolean fits=rect.width>=textWidth+6&&rect.height>=textHeight;
                var textBounds=new java.awt.Rectangle(rect.x+(rect.width-textWidth)/2,rect.y+(rect.height-textHeight)/2,textWidth,textHeight);
                textRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv("tab"),csv(label),""+(tabs.getSelectedIndex()==i),""+textWidth,""+rect.width,""+rect.height,""+visible.contains(textBounds),""+fits));
            }
        }
        if(c instanceof javax.swing.JToggleButton tab && c.getName()!=null && c.getName().startsWith("wb.js.tab.")) {
            var rect=SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
            var clipped=visible(root,c,rect);int textWidth=tab.getFontMetrics(tab.getFont()).stringWidth(tab.getText());
            var textBounds=new java.awt.Rectangle(rect.x+11,rect.y+7,textWidth,tab.getFontMetrics(tab.getFont()).getHeight());
            textRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv("tab"),csv(tab.getText()),""+tab.isSelected(),""+textWidth,""+rect.width,""+rect.height,""+clipped.contains(textBounds),""+(rect.width>=textWidth+22)));
        }
        if(c instanceof javax.swing.JLabel label&&label.getText()!=null&&!label.getText().isBlank()
                &&label.getFont().isBold()&&label.getFont().getSize()>=14&&!label.getText().startsWith("<html")) {
            var rect=javax.swing.SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
            var visible=visible(root,c,rect);var insets=label.getInsets();
            int textWidth=label.getFontMetrics(label.getFont()).stringWidth(label.getText());
            int iconWidth=label.getIcon()==null?0:label.getIcon().getIconWidth()+label.getIconTextGap();
            boolean fits=rect.width-insets.left-insets.right>=textWidth+iconWidth&&rect.height-insets.top-insets.bottom>=label.getFontMetrics(label.getFont()).getHeight();
            var textBounds=new java.awt.Rectangle();
            javax.swing.SwingUtilities.layoutCompoundLabel(label,label.getFontMetrics(label.getFont()),label.getText(),label.getIcon(),label.getVerticalAlignment(),label.getHorizontalAlignment(),label.getVerticalTextPosition(),label.getHorizontalTextPosition(),new java.awt.Rectangle(rect.x+insets.left,rect.y+insets.top,Math.max(0,rect.width-insets.left-insets.right),Math.max(0,rect.height-insets.top-insets.bottom)),new java.awt.Rectangle(),textBounds,label.getIconTextGap());
            boolean scrollableHeading=false;for(var ancestor=c.getParent();ancestor!=null&&ancestor!=root;ancestor=ancestor.getParent())if(ancestor instanceof javax.swing.JViewport)scrollableHeading=true;
            boolean headingVisible=!scrollableHeading||(visible.y<=textBounds.y&&visible.y+visible.height>=textBounds.y+textBounds.height);
            textRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv("heading"),csv(label.getText()),""+headingVisible,""+textWidth,""+rect.width,""+rect.height,""+visible.contains(textBounds),""+fits));
        }
        if(c instanceof JsAnalysisTotals.Lamp label){
            var rect=SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);var clipped=visible(root,c,rect);
            int required=label.getPreferredSize().width;
            textRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv("aggregate"),csv(label.getText()),"true",""+required,""+rect.width,""+rect.height,""+clipped.contains(rect),""+(rect.width>=required&&rect.height>=JsAnalysisTotals.Lamp.ROW_HEIGHT)));
        }
        boolean control=c instanceof javax.swing.AbstractButton b&&b.getText()!=null&&!b.getText().isBlank()||c instanceof javax.swing.JTextField||c instanceof javax.swing.JComboBox;
        if(c instanceof javax.swing.text.JTextComponent&&!(c instanceof javax.swing.JTextField)||c instanceof javax.swing.JTable){
            var rect=javax.swing.SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
            var visible=new java.awt.Rectangle(rect);
            String card="";
            for(var a=c.getParent();a!=null&&a!=root;a=a.getParent()){
                if(card.isBlank()&&a.getName()!=null)card=a.getName();
                visible=visible.intersection(javax.swing.SwingUtilities.convertRectangle(a,new java.awt.Rectangle(0,0,a.getWidth(),a.getHeight()),root));
            }
            visible=visible.intersection(new java.awt.Rectangle(0,0,width,height));
            contentRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv(c.getClass().getSimpleName()),csv(c.getName()==null?card:c.getName()),""+rect.x,""+rect.y,""+Math.max(0,visible.width),""+Math.max(0,visible.height)));
        }
        if(control){
            java.awt.Rectangle rect=javax.swing.SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
            java.awt.Rectangle visible=new java.awt.Rectangle(rect);
            boolean scrollable=false;
            for(java.awt.Container a=c.getParent();a!=null&&a!=root;a=a.getParent()){
                if(a instanceof javax.swing.JViewport)scrollable=true;
                java.awt.Rectangle clip=javax.swing.SwingUtilities.convertRectangle(a,new java.awt.Rectangle(0,0,a.getWidth(),a.getHeight()),root);
                visible=visible.intersection(clip);
            }
            visible=visible.intersection(new java.awt.Rectangle(0,0,width,height));
            String label=c instanceof javax.swing.AbstractButton b?b.getText():c.getName()==null?c.getClass().getSimpleName():c.getName();
            boolean fullyVisible=visible.width>=rect.width&&visible.height>=rect.height&&rect.width>0&&rect.height>0;
            rows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv(label),""+rect.x,""+rect.y,""+rect.width,""+rect.height,""+fullyVisible,""+scrollable,""+c.isEnabled()));
        }
        if(c instanceof Container parent)for(var child:parent.getComponents())inspect(root,child,view);
    }
    static java.awt.Rectangle visible(Container root,java.awt.Component c,java.awt.Rectangle rect){
        var visible=new java.awt.Rectangle(rect);
        for(var parent=c.getParent();parent!=null&&parent!=root;parent=parent.getParent())
            visible=visible.intersection(javax.swing.SwingUtilities.convertRectangle(parent,new java.awt.Rectangle(0,0,parent.getWidth(),parent.getHeight()),root));
        return visible.intersection(new java.awt.Rectangle(0,0,width,height));
    }
    static Object unchecked(Object owner,String name){try{return get(owner,name);}catch(Exception e){throw new RuntimeException(e);}}

    static void renderGuides(WorkbenchExtension extension,Path out)throws Exception {
        java.util.List<String> measurements=new java.util.ArrayList<>();
        measurements.add("theme,width,height,feature,horizontalScroll,viewportWidth,contentWidth,textWidth,textHeight,lastGlyphBottom,maxGlyphRight,controlsVisible");
        edt(()->{
            try {
                JPanel root=(JPanel)unchecked(extension,"root");
                java.awt.Component[] content={null};
                extension.dialogOpener=(owner,title,body,modal,closed)->{content[0]=body;return null;};
                ((javax.swing.JButton)UiLayoutTest.named(root,"wb.feature.help")).doClick();
                var body=WorkbenchUi.settingsBody(content[0],()->{});
                var scroll=(javax.swing.JScrollPane)((java.awt.BorderLayout)body.getLayout()).getLayoutComponent(java.awt.BorderLayout.CENTER);
                var choices=(javax.swing.JComboBox<?>)UiLayoutTest.named(content[0],"wb.feature.guide.choice");
                var open=(javax.swing.JButton)UiLayoutTest.named(content[0],"wb.feature.guide.open");
                var prose=(javax.swing.JTextArea)UiLayoutTest.named(content[0],"wb.feature.guide.text");
                body.addNotify();
                for(var feature:FeatureGuide.all()) {
                    choices.setSelectedItem(feature);scroll.getViewport().setViewPosition(new java.awt.Point());
                    render(body,out.resolve("guide-"+feature.id()+".png"),width,height);
                    double maxRight=0,lastBottom=0;
                    for(int position=0;position<=prose.getDocument().getLength();position++) {
                        var glyph=prose.modelToView2D(position);
                        if(glyph!=null){maxRight=Math.max(maxRight,glyph.getMaxX());lastBottom=Math.max(lastBottom,glyph.getMaxY());}
                    }
                    boolean controlsVisible=true;
                    for(var control:List.of(choices,open)) {
                        var rectangle=javax.swing.SwingUtilities.convertRectangle(control.getParent(),control.getBounds(),body);
                        controlsVisible&=visible(body,control,rectangle).contains(rectangle)&&rectangle.width>0&&rectangle.height>0;
                    }
                    measurements.add(String.join(",",csv(theme),""+width,""+height,csv(feature.id()),""+scroll.getHorizontalScrollBar().isVisible(),""+scroll.getViewport().getExtentSize().width,""+content[0].getWidth(),""+prose.getWidth(),""+prose.getHeight(),""+(int)Math.ceil(lastBottom),""+(int)Math.ceil(maxRight),""+controlsVisible));
                }
                body.removeNotify();
            }catch(Exception error){throw new RuntimeException(error);}
        });
        Files.write(out.resolve("guides.csv"),measurements,java.nio.charset.StandardCharsets.UTF_8);
    }
    static void writeMeasurements(Path out)throws Exception {
        Files.write(out.resolve("controls.csv"),rows,java.nio.charset.StandardCharsets.UTF_8);
        Files.write(out.resolve("content.csv"),contentRows,java.nio.charset.StandardCharsets.UTF_8);
        Files.write(out.resolve("text.csv"),textRows,java.nio.charset.StandardCharsets.UTF_8);
    }
    static void unload()throws Exception {
        for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();
        for(var unload:PayloadIntegrationTest.legacyUnloads)unload.extensionUnloaded();
        edt(()->{});
        if(PayloadIntegrationTest.sent.get()!=0)throw new IllegalStateException("Layout rendering attempted HTTP requests");
    }
    static void renderAt(Container root,Path path,int w,int h)throws Exception {
        int previousWidth=width,previousHeight=height;
        try{width=w;height=h;render(root,path,w,h);}finally{width=previousWidth;height=previousHeight;}
    }
    static void installHeaders(Component component) {
        if(component instanceof JTable table){var parent=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);if(parent!=null)parent.setColumnHeaderView(table.getTableHeader());}
        if(component instanceof Container c)for(Component child:c.getComponents())installHeaders(child);
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{renderingErrors.add(error);error.printStackTrace();});
        Path out = Path.of(args[0]);
        theme=args.length>2?args[2]:"light";
        width=args.length>3?Integer.parseInt(args[3]):1024;
        height=args.length>4?Integer.parseInt(args[4]):720;
        if(theme.equals("dark")){
            for(String key:List.of("Panel.background","TabbedPane.background","Button.background","CheckBox.background","ComboBox.background","ScrollBar.background","control"))UIManager.put(key,new java.awt.Color(30,33,39));
            // Complete the simulated Metal dark palette; Burp supplies its own look-and-feel.
            UIManager.put("TabbedPane.selected",new java.awt.Color(69,58,48));
            for(String key:List.of("TabbedPane.selectHighlight","TabbedPane.highlight","TabbedPane.shadow","TabbedPane.darkShadow","controlHighlight","controlLtHighlight","controlShadow","controlDkShadow"))UIManager.put(key,new java.awt.Color(68,75,86));
            UIManager.put("TabbedPane.contentAreaColor",new java.awt.Color(30,33,39));
            for(String key:List.of("Label.foreground","Button.foreground","CheckBox.foreground","ComboBox.foreground","TabbedPane.foreground","textText"))UIManager.put(key,new java.awt.Color(228,233,241));
        }
        rows.add("theme,width,height,view,label,x,y,controlWidth,controlHeight,fullyVisible,scrollable,enabled");
        contentRows.add("theme,width,height,view,kind,name,x,y,visibleWidth,visibleHeight");
        textRows.add("theme,width,height,view,kind,label,selected,textWidth,allocatedWidth,allocatedHeight,fullyVisible,textFits");
        Files.createDirectories(out);
        Font font = Font.createFont(Font.TRUETYPE_FONT, Path.of(args[1]).toFile());
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        var keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            if (UIManager.get(key) instanceof FontUIResource)
                UIManager.put(key, new FontUIResource(font.deriveFont(14f)));
        }
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> def(m));
        MontoyaApi api = (MontoyaApi) PayloadIntegrationTest.host(MontoyaApi.class);
        burp.BurpExtender entry=new burp.BurpExtender();
        entry.registerExtenderCallbacks((burp.IBurpExtenderCallbacks)PayloadIntegrationTest.host(burp.IBurpExtenderCallbacks.class));
        edt(() -> entry.initialize(api));edt(()->{});
        WorkbenchExtension ext=(WorkbenchExtension)get(entry,"workbench");
        if(args.length>5&&args[5].equals("auth")) {
            try {
                var auth=(AuthComparePanel)get(ext,"authCompare");
                edt(()->{
                    JPanel desktop=(JPanel)unchecked(ext,"root");JTabbedPane pages=(JTabbedPane)unchecked(ext,"tabs");pages.setSelectedComponent(auth);desktop.setVisible(true);desktop.addNotify();installHeaders(desktop);
                    try{render(desktop,out.resolve("auth-empty-desktop.png"),width,height);}catch(Exception e){throw new RuntimeException(e);}
                    auth.setDetection(true);
                    auth.observe(AuthRecordTest.q("ALICE"),response(200,"{}"));auth.observe(AuthRecordTest.q("BOB"),response(200,"{}"));
                    var credentials=new java.util.ArrayList<>(auth.credentials.values());
                    auth.mapCandidate(credentials.get(0),new AuthAccount("Alice","alice","101","관리자","alice@example.test"));
                    auth.mapCandidate(credentials.get(1),new AuthAccount("Bob","bob","102","일반 사용자","bob@example.test"));
                    auth.assign(0,credentials.get(1));auth.assign(1,credentials.get(0));auth.anonymous(2);
                    var original=AuthRecordTest.q("ALICE");var baseline=response(200,"{\"secret\":\"alice\"}");
                    var run=new AuthComparePanel.Run(1,original,baseline,auth.context());
                    String[] notes={"우회 의심 · 비교 대상 JSON 필드 동일 · 실제 접근 확인","차단 근거 확인 · HTTP 403","판정 보류 · HTTP 500"};
                    for(int i=0;i<3;i++){run.packets[i].request=AuthRecordTest.q("DEMO");run.packets[i].response=response(i==0?200:i==1?403:500,i==0?"{\"secret\":\"alice\"}":"접근 거부 / 서버 오류");run.packets[i].note=notes[i];}
                    run.note="완료";auth.runs.add(run);auth.historyModel.addRow(new Object[]{1,original.url(),"","","","완료"});auth.refresh(run);auth.history.setRowSelectionInterval(0,0);auth.show(run);
                    installHeaders(auth);auth.setVisible(true);
                    try {
                        render(desktop,out.resolve("auth-desktop.png"),width,height);desktop.removeNotify();
                        render(auth,out.resolve("auth-results.png"),width,height);
                        auth.candidateDetails.load(credentials.get(1));installHeaders(auth.candidateDetails);auth.candidateDetails.setVisible(true);
                        renderAt(auth.candidateDetails,out.resolve("auth-candidate.png"),820,600);
                        renderAt(auth.candidateDetails,out.resolve("auth-candidate-minimum.png"),700,520);
                        auth.detailRun=run;installHeaders(auth.packetDetails);auth.packetDetails.setVisible(true);
                        renderAt(auth.packetDetails,out.resolve("auth-detail.png"),1180,780);
                        renderAt(auth.packetDetails,out.resolve("auth-detail-minimum.png"),840,520);
                        auth.responseDenialRules.setText("BODY: 접근 권한이 없습니다\nBODY_REGEX: (?i)access\\s+denied\nHEADER: X-Access: denied");
                        JPanel settings=WorkbenchUi.settingsBody(auth.settingsContent(),()->{});installHeaders(settings);
                        render(settings,out.resolve("auth-settings.png"),width,height);
                        for(Component child:settings.getComponents())if(child instanceof JScrollPane scroll)scroll.getViewport().setViewPosition(new java.awt.Point(0,Math.max(0,scroll.getViewport().getView().getHeight()-scroll.getViewport().getHeight())));
                        render(settings,out.resolve("auth-settings-bottom.png"),width,height);
                    }catch(Exception e){throw new RuntimeException(e);}
                });writeMeasurements(out);
            } finally {unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("Authorization preview failed");
            System.out.println("Authorization verdicts rendered: "+theme+" "+width+"x"+height+"; synthetic packets, zero HTTP.");return;
        }
        if(args.length>5&&args[5].equals("session")) {
            try {
                var auth=(AuthComparePanel)get(ext,"authCompare");var inspector=auth.cookieSessions;
                edt(()->{
                    auth.slots[0]=new AuthComparePanel.Candidate(1,"demo-session-fingerprint","https://app.example.test:443",java.util.Map.of("cookie","SID=DEMO; XSRF-TOKEN=DEMO; tracking_id=DEMO"),1,"");
                    inspector.loadSlot();inspector.url.setText("https://app.example.test/api/me");inspector.expected.setText("demo-user");
                    inspector.show(new CookieSessionProbe.Report(true,true,"신원 확인 완료 · 검증된 쿠키 조합 적용 가능",
                        List.of(new CookieSessionProbe.Finding("SID","조건 불일치","신원 일치","인증에 필요"),new CookieSessionProbe.Finding("XSRF-TOKEN","신원 일치","조건 불일치","CSRF 이름 후보 · 인증 기여 미관측"),new CookieSessionProbe.Finding("tracking_id","신원 일치","조건 불일치","이 검사에서 제외 가능")),
                        List.of(new CookieSessionProbe.Trial("전체 세션",List.of("SID","XSRF-TOKEN","tracking_id"),200,CookieSessionProbe.Outcome.MATCH,"설정한 사용자 식별값 일치"),new CookieSessionProbe.Trial("비로그인 대조",List.of(),401,CookieSessionProbe.Outcome.DIFFERENT,"인증 거부 응답"),new CookieSessionProbe.Trial("제외: SID",List.of("XSRF-TOKEN","tracking_id"),401,CookieSessionProbe.Outcome.DIFFERENT,"인증 거부 응답")),Set.of("SID"),true));
                    JPanel body=WorkbenchUi.settingsBody(inspector,()->{});installHeaders(body);
                    try {render(body,out.resolve("session-cookies.png"),width,height);inspector.results.setSelectedIndex(1);render(body,out.resolve("session-trials.png"),width,height);}catch(Exception e){throw new RuntimeException(e);}
                });writeMeasurements(out);
            } finally {unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("Session inspector preview failed");
            System.out.println("Session inspector rendered: "+theme+" "+width+"x"+height+"; synthetic findings, zero HTTP.");return;
        }
        if(args.length>5&&args[5].equals("js")) {
            try {
                var js=(JsAnalysisPanel)get(ext,"jsAnalysis");
                String origin="https://app.example.test";
                String code="const root='/api';\nconst payload = {name: 'demo', count: 2, active: true};\nfetch(root+'/users?lang=ko', {\n  method: 'POST',\n  headers: {'Content-Type': 'application/json'},\n  body: JSON.stringify(payload)\n});\nfetch(root+'/users/'+userId);\nconst apiKey='AIza"+"Z".repeat(35)+"';";
                edt(()->{
                    js.rememberPacket(origin+"/assets/app.js","GET /assets/app.js HTTP/1.1\r\nHost: app.example.test\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n\r\n"+code,"HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n\r\n"+code);
                    js.load(code,origin+"/assets/app.js");
                });JsAnalysisPanelTest.waitDone(js);
                edt(()->js.load("{\"api\":\"/api/settings\",\"api_key\":\"Q1w2e3R4t5Y6u7I8o9P0\"}",origin+"/config.json"));JsAnalysisPanelTest.waitDone(js);
                JPanel root=(JPanel)get(ext,"root");
                Component[] history={null},packets={null},repeater={null},details={null};
                edt(()->{
                    ((JTabbedPane)unchecked(ext,"tabs")).setSelectedComponent(js);
                    js.sources.setSelectedItem(origin+"/assets/app.js");js.urlTable.setRowSelectionInterval(0,0);
                    js.analysisWindow.opener=(owner,title,content,modal,closed)->{history[0]=WorkbenchUi.settingsBody(content,()->{});return null;};
                    js.packetWindow.opener=(owner,title,content,modal,closed)->{packets[0]=WorkbenchUi.settingsBody(content,()->{});return null;};
                    js.repeater.opener=(owner,title,content,modal,closed)->{repeater[0]=content;return null;};
                    js.detailsWindow.opener=(owner,title,content,modal,closed)->{details[0]=WorkbenchUi.settingsBody(content,()->{});return null;};
                    js.analysisList.doClick(0);js.openPacket(js.urlTable,0);
                    js.prepareRepeater();js.analysisDetails.doClick(0);
                    try {
                        installHeaders(root);installHeaders(history[0]);installHeaders(packets[0]);
                        render(root,out.resolve("js-workspace.png"),width,height);
                        renderAt((Container)history[0],out.resolve("js-analysis-list.png"),760,480);
                        renderAt((Container)history[0],out.resolve("js-analysis-list-small.png"),560,300);
                        renderAt((Container)packets[0],out.resolve("js-packets.png"),760,480);
                        renderAt((Container)packets[0],out.resolve("js-packets-small.png"),560,300);
                        renderAt((Container)repeater[0],out.resolve("js-repeater.png"),1120,720);
                        renderAt((Container)repeater[0],out.resolve("js-repeater-small.png"),780,440);
                        JsRepeater.Session draft=(JsRepeater.Session)repeater[0];
                        draft.request.tabs.get(1).doClick(0);
                        draft.request.text.setText(new String(draft.request.editedBytes(),java.nio.charset.StandardCharsets.UTF_8).replaceFirst("^[A-Z_]+ ","REPLACE_METHOD "));
                        draft.send.doClick(0);
                        renderAt(draft,out.resolve("js-repeater-method-required.png"),780,440);
                        var inferred=JsMethodSelection.choose(new JsRequestDraft.Draft(origin+"/api/users","REPLACE_METHOD",""),Set.of("GET"));
                        var automatic=new JsRepeater.Session(js.repeater.api,List.of(inferred.draft()),List.of(inferred.reason()));
                        try{
                            renderAt(automatic,out.resolve("js-repeater-auto-method.png"),1120,720);
                            renderAt(automatic,out.resolve("js-repeater-auto-method-small.png"),780,440);
                        }finally{automatic.close();}
                        var fallback=JsMethodSelection.choose(new JsRequestDraft.Draft(origin+"/api/users","REPLACE_METHOD",""),Set.of());
                        var defaultGet=new JsRepeater.Session(js.repeater.api,List.of(fallback.draft()),List.of(fallback.reason()));
                        try{
                            renderAt(defaultGet,out.resolve("js-repeater-default-get.png"),1120,720);
                            renderAt(defaultGet,out.resolve("js-repeater-default-get-small.png"),780,440);
                        }finally{defaultGet.close();}
                        installHeaders(details[0]);
                        renderAt((Container)details[0],out.resolve("js-details.png"),980,640);
                        renderAt((Container)details[0],out.resolve("js-details-small.png"),620,400);
                        js.passive.doClick(0);render(root,out.resolve("js-analysis-off.png"),width,height);
                        js.totals.api.count(50000);js.totals.secret.count(50000);js.totals.domain.count(50000);js.totals.cloud.count(50000);
                        render(root,out.resolve("js-maximum-counts.png"),width,height);
                    }catch(Exception e){throw new RuntimeException(e);}
                });
                writeMeasurements(out);
            } finally {unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("JS response workspace preview failed");
            System.out.println("JS/text workspace and modeless windows rendered: "+theme+" "+width+"x"+height+"; synthetic packets, zero HTTP.");return;
        }
        if(args.length>5&&args[5].equals("packet")) {
            try {
                String origin="https://app.example.test";
                for(int i=0;i<12;i++) {
                    String path=i==3?"/api/profile":"/api/"+List.of("users","settings","notifications","logs").get(i%4)+"/"+(i+1);
                    var base=request(origin+path,i==3?"POST":"GET",true,List.of());
                    String wire=(i==3?"POST":"GET")+" "+path+" HTTP/1.1\r\nHost: app.example.test\r\nContent-Type: application/json\r\n\r\n{\"userId\":42,\"name\":\"demo\",\"role\":\"viewer\"}";
                    var q=proxy(burp.api.montoya.http.message.requests.HttpRequest.class,(p,m,a)->switch(m.getName()){
                        case "toString"->wire;case "toByteArray"->bytes(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));default->m.invoke(base,a);
                    });
                    seedTraffic(ext,q,response(200,"{\"success\":true,\"data\":{\"userId\":42,\"name\":\"demo\",\"role\":\"viewer\"}}"));
                }
                JPanel root=(JPanel)get(ext,"root");
                var request=(PacketInspector)get(ext,"requestPacket");var response=(PacketInspector)get(ext,"responsePacket");
                // Do not manufacture displayability without a heavyweight peer:
                // JTextPane accessibility caret events otherwise ask AWT for a screen location.
                edt(()->{
                    JTable history=(JTable)unchecked(ext,"logTable");
                    var parent=(javax.swing.JScrollPane)javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class,history);
                    parent.setColumnHeaderView(history.getTableHeader());
                    history.setRowSelectionInterval(3,3);
                });
                for(int mode=0;mode<3;mode++) {
                    int selected=mode;
                    edt(()->{request.tabs.get(selected).doClick(0);response.tabs.get(selected).doClick(0);request.search.setText(selected==2?"75 73 65 72":"userId");response.search.setText(selected==2?"75 73 65 72":"userId");});
                    PacketInspectorTest.idle(request);PacketInspectorTest.idle(response);
                    edt(()->{try{render(root,out.resolve(selected==0?"packet-pretty-search.png":selected==1?"packet-raw.png":"packet-hex.png"),width,height);}catch(Exception ex){throw new RuntimeException(ex);}});
                }
                writeMeasurements(out);
            }finally{unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("Packet preview failed");
            System.out.println("Packet views rendered: "+theme+" "+width+"x"+height+"; production UI, synthetic HTTP, zero requests.");return;
        }
        if(args.length>5&&args[5].equals("scope")) {
            try {
                edt(()->{
                    try {
                        WorkbenchScope scope=(WorkbenchScope)unchecked(ext,"localScope");
                        scope.syncBurp(TrafficScopeModesTest.config("https://native.example.test/"));
                        scope.add("https://manual.example.test/api",false);
                        java.awt.Component[] content={null};
                        ext.dialogOpener=(owner,title,body,modal,closed)->{content[0]=body;return null;};
                        ((JButton)unchecked(ext,"scopeListButton")).doClick(0);
                        JTable table=(JTable)unchecked(ext,"scopeTable");table.setRowSelectionInterval(1,1);
                        var body=WorkbenchUi.settingsBody(content[0],()->{});body.addNotify();
                        body.setSize(width,height);UiLayoutTest.layout(body);
                        table.scrollRectToVisible(table.getCellRect(1,2,true));
                        render(body,out.resolve("scope-list.png"),width,height);body.removeNotify();
                    }catch(Exception error){throw new RuntimeException(error);}
                });
                writeMeasurements(out);
            }finally{unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("Scope preview failed");
            System.out.println("Scope editor preview rendered: "+theme+" "+width+"x"+height+"; zero HTTP.");return;
        }
        if(args.length>5&&args[5].equals("guide")) {
            try {renderGuides(ext,out);writeMeasurements(out);}finally{unload();}
            System.out.println("Guide wrapper previews rendered: "+theme+" "+width+"x"+height+"; zero HTTP.");return;
        }
        try {
        String origin = "http://127.0.0.1:8088";
        for (String path : List.of("/api/community/fixed/posts/102", "/api/community/vuln/posts/102", "/v3/api-docs", "/js-lab"))
            seedTraffic(ext, request(origin + path, "GET", true,
                List.of(header("Cookie", "LAB_DEMO=alice"))), response(200, "{\"note\":\"guide demonstration data\"}"));
        edt(()->((PassiveScanWorkspace)unchecked(ext,"passiveScan")).open(origin,false));
        ActivityPanel activity = (ActivityPanel) get(ext, "activity");
        var tracker = activity.tracker.assessments;
        String alice = tracker.register(new AssessmentTracker.AccountInput("Alice", origin, "회원", "Lab", false));
        String bob = tracker.register(new AssessmentTracker.AccountInput("Bob", origin, "회원 / 소유자", "Lab", false));
        String admin = tracker.register(new AssessmentTracker.AccountInput("Admin", origin, "관리자", "Lab", false));
        String task = tracker.createTask("비공개 게시글 접근 정책", "취약/수정 경로와 3개 계정의 정책을 대조합니다.", Set.of(alice,bob,admin), true);
        tracker.taskRequirements(task, true, true, Set.of("URL:id"));
        tracker.addTarget(task, "GET", origin + "/api/community/fixed/posts/102");
        var recon=(ResponseReconPanel)get(ext,"responseRecon");
        String marker="WB_STAGE_DEMO01";
        recon.flows.register(marker);
        recon.flows.observe(origin+"/api/bounty/vuln/stored", "POST", marker, 201, "application/json", "{}");
        recon.flows.observe(origin+"/api/bounty/vuln/stored/301", "GET", "", 200, "text/html", "<p>"+marker+"</p>");
        var barrier=new java.util.concurrent.CountDownLatch(1);
        recon.submit(()->{recon.refresh(recon.generation.get());barrier.countDown();});
        if(!barrier.await(5, java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Preview queue did not finish");
        edt(()->{});
        edt(()->{
        var js=(JsAnalysisPanel)unchecked(ext,"jsAnalysis");
        js.input.setName("preview.js.source");
        var support=(BountySupportPanel)unchecked(ext,"bountySupport");
        support.workflow.setName("preview.workflow.input");
        support.workflowDetail.setName("preview.workflow.detail");
        support.impact.setName("preview.evidence.impact");
        support.repro.setName("preview.evidence.repro");
        });
        JPanel root = (JPanel) get(ext, "root");
        JTabbedPane tabs = (JTabbedPane) get(ext, "tabs");
        edt(() -> {
            try {
                activity.refresh();
                root.addNotify();
                java.awt.Component[] settings={null};
                ext.dialogOpener=(owner,title,content,modal,closed)->{settings[0]=content;return null;};
                for (int i=0;i<tabs.getTabCount();i++) {
                    if(tabs.getTitleAt(i).equals("설정")) {
                        tabs.setSelectedIndex(i);
                        if(!(settings[0] instanceof Container))throw new IllegalStateException("Settings action did not open content");
                        JPanel shell=new JPanel(new java.awt.BorderLayout());shell.add(settings[0],java.awt.BorderLayout.NORTH);
                        render(shell,out.resolve("settings.png"),1440,920);
                        continue;
                    }
                    tabs.setSelectedIndex(i);
                    render(root, out.resolve(String.format("tool-%02d.png", i)), 1440, 920);
                }
                tabs.setSelectedIndex(2);
                var dataTabs=(JTabbedPane)get(ext,"dataTabs");
                dataTabs.setSelectedIndex(2);
                var comparison=(StructuredComparePanel)dataTabs.getSelectedComponent();
                comparison.leftInput.setText("{\"id\":102,\"owner\":\"bob\",\"visibility\":\"private\"}");
                comparison.rightInput.setText("{\"error\":\"not found\"}");
                comparison.compare();
                render(root,out.resolve("data-compare.png"),1440,920);
                tabs.setSelectedComponent(recon);
                for(var component:recon.getComponents()) if(component instanceof JTabbedPane reconTabs)reconTabs.setSelectedIndex(2);
                if(recon.flowTable.getRowCount()>0){int last=recon.flowTable.getRowCount()-1;recon.flowTable.setRowSelectionInterval(last,last);}
                render(root,out.resolve("stored-flow.png"),1440,920);
                tabs.setSelectedComponent(activity);
                activity.coveragePages.setSelectedComponent(activity.assessmentPanel.accountsPage);
                render(root, out.resolve("accounts.png"), 1440, 920);
                activity.coveragePages.setSelectedComponent(activity.assessmentPanel.tasksPage);
                activity.assessmentPanel.taskTable.setRowSelectionInterval(0,0);
                render(root, out.resolve("tasks.png"), 1440, 920);
                var panel = activity.assessmentPanel;
                panel.taskTitle.setText("비공개 게시글 접근 정책");
                panel.taskNote.setText("계정별 접근 정책 · 파일 확인 증거 · 중요 입력 변경 재검토");
                for(var account : tracker.accounts()) panel.requiredModel.addElement(account);
                panel.requiredAccounts.setSelectionInterval(0,2);
                panel.strictInputs.setSelected(true);
                panel.allEvidence.setSelected(true);
                panel.criticalInputs.setText("URL:id, BODY:ownerId");
                // Dialogs are covered by the separate window checks; this matrix renders the main workspace.
                var bounty=(BountySupportPanel)get(ext,"bountySupport");
                tabs.setSelectedComponent(bounty);
                for(int i:List.of(1,3)){
                    bounty.pages.setSelectedIndex(i);
                    render(root,out.resolve("bounty-"+i+".png"),1440,920);
                }
                // Exercise the public common-layout path, not only the default split orientation.
                tabs.setSelectedComponent((JsAnalysisPanel)get(ext,"jsAnalysis"));
                WorkbenchUi.packetLayout(root,true);
                render(root,out.resolve("js-horizontal.png"),1440,920);
                WorkbenchUi.packetLayout(root,false);
                tabs.setSelectedIndex(0);
                var menu=(javax.swing.JButton)UiLayoutTest.named(root,"wb.navigation.toggle");
                var navigation=(javax.swing.JSplitPane)UiLayoutTest.named(root,"wb.navigation.split");
                menu.doClick();
                render(root,out.resolve("navigation-hidden.png"),1440,920);
                menu.doClick();
                navigation.setDividerLocation(285);
                render(root,out.resolve("navigation-wide.png"),1440,920);
                menu.doClick();menu.doClick();
                render(root,out.resolve("navigation-restored.png"),1440,920);
                WorkbenchUi.packetLayout(root,true);
                var capture=(javax.swing.JButton)UiLayoutTest.named(root,"wb.traffic.capture.mode");
                render(root,out.resolve("capture-off.png"),1440,920);
                capture.doClick(0);render(root,out.resolve("capture-scope.png"),1440,920);
                capture.doClick(0);
                var tree=(javax.swing.JTree)UiLayoutTest.named(root,"wb.navigation.tree");
                tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer());tree.setRowHeight(18);
                javax.swing.SwingUtilities.updateComponentTreeUI(root);
                render(root,out.resolve("host-theme-recovered.png"),1440,920);
                ((Diagnostics)get(ext,"diagnostics")).record("시안용 저장 오류",new java.io.IOException("SYNTHETIC_PACKET_TEXT_NOT_RECORDED"));
                ((JButton)UiLayoutTest.named(root,"wb.navigation.logs")).doClick(0);
                JTable errors=(JTable)UiLayoutTest.named(settings[0],"diagnostics.table");
                if(errors.getRowCount()>0)errors.setRowSelectionInterval(0,0);
                int workspaceWidth=width,workspaceHeight=height;
                width=workspaceWidth<=1024?680:760;height=workspaceHeight<=720?420:480;
                render(WorkbenchUi.settingsBody(settings[0],()->{}),out.resolve("logs.png"),width,height);
                width=workspaceWidth;height=workspaceHeight;

                root.removeNotify();
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        } finally {
            unload();
        }
        writeMeasurements(out);
        if(!renderingErrors.isEmpty())throw new IllegalStateException("Asynchronous UI errors during rendering: "+renderingErrors.size());
        System.out.println("Layout previews rendered: "+theme+" "+width+"x"+height+"; synthetic data, no Burp GUI or HTTP execution.");
    }
}
