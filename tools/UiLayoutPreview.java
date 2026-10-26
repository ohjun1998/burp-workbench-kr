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
    static final java.util.List<String> scenarioRows = new java.util.ArrayList<>();
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
                &&((label.getFont().isBold()&&label.getFont().getSize()>=14)
                    || Set.of("wb.next.version","wb.work.context.request","wb.work.context.state").contains(java.util.Objects.toString(label.getName(),"")))
                &&!label.getText().startsWith("<html")) {
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
        if(c instanceof javax.swing.text.JTextComponent&&!(c instanceof javax.swing.JTextField)||c instanceof javax.swing.JTable||c instanceof javax.swing.JList){
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
            rows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv(label),""+rect.x,""+rect.y,""+rect.width,""+rect.height,""+fullyVisible,""+scrollable,""+c.isEnabled(),csv(c.getName()==null?"":c.getName())));
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

    /** A real in-memory project store, while all non-persistence host behavior stays in the shared adapter. */
    static MontoyaApi previewApi() {
        var values=new java.util.concurrent.ConcurrentHashMap<String,java.util.Map<String,Object>>();
        var data=proxy(burp.api.montoya.persistence.PersistedObject.class,(p,m,a)->{
            String method=m.getName();
            if(method.equals("equals"))return p==a[0];
            if(method.equals("hashCode"))return System.identityHashCode(p);
            if(method.equals("toString"))return "Preview project persistence";
            String operation=method.startsWith("get")?"get":method.startsWith("set")?"set":method.startsWith("delete")?"delete":"";
            if(!operation.isEmpty()&&a!=null&&a.length>0) {
                var store=values.computeIfAbsent(method.substring(operation.length()),ignored->new java.util.concurrent.ConcurrentHashMap<>());
                String key=a[0].toString();
                if(operation.equals("get"))return store.get(key);
                if(operation.equals("delete")){store.remove(key);return null;}
                if(a[1]==null)store.remove(key);else store.put(key,a[1]);return null;
            }
            if(method.endsWith("Keys")) {
                String kind=method.substring(0,method.length()-4);
                kind=Character.toUpperCase(kind.charAt(0))+kind.substring(1);
                return Set.copyOf(values.getOrDefault(kind,java.util.Map.of()).keySet());
            }
            return def(m);
        });
        data.setString(BuildInfo.storageKey("auth-setup-library-project-v1"),"11111111-1111-4111-8111-111111111111");
        var fallbackPersistence=PayloadIntegrationTest.host(burp.api.montoya.persistence.Persistence.class);
        var persistence=proxy(burp.api.montoya.persistence.Persistence.class,(p,m,a)->
            m.getName().equals("extensionData")?data:m.invoke(fallbackPersistence,a));
        var fallback=PayloadIntegrationTest.host(MontoyaApi.class);
        return proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("persistence")?persistence:m.invoke(fallback,a));
    }

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
        Files.write(out.resolve("scenarios.csv"),scenarioRows,java.nio.charset.StandardCharsets.UTF_8);
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
    static void awaitUi(java.util.function.BooleanSupplier ready,String reason) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("EDT preview required");
        if(ready.getAsBoolean())return;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var loop=java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        var timer=new javax.swing.Timer(10,event->{if(ready.getAsBoolean()||System.nanoTime()>=deadline)loop.exit();});
        timer.start();try{loop.enter();}finally{timer.stop();}
        if(!ready.getAsBoolean())throw new IllegalStateException("Preview fixture timeout: "+reason);
    }
    static <T extends Component> List<T> components(Component root,Class<T> type) {
        var result=new java.util.ArrayList<T>();if(type.isInstance(root))result.add(type.cast(root));
        if(root instanceof Container parent)for(var child:parent.getComponents())result.addAll(components(child,type));
        return result;
    }
    static JButton action(Component root,String title) {
        return components(root,JButton.class).stream().filter(button->title.equals(button.getText())).findFirst().orElseThrow();
    }
    static void scenario(String view,String name,boolean passed,String detail) {
        scenarioRows.add(String.join(",",csv(theme),""+width,""+height,csv(view),csv(name),""+passed,csv(detail)));
        if(!passed)throw new IllegalStateException("Preview scenario failed: "+name+" · "+detail);
    }
    static void dialogPair(Container body,Path out,String name) throws Exception {
        installHeaders(body);renderAt(body,out.resolve(name+".png"),760,480);
        renderAt(body,out.resolve(name+"-minimum.png"),560,300);
    }
    static void installHeaders(Component component) {
        if(component instanceof JTable table){var parent=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,table);if(parent!=null)parent.setColumnHeaderView(table.getTableHeader());}
        if(component instanceof Container c)for(Component child:c.getComponents())installHeaders(child);
    }
    /** Production Next forms with synthetic retained packets; no scan or comparison is executed. */
    static void seedNext(WorkbenchExtension ext) throws Exception {
        String origin="https://app.example.test";
        edt(()->{
            ((WorkbenchScope)unchecked(ext,"localScope")).add(origin,true);
            try{var refresh=WorkbenchExtension.class.getDeclaredMethod("scopeChanged");refresh.setAccessible(true);refresh.invoke(ext);}
            catch(Exception error){throw new RuntimeException(error);}
        });
        for(String path:List.of("/assets/queued.js","/assets/partial.js","/assets/failed.js"))
            seedTraffic(ext,request(origin+path,"GET",true,List.of()),response(200,"fetch('/api/notices/101');"));
        seedTraffic(ext,request(origin+"/api/notices/101","GET",true,List.of(header("Cookie","DEMO_SESSION"))),
            response(200,"{\"id\":101,\"title\":\"기존 공지\",\"ownerId\":42,\"status\":\"draft\"}"));
        seedTraffic(ext,request(origin+"/api/notices/101","PATCH",true,List.of(header("Cookie","DEMO_SESSION"))),
            response(200,"{\"accepted\":true}"));
        var js=(JsAnalysisPanel)get(ext,"jsAnalysis");
        if(js.archive==null)throw new IllegalStateException("Disk history preview requires the persistence adapter");
        // Populate real disk indexes, including a second page. These are synthetic local records.
        var result=new JsAnalysis.Result(List.of(new JsAnalysis.Endpoint(origin+"/api/notices/101","GET","fetch","","","",1,0,5,true,"static")),List.of(),"complete",false);
        for(int i=0;i<205;i++) {
            String key=origin+String.format("/preview-history/part-%03d.js",i);
            js.archive.save(key,"fetch('/api/notices/101');",origin+"/","application/javascript",result,null,JsMiner.hash("preview-source-"+i));
        }
        js.archive.protect(origin+"/preview-history/part-000.js");
        js.archive.retention(new JsArchive.Retention(true,90,2048L*1024*1024));
    }

    static void renderNext(WorkbenchExtension ext,Path out) throws Exception {
        JPanel root=(JPanel)get(ext,"root");
        var auth=(AuthComparePanel)get(ext,"authCompare");
        var js=(JsAnalysisPanel)get(ext,"jsAnalysis");
        var recon=(ResponseReconPanel)get(ext,"responseRecon");
        DiskTrafficStore store=(DiskTrafficStore)get(ext,"trafficStore");
        JTable traffic=(JTable)get(ext,"logTable");
        traffic.setRowSelectionInterval(traffic.getRowCount()-1,traffic.getRowCount()-1);
        AuthEffectPanel effect=auth.effectSettings;
        effect.connectSelected(true);
        // Hold the worker, not the EDT: paint the actual pending state deterministically.
        var readsReleased=new java.util.concurrent.CountDownLatch(1);
        effect.loader.worker.execute(()->{try{readsReleased.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        JPanel reads;
        try {
            effect.openReads();reads=(JPanel)effect.suggestionWindow.content.get();
            scenario("next-auth-get-loading.png","GET candidates loading",!action(reads,"선택 URL을 조회 설정에 적용").isEnabled(),"Apply stays disabled while retained packets are read");
            dialogPair(WorkbenchUi.settingsBody(reads,()->{}),out,"next-auth-get-loading");
        }finally{readsReleased.countDown();}
        reads=(JPanel)effect.suggestionWindow.content.get();
        installHeaders(reads);
        JTable readTable=(JTable)UiLayoutTest.named(reads,"wb.auth.effect.read.candidates");
        awaitUi(()->readTable.getRowCount()>0,"GET candidates");
        scenario("next-auth-get-candidates.png","GET candidates ready",action(reads,"선택 URL을 조회 설정에 적용").isEnabled(),"Retained response candidates completed asynchronously");
        readTable.setRowSelectionInterval(0,0);
        JPanel readBody=WorkbenchUi.settingsBody(reads,()->{});
        renderAt(readBody,out.resolve("next-auth-get-candidates.png"),760,480);
        renderAt(readBody,out.resolve("next-auth-get-candidates-minimum.png"),560,300);
        for(int row=0;row<readTable.getRowCount();row++)if(readTable.getValueAt(row,0).toString().endsWith("/api/notices/101"))readTable.setRowSelectionInterval(row,row);
        action(reads,"선택 URL을 조회 설정에 적용").doClick(0);
        if(!effect.url.getText().endsWith("/api/notices/101"))throw new IllegalStateException("GET fixture candidate was not applied");
        var fieldsReleased=new java.util.concurrent.CountDownLatch(1);
        effect.loader.worker.execute(()->{try{fieldsReleased.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        JPanel fields;
        try {
            effect.openFields();fields=(JPanel)effect.suggestionWindow.content.get();
            scenario("next-auth-json-loading.png","JSON candidates loading",!action(fields,"선택 경로 적용").isEnabled(),"Apply stays disabled during local JSON parsing");
            dialogPair(WorkbenchUi.settingsBody(fields,()->{}),out,"next-auth-json-loading");
        }finally{fieldsReleased.countDown();}
        fields=(JPanel)effect.suggestionWindow.content.get();
        installHeaders(fields);
        JTable fieldTable=(JTable)UiLayoutTest.named(fields,"wb.auth.effect.json.candidates");
        awaitUi(()->fieldTable.getRowCount()>0,"JSON candidates");
        scenario("next-auth-json-fields.png","JSON candidates ready",action(fields,"선택 경로 적용").isEnabled(),"Observed JSON paths populated without sending GET");
        fieldTable.setRowSelectionInterval(0,0);
        JPanel fieldBody=WorkbenchUi.settingsBody(fields,()->{});
        renderAt(fieldBody,out.resolve("next-auth-json-fields.png"),760,480);
        renderAt(fieldBody,out.resolve("next-auth-json-fields-minimum.png"),560,300);
        effect.jsonPath.setText("$.title");effect.expected.setText("\"수정 공지\"");effect.enabled.setSelected(true);
        effect.replaceRules(List.of(
            new AuthEffectRules.Rule("공지 제목 수정", "https://app.example.test",effect.snapshot()),
            new AuthEffectRules.Rule("공지 삭제 확인", "https://app.example.test",new AuthEffectCheck.Config(true,"DELETE","/api/notices/101","https://app.example.test/api/notices/101","$.id","",AuthEffectCheck.Mode.ABSENT,1))));
        scenario("next-auth-effect-setup.png","Multiple write rules",effect.savedRules.size()==2&&effect.ruleChoice.getItemCount()==2,"PATCH and DELETE have separate named observation rules");
        JPanel effectBody=WorkbenchUi.settingsBody(effect,()->{});
        renderAt(effectBody,out.resolve("next-auth-effect-setup.png"),760,480);
        renderAt(effectBody,out.resolve("next-auth-effect-setup-minimum.png"),560,300);
        js.retries.clear();recon.retries.clear();
        var states=List.of(AnalysisRetries.State.QUEUE_FULL,AnalysisRetries.State.PARTIAL,AnalysisRetries.State.FAILED);
        var notes=List.of("시연: 대기열 한도 · 원본 다시 분석 가능","시연: 일부 범위 분석 · 원본과 분석 범위 확인","시연: 형식 처리 실패 · 원본 형식 확인 후 재시도");
        for(int i=0;i<3;i++) {
            var ref=store.ref(store.size()-5+i);
            js.retries.record(ref,ref.method(),ref.url(),states.get(i),notes.get(i));
            recon.retries.record(ref,ref.method(),ref.url(),states.get(i),notes.get(i));
        }
        var jsRetry=js.retryWindow.content.get();
        JPanel jsBody=WorkbenchUi.settingsBody(jsRetry,()->{});jsBody.addNotify();
        installHeaders(jsBody);
        renderAt(jsBody,out.resolve("next-js-retries.png"),760,480);
        if(js.retries.table.getRowCount()>0)js.retries.table.setRowSelectionInterval(0,0);
        renderAt(jsBody,out.resolve("next-js-retries-minimum.png"),560,300);
        jsBody.removeNotify();
        ext.openFeature("response.recon");
        ((JTabbedPane)UiLayoutTest.named(recon,"wb.response.pages")).setSelectedIndex(3);
        installHeaders(root);
        render(root,out.resolve("next-response-retries.png"),width,height);
        ext.openFeature("traffic");
        render(root,out.resolve("next-selected-context.png"),width,height);
        renderNextWorkflows(ext,out);
        effect.closeSuggestions();
    }

    /** Newly added 0.52 production panels. Synthetic provenance is never a real comparison result. */
    static void renderNextWorkflows(WorkbenchExtension ext,Path out) throws Exception {
        String origin="https://app.example.test";
        var auth=(AuthComparePanel)get(ext,"authCompare");
        var activity=(ActivityPanel)get(ext,"activity");var tracker=activity.tracker.assessments;
        var js=(JsAnalysisPanel)get(ext,"jsAnalysis");
        awaitUi(()->auth.setupBundles.save.isEnabled(),"project setup library restore");
        var bundle=auth.setupBundles.bundles.save(auth.captureBundle().withName("공지 관리 · 계정 권한과 쓰기 조회"),0);
        auth.setupBundles.refresh(bundle.key());
        scenario("next-auth-bundles.png","Setup bundle review",auth.setupBundles.model.size()==1&&auth.setupBundles.apply.isEnabled()&&bundle.settings().effects().size()==2,"Selected local bundle has two write rules; no apply/import/export operation is executed");
        dialogPair(WorkbenchUi.settingsBody(auth.setupBundles,()->{}),out,"next-auth-bundles");

        auth.assign(0,new AuthComparePanel.Candidate(1001,"preview-admin",origin,java.util.Map.of("cookie","sid=DEMO_ADMIN"),1,""));
        auth.assign(1,new AuthComparePanel.Candidate(1002,"preview-member",origin,java.util.Map.of("cookie","sid=DEMO_MEMBER"),1,""));
        auth.profileNames[0].setText("관리자");auth.profileNames[1].setText("일반 사용자");
        auth.matrixRules.setText("PATCH /api/notices/101 ALLOW DENY UNKNOWN UNKNOWN\nDELETE /api/notices/101 ALLOW DENY UNKNOWN UNKNOWN");
        // Capture the real selected-origin draft before later workflow fixtures change selection.
        var currentSetup=auth.captureBundle();
        var write=request(origin+"/api/notices/101","PATCH",true,List.of(header("Cookie","DEMO_ADMIN")));
        var context=auth.context();var readiness=AuthReadiness.selected(context,List.of(write));
        var chosen=new java.util.concurrent.atomic.AtomicReference<String>();
        var ready=AuthReadiness.content(context,readiness,List.of(),(row,kind)->chosen.set(kind+" "+row.method()+" "+row.url()));
        ((JButton)UiLayoutTest.named(ready,"wb.auth.readiness.fix.effect")).doClick(0);
        scenario("next-auth-readiness.png","Readiness row edit",readiness.rows().size()==2&&("effect PATCH "+write.url()).equals(chosen.get()),"Row action carries the exact selected method and URL");
        dialogPair(WorkbenchUi.settingsBody(ready,()->{}),out,"next-auth-readiness");

        String account=tracker.register(new AssessmentTracker.AccountInput("시연 검토자",origin,"관리자","가이드 시연",false));
        var existing=tracker.mapperDraft("preview-notice-feature","공지 관리",List.of("GET "+origin+"/api/notices/101","PATCH "+origin+"/api/notices/101"));
        String task=tracker.applyMapperDraft(existing,Set.of(account),false);
        auth.trackingAccounts[0]=account;
        tracker.beginAuthExecution("preview-run-completed","preview-original:1",write,auth.context());
        tracker.finishAuthExecution("preview-run-completed",ExecutionLedger.State.COMPLETED,"synthetic local fixture");
        tracker.executionEvidenceSaved("preview-run-completed","a".repeat(64));
        tracker.beginAuthExecution("preview-run-cancelled","preview-original:2",write,auth.context());
        tracker.finishAuthExecution("preview-run-cancelled",ExecutionLedger.State.CANCELLED,"synthetic local fixture");
        var history=activity.assessmentPanel.historyContent();
        components(history,JTabbedPane.class).get(0).setSelectedIndex(1);
        JTable executions=(JTable)UiLayoutTest.named(history,"wb.assessment.executions");
        var executionDetails=components(history,javax.swing.JTextArea.class).get(0);executionDetails.setName("preview.assessment.execution.details");
        executions.setRowSelectionInterval(0,0);
        scenario("next-execution-links.png","Execution evidence linkage",executions.getRowCount()==2&&tracker.execution("preview-run-completed").evidence().size()==1&&tracker.execution("preview-run-completed").bindings().stream().anyMatch(binding->binding.tasks().contains(task)),"Synthetic original → execution → policy revision → task → ZIP hash");
        dialogPair(WorkbenchUi.settingsBody(history,()->{}),out,"next-execution-links");

        var assessment=activity.assessmentPanel;
        activity.refresh();
        activity.coveragePages.setSelectedComponent(assessment.tasksPage);
        for(int i=0;i<assessment.tasks.size();i++)if(assessment.tasks.get(i).id().equals(task)) {
            int row=assessment.taskTable.convertRowIndexToView(i);assessment.taskTable.setRowSelectionInterval(row,row);break;
        }
        assessment.taskDetailPages.setSelectedIndex(1);
        assessment.remainingTable.setRowSelectionInterval(0,0);
        var navigation=(JTabbedPane)get(ext,"tabs");
        var mapperTools=(JTabbedPane)get(ext,"mapperTools");
        navigation.setSelectedComponent(mapperTools);mapperTools.setSelectedComponent(activity);
        scenario("next-remaining-checks.png","Remaining work and explicit next step",
            assessment.remaining.size()>=2&&assessment.remainingNext.isEnabled()
                &&assessment.remaining.stream().anyMatch(row->row.reasons().contains(AssessmentTracker.RemainingReason.CANCELLED))
                &&assessment.remaining.stream().anyMatch(row->row.reasons().contains(AssessmentTracker.RemainingReason.NOT_INSPECTED)),
            "Unrun and cancelled requests expose concrete reasons and exact execution IDs without sending requests");
        render((Container)get(ext,"root"),out.resolve("next-remaining-checks.png"),width,height);

        int before=tracker.tasks().size();
        activity.assessmentPanel.upsertMapperDraft("preview-notice-feature","공지 관리",List.of("GET "+origin+"/api/notices/101","DELETE "+origin+"/api/notices/101"));
        var draft=(Container)activity.assessmentPanel.mapperDraftWindow.content.get();
        var draftText=components(draft,javax.swing.JTextArea.class).get(0);draftText.setName("preview.assessment.mapper.changes");
        var accounts=(javax.swing.JList<?>)UiLayoutTest.named(draft,"wb.assessment.mapper.accounts");
        scenario("next-mapper-review.png","Mapper update review",tracker.tasks().size()==before&&accounts.getSelectedIndices().length==1&&draftText.getText().contains("추가 API 1개")&&draftText.getText().contains("기능에서 빠진 API 1개"),"Opening the review neither updates nor completes the task");
        dialogPair(WorkbenchUi.settingsBody(draft,()->{}),out,"next-mapper-review");
        activity.assessmentPanel.mapperDraftWindow.close();

        js.historySearch.setText("/preview-history/");js.analysisList.doClick(0);
        awaitUi(()->js.historyTable.getRowCount()==200&&js.historyNext.isEnabled(),"archive first page");
        var list=WorkbenchUi.settingsBody(js.analysisContent(),()->{});
        scenario("next-js-history-page1.png","JS first page",!js.historyPrevious.isEnabled()&&js.historyNext.isEnabled(),"205 disk records; first page holds 200 rows");
        dialogPair(list,out,"next-js-history-page1");
        js.historyNext.doClick(0);
        awaitUi(()->js.historyTable.getRowCount()==5&&js.historyPrevious.isEnabled()&&!js.historyNext.isEnabled(),"archive second page");
        scenario("next-js-history-page2.png","JS second page",js.historyPageLabel.getText().equals("201–205 / 205"),"Second page retains only five rows; previous page remains available");
        dialogPair(list,out,"next-js-history-page2");
        js.historySearch.setText("part-000.js");
        awaitUi(()->js.historyTable.getRowCount()==1&&js.historyModel.getValueAt(0,0).toString().endsWith("part-000.js"),"archive filtered page");
        scenario("next-js-history-search.png","JS full disk search",js.historyPageLabel.getText().equals("1–1 / 1"),"Search finds a record outside the original first page");
        dialogPair(list,out,"next-js-history-search");
        var storage=(Container)js.archiveSettings.content.get();
        awaitUi(()->components(storage,javax.swing.JLabel.class).stream().anyMatch(label->label.getText().contains("보존/이전 버전 1")),"archive disk usage");
        scenario("next-js-retention.png","JS retention policy",components(storage,javax.swing.JCheckBox.class).get(0).isSelected()&&action(storage,"기간이 지난 이력 정리").isEnabled(),"One protected record; saving retention criteria and explicit pruning remain separate");
        dialogPair(WorkbenchUi.settingsBody(storage,()->{}),out,"next-js-retention");
        renderNextUsability(ext,out,currentSetup,bundle);
    }

    /** 0.53.1 production review, project-filter and resume components; all metadata is synthetic. */
    static void renderNextUsability(WorkbenchExtension ext,Path out,AuthSetupBundles.Bundle currentSetup,AuthSetupBundles.Bundle bundle) throws Exception {
        var auth=(AuthComparePanel)get(ext,"authCompare");
        var review=auth.setupBundles.reviewContent(currentSetup,bundle);
        var summary=(javax.swing.JTextArea)UiLayoutTest.named(review,"wb.auth.setup.bundle.review.summary");
        scenario("next-auth-bundle-diff.png","Setup bundle readable diff",summary.getText().contains("현재 활성 설정과의 차이")
            &&summary.getText().contains("쓰기 허용은 해제"),"Production confirmation starts with rule differences and account reconnection guidance");
        dialogPair(review,out,"next-auth-bundle-diff");
        ((javax.swing.JToggleButton)UiLayoutTest.named(review,"wb.auth.setup.bundle.review.raw")).doClick(0);
        scenario("next-auth-bundle-json.png","Setup bundle raw toggle",
            UiLayoutTest.named(review,"wb.auth.setup.bundle.review.json").isVisible(),"Same production confirmation exposes full JSON only on request");
        dialogPair(review,out,"next-auth-bundle-json");

        var browser=ext.trafficBrowser;String previous=browser.captureSearchState();
        var filters=new TrafficQuery("PATCH","2xx",0,false,false,false,true,false,true,"",30);
        browser.restoreSearchState(TrafficSearchPresets.capture(filters));
        browser.savePreset("공지 수정 · 최근 응답",filters,browser.projectIdentity());
        awaitUi(()->browser.idle()&&browser.searchViewCurrent(),"saved filter results");
        if(browser.table.getRowCount()!=1)throw new IllegalStateException("Saved PATCH filter must retain exactly its synthetic original");
        browser.table.setRowSelectionInterval(0,0);
        ext.openFeature("traffic");
        Container desktop=(Container)get(ext,"root");
        scenario("next-traffic-conditions.png","Active filters and saved project conditions",
            UiLayoutTest.named(desktop,"wb.traffic.conditions")==browser.conditionBar()
                &&components(browser.conditionBar(),JButton.class).stream().anyMatch(button->button.getText().contains("PATCH"))
                &&((javax.swing.JComboBox<?>)UiLayoutTest.named(browser.conditionBar(),"wb.traffic.conditions.saved")).getItemCount()>0,
            "Production traffic workspace displays active conditions and the named project-preset row");
        render(desktop,out.resolve("next-traffic-conditions.png"),width,height);

        String project="11111111-1111-4111-8111-111111111111";
        var bookmark=new WorkspaceResume.Snapshot(project,"activity",3,"22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333:17",TrafficSearchPresets.capture(filters),System.currentTimeMillis());
        var restored=new java.util.concurrent.atomic.AtomicInteger();
        var resume=new WorkspaceResumePanel(bookmark,"저장된 업무를 찾을 수 없습니다. 점검 기록을 불러오세요.",false,"",
            ignored->restored.incrementAndGet(),restored::incrementAndGet);
        scenario("next-workspace-resume.png","Resume missing original and task guidance",
            resume.details.getText().contains("원본이 현재 임시 저장소에 없습니다")
                &&resume.details.getText().contains("점검 기록을 불러오세요")
                &&resume.details.getText().contains("PATCH")&&resume.details.getText().contains("다시 연결")
                &&restored.get()==0,
            "Actual resume panel explains missing originals/tasks, saved filters and account reconnection without restoring automatically");
        dialogPair(resume,out,"next-workspace-resume");

        browser.restoreSearchState(previous);
        renderUsability0532(ext,out);
    }

    /** 0.53.2 components, real UI actions and bounded synthetic metadata; zero target HTTP. */
    static void renderUsability0532(WorkbenchExtension ext,Path out)throws Exception {
        var opened=new java.util.ArrayList<String>();
        var quick=new QuickNavigationPanel(()->List.of("traffic","auth"),opened::add,()->{});
        quick.query.setText("JWT");
        scenario("usability-quick-navigation.png","Purpose search finds JWT without executing a request",
            quick.model.size()==1&&opened.isEmpty(),"Keyboard search resolves a feature route only");
        quick.openSelected();
        scenario("usability-quick-navigation.png","Selected purpose opens one route",opened.size()==1,
            "Enter and mouse share the navigation callback; this fixture records the route only");
        dialogPair(quick,out,"usability-quick-navigation");

        var jobs=new BackgroundJobs();var stops=new java.util.concurrent.atomic.AtomicInteger();
        var search=jobs.start("트래픽 검색","traffic",stops::incrementAndGet);
        search.progress("본문 검색",7,20);
        var finished=jobs.start("증거 ZIP 저장","evidence",null);finished.complete("파일 저장 완료");
        var waiting=jobs.start("계정 비교","auth",stops::incrementAndGet);
        var waitingView=jobs.snapshot().stream().filter(v->v.title().equals("계정 비교")).findFirst().orElseThrow();
        jobs.requestCancel(waitingView.id());
        var monitor=new JobMonitorPanel(jobs,opened::add);
        scenario("usability-background-jobs.png","Task monitor distinguishes cancel request from termination",
            jobs.snapshot().stream().anyMatch(v->v.state()==BackgroundJobs.State.CANCEL_REQUESTED)
                &&monitor.table.getRowCount()==3&&stops.get()==1,
            "Observed counters are shown; cancellation remains pending until worker acknowledges");
        dialogPair(monitor,out,"usability-background-jobs");monitor.close();jobs.close();

        var readiness=new GettingStartedPanel(()->new GettingStartedPanel.Readiness(true,false,12,1,1,false),opened::add);
        int before=opened.size();readiness.actions[0].doClick(0);
        scenario("usability-getting-started.png","Setup checklist opens settings without automatic requests",
            opened.size()==before+1&&readiness.states[1].getText().contains("12")
                &&readiness.states[4].getText().contains("ZIP"),
            "Production checklist derives status from supplied current workspace metadata");
        dialogPair(readiness,out,"usability-getting-started");readiness.close();

        var compare=new StructuredComparePanel();
        compare.leftInput.setText("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Role: reader\r\n\r\n{\"user\":{\"role\":\"reader\",\"active\":true}}");
        compare.rightInput.setText("HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nX-Role: reviewer\r\n\r\n{\"user\":{\"role\":\"reviewer\",\"active\":false},\"reason\":\"policy\"}");
        compare.compare();compare.summaryTable.setRowSelectionInterval(0,0);
        scenario("usability-response-summary.png","Response summary preserves raw evidence and field differences",
            compare.summaryTable.getRowCount()>=4&&compare.leftRaw.getText().contains("200 OK")
                &&compare.rightRaw.getText().contains("403 Forbidden"),
            "Status, header and JSON changes are display-only, with untouched original text");
        render(compare,out.resolve("usability-response-summary.png"),width,height);

        var bounty=(BountySupportPanel)get(ext,"bountySupport");
        bounty.clearEvidence();
        bounty.captureObserved(List.of(
            AuthRecordTest.rr(request("https://app.example.test/api/notices/101","GET",true,List.of()),response(200,"{\"id\":101}")),
            AuthRecordTest.rr(request("https://app.example.test/api/notices/101","PATCH",true,List.of()),response(200,"{\"accepted\":true}"))),"화면 검증용 합성 원본");
        bounty.evidenceTable.setRowSelectionInterval(0,0);bounty.removeSelectedEvidence();
        scenario("usability-evidence-editor.png","Selected evidence removal preserves other packet",bounty.evidenceCount()==1,
            "Production item removal updates the bundle without touching the source traffic");
        bounty.undoEvidenceEdit();
        scenario("usability-evidence-editor.png","Undo restores both exact evidence items",bounty.evidenceCount()==2,
            "The original retained packet references and bundle order are restored");
        bounty.impact.setText("화면 검증용 합성 설명 · 실제 서비스의 보안 영향 판정이 아닙니다.");
        bounty.repro.setText("1. 기준 원본 확인\n2. 변경 요청·응답 대조\n3. 가림 미리보기 후 파일 저장");
        bounty.impact.setName("preview.evidence.impact");bounty.repro.setName("preview.evidence.repro");
        ext.openFeature("bounty");bounty.pages.setSelectedIndex(3);
        scenario("usability-evidence-editor.png","Evidence editing actions available in production workspace",
            UiLayoutTest.named(bounty,"wb.bounty.evidence.save")!=null,
            "Item editing preserves original traffic; exported content still requires redaction review");
        render((Container)get(ext,"root"),out.resolve("usability-evidence-editor.png"),width,height);


        var validationOwner=new AuthComparePanel((MontoyaApi)get(ext,"api"),List::of);
        try {
            var effect=validationOwner.effectSettings;effect.path.setText("/api/items/{id}");effect.url.setText("invalid URL");
            effect.jsonPath.setText("ownerId");effect.refreshValidation();
            scenario("usability-inline-validation.png","Invalid fields explain disabled apply before any request",
                !effect.saveRuleButton.isEnabled()&&!effect.validation.message(effect.url).getText().isBlank()
                    &&effect.validation.message(effect.jsonPath).getText().contains("JSON"),
                "URL and JSON-path examples remain beside their inputs; no settings are applied");
            dialogPair(WorkbenchUi.settingsBody(effect,()->{}),out,"usability-inline-validation");
        } finally { validationOwner.close(); }
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
        rows.add("theme,width,height,view,label,x,y,controlWidth,controlHeight,fullyVisible,scrollable,enabled,name");
        contentRows.add("theme,width,height,view,kind,name,x,y,visibleWidth,visibleHeight");
        textRows.add("theme,width,height,view,kind,label,selected,textWidth,allocatedWidth,allocatedHeight,fullyVisible,textFits");
        scenarioRows.add("theme,width,height,view,scenario,passed,detail");
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
        MontoyaApi api = previewApi();
        burp.BurpExtender entry=new burp.BurpExtender();
        entry.registerExtenderCallbacks((burp.IBurpExtenderCallbacks)PayloadIntegrationTest.host(burp.IBurpExtenderCallbacks.class));
        edt(() -> entry.initialize(api));edt(()->{});
        WorkbenchExtension ext=(WorkbenchExtension)get(entry,"workbench");
        if(args.length>5&&args[5].equals("next")) {
            try {
                seedNext(ext);
                edt(()->{try{JPanel root=(JPanel)unchecked(ext,"root");root.addNotify();renderNext(ext,out);root.removeNotify();}catch(Exception error){throw new RuntimeException(error);}});
                writeMeasurements(out);
            }finally{unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("Next form preview failed");
            System.out.println("Next setup helpers, retry tables and shared context rendered with synthetic metadata; zero HTTP.");return;
        }
        if(args.length>5&&args[5].equals("traffic")) {
            try{
                String origin="https://app.example.test";
                for(int i=0;i<18;i++)seedTraffic(ext,request(origin+"/api/posts/"+(101+i),"GET",true,List.of(header("Cookie","DEMO_A"))),response(i%4==0?403:200,"{\"id\":"+(101+i)+",\"title\":\"점검용 게시글\"}"));
                seedTraffic(ext,request(origin+"/api/users/me","GET",true,List.of(header("Cookie","DEMO_A"))),response(200,"{\"name\":\"일반 A\"}"));
                seedTraffic(ext,request(origin+"/api/admin/users","GET",true,List.of(header("Cookie","DEMO_ADMIN"))),response(200,"[]"));
                var browser=ext.trafficBrowser;
                edt(()->{browser.identities=()->List.of(new TrafficBrowser.Identity(origin,"일반 A",java.util.Map.of("cookie","DEMO_A")),new TrafficBrowser.Identity(origin,"관리자",java.util.Map.of("cookie","sid=DEMO_ADMIN")));browser.group.setSelected(true);browser.normalize.setSelected(true);browser.apply();browser.note(0,"권한 확인");browser.toggleStar(0);});
                TrafficSearchUpgradeTest.idle(browser);
                edt(()->{
                    try{
                        JPanel root=(JPanel)unchecked(ext,"root");((JTabbedPane)unchecked(ext,"tabs")).setSelectedIndex(0);JTable table=(JTable)unchecked(ext,"logTable");table.setRowSelectionInterval(0,0);installHeaders(root);
                        render(root,out.resolve("traffic-workspace.png"),width,height);
                        browser.loadControls();JPanel settings=browser.content();renderAt(settings,out.resolve("traffic-settings.png"),550,440);renderAt(settings,out.resolve("traffic-settings-minimum.png"),480,360);
                        browser.advancedToggle.doClick(0);renderAt(settings,out.resolve("traffic-settings-advanced.png"),650,560);
                    }catch(Exception e){throw new RuntimeException(e);}
                });writeMeasurements(out);
            }finally{unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("Traffic preview failed");return;
        }
        if(args.length>5&&args[5].equals("environment")) {
            try {
                var environment=(EnvironmentPanel)get(ext,"environments");
                edt(()->{
                    String origin="https://app.example.test";var site=environment.catalog.observe(origin+"/","Content-Type: application/vnd.spring-boot.actuator.v3+json","<a href='/v3/api-docs'>API</a>");environment.origins.addItem(origin);
                    String url=origin+"/v3/api-docs",body="{\"openapi\":\"3.0.0\",\"paths\":{\"/api/users\":{\"get\":{}}}}";
                    var finding=EnvironmentContent.inspect(url,200,"Content-Type: application/json",body,"");
                    var result=new EnvironmentScan.Result(url,"GET /v3/api-docs HTTP/1.1\r\nHost: app.example.test\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"+body,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"+body,"HTTP 200 · API 명세 확인",200,finding,EnvironmentProject.hash(body),null);
                    environment.project.add(url,"anonymous",200,"OPENAPI",result.fingerprint());environment.project.add(url,"anonymous",403,"DENIED",result.fingerprint());environment.project.add(url,"anonymous",200,"OPENAPI",result.fingerprint());
                    environment.packets.put(url,new EnvironmentPanel.Packet(url,result.request(),result.response(),result.state(),result.decoded(),result,"anonymous"));environment.project.pending=List.of(new EnvironmentProject.Pending(origin+"/api/users",1));environment.refresh();
                    for(int i=0;i<environment.candidates.size();i++)if(environment.candidates.get(i).url().equals(url)){int row=environment.pathTable.convertRowIndexToView(i);environment.pathTable.setRowSelectionInterval(row,row);break;}
                    environment.requestView.setName("wb.environment.request.text");environment.responseView.setName("wb.environment.response.text");environment.setVisible(true);installHeaders(environment);
                    try{
                        render(environment,out.resolve("environment-workspace.png"),width,height);
                        var settings=WorkbenchUi.settingsBody(environment.settingsContent(),()->{});renderAt(settings,out.resolve("environment-settings.png"),760,480);renderAt(settings,out.resolve("environment-settings-minimum.png"),560,300);
                        var evidence=WorkbenchUi.settingsBody(environment.evidenceContent(),()->{});renderAt(evidence,out.resolve("environment-evidence.png"),760,480);renderAt(evidence,out.resolve("environment-evidence-minimum.png"),560,300);
                        var history=WorkbenchUi.settingsBody(environment.changesContent(),()->{});installHeaders(history);renderAt(history,out.resolve("environment-history.png"),760,480);renderAt(history,out.resolve("environment-history-minimum.png"),560,300);
                        environment.pause.setEnabled(true);environment.pause.setText("재개");environment.status.setText("일시정지 · HTTP 429 · 대기 후 재개 · 남음 1");render(environment,out.resolve("environment-paused.png"),width,height);
                    }catch(Exception error){throw new RuntimeException(error);}
                });writeMeasurements(out);
            }finally{unload();}
            if(!renderingErrors.isEmpty())throw new IllegalStateException("Environment preview failed");
            System.out.println("Environment views rendered: "+theme+" "+width+"x"+height+"; synthetic metadata, zero HTTP.");return;
        }
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
                    auth.assign(0,credentials.get(1));auth.assign(1,credentials.get(0));auth.anonymous(3);
                    var original=AuthRecordTest.q("ALICE");var baseline=response(200,"{\"secret\":\"alice\"}");
                    var run=new AuthComparePanel.Run(1,original,baseline,auth.context());
                    String[] notes={"우회 의심 · 비교 대상 JSON 필드 동일 · 실제 접근 확인","원본과 동일 인증 · 교차 인가 미검증","판정 보류 · HTTP 500"};
                    for(int i:new int[]{0,1,3}){run.packets[i].request=AuthRecordTest.q(i==1?"ALICE":"DEMO");run.packets[i].response=response(i<2?200:500,i==0?"{\"secret\":\"alice\"}":"접근 거부 / 서버 오류");run.packets[i].note=notes[i==3?2:i];}
                    run.baselineState=AuthComparePanel.Run.Baseline.VALID;run.baselineNote="현재 기준 HTTP 200 · 비교 직전 재확인";run.note="완료";auth.runs.add(run);auth.historyModel.addRow(new Object[]{1,original.url(),"","","","완료"});auth.refresh(run);auth.history.setRowSelectionInterval(0,0);auth.show(run);
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
                        WorkbenchUi.style(auth.expandedEvidence);renderAt(auth.expandedEvidence,out.resolve("auth-evidence.png"),960,560);
                        var fieldRun=new AuthComparePanel.Run(2,original,response(200,"{\"ownerId\":101,\"title\":\"관리 문서\",\"optional\":null}"),auth.context());fieldRun.note="완료";
                        fieldRun.packets[0].request=run.packets[0].request;fieldRun.packets[0].response=response(200,"{\"ownerId\":102,\"title\":\"일반 문서\",\"items\":[]}");fieldRun.packets[0].note="판정 보류 · JSON 필드 차이 · 데이터/실행 결과 확인";
                        auth.show(fieldRun);auth.expandedEvidence.tabs.setSelectedIndex(1);renderAt(auth.expandedEvidence,out.resolve("auth-fields.png"),960,560);auth.show(run);
                        var effectRun=new AuthComparePanel.Run(3,original,baseline,auth.context());effectRun.note="완료";effectRun.baselineState=AuthComparePanel.Run.Baseline.WRITE;effectRun.baselineNote="쓰기 원본 추가 재전송 없음 · 지정한 GET으로 처리 결과 확인";effectRun.packets[0].request=original;effectRun.packets[0].response=response(200,"{\"accepted\":true}");effectRun.packets[0].result(new AuthFinding(AuthFinding.Kind.SUSPECT,"기대 DENY · 후속 조회에서 기대 상태 변경 관측", ""));
                        var effectConfig=new AuthEffectCheck.Config(true,"POST","/api/document","https://auth.test/object/101","$.value","1",AuthEffectCheck.Mode.EQUALS,1);effectRun.packets[0].effect=new AuthEffectCheck.Evidence(effectConfig,new AuthEffectCheck.Snapshot(original,response(200,"{\"value\":0}"),true,0),new AuthEffectCheck.Snapshot(original,response(200,"{\"value\":1}"),true,1),AuthEffectCheck.Outcome.OBSERVED,"후속 조회에서 기대 상태 변경 관측 · 요청과의 인과관계는 재현 근거로 확인");auth.show(effectRun);renderAt(auth.expandedEvidence,out.resolve("auth-effect-evidence.png"),960,640);auth.show(run);
                        auth.responseDenialRules.setText("BODY: 접근 권한이 없습니다\nBODY_REGEX: (?i)access\\s+denied\nHEADER: X-Access: denied");
                        JPanel settings=WorkbenchUi.settingsBody(auth.settingsContent(),()->{});installHeaders(settings);
                        render(settings,out.resolve("auth-settings.png"),width,height);
                        for(Component child:settings.getComponents())if(child instanceof JScrollPane scroll)scroll.getViewport().setViewPosition(new java.awt.Point(0,Math.max(0,scroll.getViewport().getView().getHeight()-scroll.getViewport().getHeight())));
                        render(settings,out.resolve("auth-settings-bottom.png"),width,height);
                        auth.matrixRules.setText("GET /object/101 DENY ALLOW DENY DENY\nPOST /api/document DENY ALLOW DENY DENY");JPanel policy=WorkbenchUi.settingsBody(auth.matrixEditor,()->{});installHeaders(policy);render(policy,out.resolve("auth-policy.png"),width,height);
                        JPanel claims=WorkbenchUi.settingsBody(auth.cookieSessions.claimSettings(),()->{});renderAt(claims,out.resolve("auth-claims.png"),820,480);
                        JPanel effect=WorkbenchUi.settingsBody(auth.effectSettings,()->{});renderAt(effect,out.resolve("auth-effect.png"),1000,600);
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
                String baseline="fetch('/api/posts');fetch('/api/users/me');";
                String updated=baseline+"\nfetch('/api/admin/users');fetch('/api/export',{method:'POST'});fetch('/api/files/upload',{method:'POST'});fetch('/api/orders/101');fetch('/api/reports');fetch('/api/roles');fetch('/api/config');fetch('/api/profile',{method:'PATCH'});fetch('/api/notifications');fetch('/api/audit');";
                edt(()->js.loadContext(baseline,origin+"/assets/inventory.js","application/javascript",origin+"/"));JsAnalysisPanelTest.waitDone(js);
                edt(()->js.loadContext(updated,origin+"/assets/inventory.js","application/javascript",origin+"/"));JsAnalysisPanelTest.waitDone(js);
                JPanel root=(JPanel)get(ext,"root");
                Component[] history={null},packets={null},repeater={null},details={null};
                edt(()->{
                    ((JTabbedPane)unchecked(ext,"tabs")).setSelectedComponent(js);
                    js.sources.setSelectedItem(js.currentSourceKey());js.urlTable.setRowSelectionInterval(2,2);
                    js.analysisWindow.opener=(owner,title,content,modal,closed)->{history[0]=WorkbenchUi.settingsBody(content,()->{});return null;};
                    js.packetWindow.opener=(owner,title,content,modal,closed)->{packets[0]=WorkbenchUi.settingsBody(content,()->{});return null;};
                    js.repeater.opener=(owner,title,content,modal,closed)->{repeater[0]=content;return null;};
                    js.detailsWindow.opener=(owner,title,content,modal,closed)->{details[0]=WorkbenchUi.settingsBody(content,()->{});return null;};
                    js.analysisList.doClick(0);js.openPacket(js.urlTable,0);
                    js.prepareRepeater();js.analysisDetails.doClick(0);
                    try {
                        installHeaders(root);installHeaders(history[0]);installHeaders(packets[0]);
                        render(root,out.resolve("js-workspace.png"),width,height);
                        js.resultView.popup.setVisible(false);var filterPanel=(Container)js.resultView.popup.getComponent(0);WorkbenchUi.style(filterPanel);renderAt(filterPanel,out.resolve("js-filter.png"),285,260);
                        js.resultView.comparison.opener=(owner,title,body,modal,closed)->null;js.resultView.openTraffic();js.resultView.comparison.closeWindow();
                        renderAt(JsResultsView.tablePanel(js.resultView.traffic,"수집 패킷 대조 · 추가 요청 없음"),out.resolve("js-traffic-comparison.png"),850,430);
                        renderAt(js.resultView.versionContent(),out.resolve("js-version-comparison.png"),900,460);
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
                for(int mode=0;mode<4;mode++) {
                    int selected=mode;
                    if(mode==3)edt(()->{String html="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<html><body><h1>계정 페이지</h1><p>저장된 응답을 Render로 확인합니다.</p><table><tr><th>사용자</th><th>역할</th></tr><tr><td>Demo</td><td>일반</td></tr></table></body></html>";response.setPacket(html.getBytes(java.nio.charset.StandardCharsets.UTF_8),html);});
                    edt(()->{request.tabs.get(selected).doClick(0);response.tabs.get(selected).doClick(0);request.search.setText(selected==2?"75 73 65 72":"userId");response.search.setText(selected==2?"75 73 65 72":"userId");});
                    PacketInspectorTest.idle(request);PacketInspectorTest.idle(response);
                    edt(()->{try{render(root,out.resolve(selected==0?"packet-pretty-search.png":selected==1?"packet-raw.png":selected==2?"packet-hex.png":"packet-render.png"),width,height);}catch(Exception ex){throw new RuntimeException(ex);}});
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
        seedNext(ext);
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
                    if(tabs.getComponentAt(i) instanceof javax.swing.JComponent page
                            && "mapper".equals(page.getClientProperty("wb.feature.id"))) {
                        var mapper=unchecked(ext,"featureMapper");
                        var refresh=mapper.getClass().getDeclaredMethod("refresh");
                        refresh.setAccessible(true);refresh.invoke(mapper);
                        var packets=(JTable)UiLayoutTest.named(page,"fm.history");
                        if(packets.getRowCount()>0)packets.setRowSelectionInterval(0,0);
                    }
                    render(root, out.resolve(String.format("tool-%02d.png", i)), 1440, 920);
                }
                ext.openFeature("passive");
                render(root,out.resolve("url-candidates.png"),1440,920);
                ext.openFeature("data");
                var dataTabs=(JTabbedPane)get(ext,"dataTabs");
                dataTabs.setSelectedIndex(0);
                render(root,out.resolve("data-codec.png"),1440,920);
                dataTabs.setSelectedIndex(1);
                render(root,out.resolve("data-jwt.png"),1440,920);
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
                ext.openFeature("activity");
                render(root,out.resolve("activity-overview.png"),1440,920);
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
                ext.openFeature("traffic");
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

                renderNext(ext,out);
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
