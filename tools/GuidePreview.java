package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/** Current production UI, including the real embedded Payload Runner, with synthetic
 * demonstration data. This generator never performs target HTTP or native Burp actions. */
public final class GuidePreview extends WorkbenchTest {
    private static final int WIDTH=1024, HEIGHT=720;
    static HttpRequestResponse pair(HttpRequest q,HttpResponse s){return proxy(HttpRequestResponse.class,(p,m,a)->switch(m.getName()){case "request"->q;case "response"->s;default->def(m);});}
    static HttpResponse reply(int code,String type,String body){
        String raw="HTTP/1.1 "+code+"\r\nContent-Type: "+type+"\r\n\r\n"+body;
        return proxy(HttpResponse.class,(p,m,a)->switch(m.getName()){
            case "statusCode"->(short)code;case "body"->bytes(body.getBytes(StandardCharsets.UTF_8));
            case "bodyToString"->body;case "toByteArray"->bytes(raw.getBytes(StandardCharsets.UTF_8));
            case "toString"->raw;case "headers"->List.of(header("Content-Type",type));default->def(m);
        });
    }
    static void render(Container root,Path path,int width,int height)throws Exception{
        root.setSize(width,height);
        for(int pass=0;pass<6;pass++){
            UiLayoutTest.layout(root);
            var loop=java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
            javax.swing.SwingUtilities.invokeLater(loop::exit);loop.enter();
        }
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();root.paint(graphics);graphics.dispose();ImageIO.write(image,"png",path.toFile());
    }
    static void render(Container root,Path path)throws Exception{render(root,path,WIDTH,HEIGHT);}
    static void show(WorkbenchExtension extension,String feature,JPanel root,Path out,String name)throws Exception{extension.openFeature(feature);render(root,out.resolve(name+".png"));}

    public static void main(String[] args)throws Exception{
        Path out=Path.of(args[0]);Files.createDirectories(out);
        Font font=Font.createFont(Font.TRUETYPE_FONT,Path.of(args[1]).toFile());
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        var keys=UIManager.getDefaults().keys();while(keys.hasMoreElements()){
            Object key=keys.nextElement();if(UIManager.get(key) instanceof FontUIResource)UIManager.put(key,new FontUIResource(font.deriveFont(14f)));
        }
        ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->{
            if(m.getReturnType()==HttpRequestResponse.class&&a.length>=2&&a[0] instanceof HttpRequest q&&a[1] instanceof HttpResponse s)return pair(q,s);
            return def(m);
        });
        MontoyaApi api=(MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class);
        BurpExtender entry=new BurpExtender();
        entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
        edt(()->entry.initialize(api));
        WorkbenchExtension ext=(WorkbenchExtension)get(entry,"workbench");
        Object payload=get(entry,"payloadRunner");
        String origin="http://127.0.0.1:8088";
        ((WorkbenchScope)get(ext,"localScope")).add(origin,true);
        HttpRequest bob=request(origin+"/api/community/fixed/posts/102","GET",true,List.of(header("Host","127.0.0.1:8088"),header("Cookie","JSESSIONID=DEMO_BOB")));
        HttpRequest alice=request(origin+"/api/community/fixed/posts/102","GET",true,List.of(header("Host","127.0.0.1:8088"),header("Cookie","JSESSIONID=DEMO_ALICE")));
        HttpResponse owned=reply(200,"application/json","{\"ID\":102,\"OWNER_ID\":2,\"TITLE\":\"Bob의 가상 비공개 글\",\"VISIBILITY\":\"private\"}");
        seedTraffic(ext,bob,owned);
        seedTraffic(ext,alice,reply(404,"application/json","{\"error\":\"not found\"}"));
        seedTraffic(ext,request(origin+"/api/community/vuln/posts/102","GET",true,alice.headers()),owned);
        String document="{\"openapi\":\"3.0.0\",\"paths\":{\"/api/community/fixed/posts/{id}\":{\"get\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"integer\"}}],\"responses\":{\"200\":{\"description\":\"post\"}}}}}}";
        seedTraffic(ext,request(origin+"/v3/api-docs","GET",true,List.of(header("Host","127.0.0.1:8088"))),reply(200,"application/json",document));
        seedTraffic(ext,request(origin+"/js-lab","GET",true,List.of(header("Host","127.0.0.1:8088"))),reply(200,"text/html","<html><body>가상 JS 실습 화면</body></html>"));
        ActivityPanel activity=(ActivityPanel)get(ext,"activity");
        var tracker=activity.tracker.assessments;
        String aliceId=tracker.register(new AssessmentTracker.AccountInput("Alice",origin,"회원","Lab",false));
        String bobId=tracker.register(new AssessmentTracker.AccountInput("Bob",origin,"회원 / 소유자","Lab",false));
        String task=tracker.createTask("비공개 글 읽기", "Bob 정상 읽기와 Alice 차단을 확인합니다.",Set.of(aliceId,bobId),true);
        tracker.taskRequirements(task,true,true,Set.of("PATH:102"));
        tracker.addTarget(task,"GET",origin+"/api/community/fixed/posts/102");
        var recon=(ResponseReconPanel)get(ext,"responseRecon");
        String marker="WB_STORE_DEMO01";
        recon.flows.register(marker);
        recon.flows.observe(origin+"/api/bounty/vuln/stored","POST",marker,201,"application/json","{}");
        recon.flows.observe(origin+"/api/bounty/vuln/stored/301","GET","",200,"text/html","<p>"+marker+"</p>");
        var barrier=new java.util.concurrent.CountDownLatch(1);
        recon.submit(()->{recon.refresh(recon.generation.get());barrier.countDown();});
        if(!barrier.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Preview queue did not finish");
        JPanel root=(JPanel)get(ext,"root");JTabbedPane tabs=(JTabbedPane)get(ext,"tabs");
        var js=(JsAnalysisPanel)get(ext,"jsAnalysis");
        edt(()->js.load("// 가이드용 가상 코드입니다. 실행하지 않습니다.\nconst endpoint='/api/bounty/fixed/objects/202';\nfunction loadExample(){return fetch(endpoint).then(r=>r.json());}\n",origin+"/assets/guide-demo.js"));
        ((ExecutorService)get(js,"worker")).submit(()->{}).get(5,TimeUnit.SECONDS);edt(()->{});
        edt(()->{
            try{
                activity.refresh();root.addNotify();
                ext.openFeature("traffic");render(root,out.resolve("traffic.png"));
                show(ext,"mapper",root,out,"mapper");
                show(ext,"auth",root,out,"auth");
                JTextArea raw=(JTextArea)get(payload,"request");raw.setText("GET /api/community/vuln/search?q=§PAYLOAD§ HTTP/1.1\r\nHost: 127.0.0.1:8088\r\nAccept: application/json\r\n\r\n");raw.setCaretPosition(0);
                show(ext,"payload",root,out,"payload");
                show(ext,"js",root,out,"js");
                show(ext,"environment",root,out,"environment");
                PassiveScanWorkspace urls=(PassiveScanWorkspace)get(ext,"passiveScan");urls.open(origin,false);
                show(ext,"passive",root,out,"urls");
                show(ext,"activity",root,out,"activity");
                activity.coveragePages.setSelectedComponent(activity.assessmentPanel.accountsPage);render(root,out.resolve("accounts.png"));
                activity.coveragePages.setSelectedComponent(activity.assessmentPanel.tasksPage);activity.assessmentPanel.taskTable.setRowSelectionInterval(0,0);render(root,out.resolve("tasks.png"));
                var panel=activity.assessmentPanel;panel.taskTitle.setText("비공개 글 읽기");panel.taskNote.setText("Bob 정상 읽기 · Alice 차단 · 현재 파일 증거");
                for(var account:tracker.accounts())panel.requiredModel.addElement(account);panel.requiredAccounts.setSelectionInterval(0,1);panel.strictInputs.setSelected(true);panel.allEvidence.setSelected(true);panel.criticalInputs.setText("URL:id, BODY:ownerId");
                render((Container)panel.taskWindow.content.get(),out.resolve("task-settings.png"),1024,580);
                ext.openFeature("response.recon");
                for(var component:recon.getComponents())if(component instanceof JTabbedPane reconTabs){
                    reconTabs.setSelectedIndex(0);render(root,out.resolve("response-findings.png"));
                    reconTabs.setSelectedIndex(1);render(root,out.resolve("response-api.png"));
                    reconTabs.setSelectedIndex(2);
                }
                if(recon.flowTable.getRowCount()>0)recon.flowTable.setRowSelectionInterval(0,0);
                render(root,out.resolve("stored-flow.png"));
                ext.openFeature("data");var dataTabs=(JTabbedPane)get(ext,"dataTabs");dataTabs.setSelectedIndex(2);
                var comparison=(StructuredComparePanel)dataTabs.getSelectedComponent();comparison.leftInput.setText("{\"ID\":102,\"OWNER_ID\":2,\"VISIBILITY\":\"private\"}");comparison.rightInput.setText("{\"error\":\"not found\"}");ComparisonTestSupport.compareAndWait(comparison);render(root,out.resolve("data-compare.png"));
                var bounty=(BountySupportPanel)get(ext,"bountySupport");ext.openFeature("bounty");
                bounty.flowOrigin.setText(origin);bounty.workflow.setText(bob.toString()+"\n===STEP===\n"+alice.toString());bounty.workflow.setCaretPosition(0);
                bounty.pages.setSelectedIndex(1);render(root,out.resolve("bounty-1.png"));
                bounty.captureObserved(List.of(pair(bob,owned),pair(alice,reply(404,"application/json","{\"error\":\"not found\"}"))),"가상 예시: Bob 정상 읽기 / Alice 수정 경로 차단");
                bounty.evidenceTitle.setText("비공개 글 읽기 - 가상 예시");bounty.impact.setText("이 화면은 가상 데이터입니다. 실제 영향은 현재 요청과 응답으로 확인한 뒤 작성합니다.");bounty.repro.setText("1. Bob 소유 글을 정상 조회\n2. Alice로 같은 수정 경로 조회\n3. 상태와 OWNER_ID·본문을 비교");
                bounty.pages.setSelectedIndex(3);render(root,out.resolve("bounty-3.png"));root.removeNotify();
            }catch(Exception e){throw new RuntimeException(e);}
        });
        for(var unload:PayloadIntegrationTest.modernUnloads)unload.extensionUnloaded();
        for(var unload:PayloadIntegrationTest.legacyUnloads)unload.extensionUnloaded();edt(()->{});
        if(PayloadIntegrationTest.sent.get()!=0)throw new IllegalStateException("Guide rendering attempted network requests");
        System.out.println("Guide previews: actual embedded tools, synthetic policy-consistent data, zero HTTP sends, no native Burp GUI.");
    }
}
