package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
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
        if(c instanceof JTabbedPane tabs && !"wb.navigation.pages".equals(c.getName())) {
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
        if(c instanceof javax.swing.JLabel label&&label.getText()!=null&&!label.getText().isBlank()
                &&label.getFont().isBold()&&label.getFont().getSize()>=14&&!label.getText().startsWith("<html")) {
            var rect=javax.swing.SwingUtilities.convertRectangle(c.getParent(),c.getBounds(),root);
            var visible=visible(root,c,rect);var insets=label.getInsets();
            int textWidth=label.getFontMetrics(label.getFont()).stringWidth(label.getText());
            int iconWidth=label.getIcon()==null?0:label.getIcon().getIconWidth()+label.getIconTextGap();
            boolean fits=rect.width-insets.left-insets.right>=textWidth+iconWidth&&rect.height-insets.top-insets.bottom>=label.getFontMetrics(label.getFont()).getHeight();
            var textBounds=new java.awt.Rectangle();
            javax.swing.SwingUtilities.layoutCompoundLabel(label,label.getFontMetrics(label.getFont()),label.getText(),label.getIcon(),label.getVerticalAlignment(),label.getHorizontalAlignment(),label.getVerticalTextPosition(),label.getHorizontalTextPosition(),new java.awt.Rectangle(rect.x+insets.left,rect.y+insets.top,Math.max(0,rect.width-insets.left-insets.right),Math.max(0,rect.height-insets.top-insets.bottom)),new java.awt.Rectangle(),textBounds,label.getIconTextGap());
            textRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv("heading"),csv(label.getText()),"true",""+textWidth,""+rect.width,""+rect.height,""+visible.contains(textBounds),""+fits));
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
        if(args.length>5&&args[5].equals("guide")) {
            try {renderGuides(ext,out);writeMeasurements(out);}finally{unload();}
            System.out.println("Guide wrapper previews rendered: "+theme+" "+width+"x"+height+"; zero HTTP.");return;
        }
        try {
        String origin = "http://127.0.0.1:8088";
        for (String path : List.of("/api/community/fixed/posts/102", "/api/community/vuln/posts/102", "/v3/api-docs", "/js-lab"))
            importTraffic(ext, request(origin + path, "GET", true,
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
                capture.doClick(0);render(root,out.resolve("capture-off.png"),1440,920);
                capture.doClick(0);render(root,out.resolve("capture-scope.png"),1440,920);
                capture.doClick(0);
                var tree=(javax.swing.JTree)UiLayoutTest.named(root,"wb.navigation.tree");
                tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer());tree.setRowHeight(18);
                javax.swing.SwingUtilities.updateComponentTreeUI(root);
                render(root,out.resolve("host-theme-recovered.png"),1440,920);

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
