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
public final class GuidePreview extends WorkbenchTest {
    static void render(Container root, Path path, int width, int height) throws Exception {
        root.setSize(width, height);
        UiLayoutTest.layout(root);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        root.paint(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
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
        String origin = "http://127.0.0.1:8088";
        for (String path : List.of("/api/community/fixed/posts/102", "/api/community/vuln/posts/102", "/v3/api-docs", "/js-lab"))
            importTraffic(ext, request(origin + path, "GET", true,
                List.of(header("Cookie", "LAB_DEMO=alice"))), response(200, "{\"note\":\"guide demonstration data\"}"));
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
        JPanel root = (JPanel) get(ext, "root");
        JTabbedPane tabs = (JTabbedPane) get(ext, "tabs");
        edt(() -> {
            try {
                activity.refresh();
                root.addNotify();
                for (int i:List.of(0,1,3,4,5,6,7)) {
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
                render((Container) panel.taskWindow.content.get(), out.resolve("task-settings.png"), 1120, 700);
                var bounty=(BountySupportPanel)get(ext,"bountySupport");
                tabs.setSelectedComponent(bounty);
                for(int i:List.of(1,3)){
                    bounty.pages.setSelectedIndex(i);
                    render(root,out.resolve("bounty-"+i+".png"),1440,920);
                }
                root.removeNotify();
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        for(var unload:PayloadIntegrationTest.modernUnloads) unload.extensionUnloaded();
        edt(()->{});
        System.out.println("Guide previews rendered: synthetic data, no Burp GUI or HTTP execution.");
    }
}
