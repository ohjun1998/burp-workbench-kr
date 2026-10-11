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
    static Object unchecked(Object owner,String name){try{return get(owner,name);}catch(Exception e){throw new RuntimeException(e);}}

    public static void main(String[] args) throws Exception {
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
        WorkbenchExtension ext = new WorkbenchExtension();
        edt(() -> ext.initialize(api));
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
                for (int i:List.of(0,1,2,3,4,5,6,7,8)) {
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
                root.removeNotify();
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        } finally {
            for(var unload:PayloadIntegrationTest.modernUnloads) unload.extensionUnloaded();
            edt(()->{});
        }
        Files.write(out.resolve("controls.csv"),rows,java.nio.charset.StandardCharsets.UTF_8);
        Files.write(out.resolve("content.csv"),contentRows,java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("Layout previews rendered: "+theme+" "+width+"x"+height+"; synthetic data, no Burp GUI or HTTP execution.");
    }
}
