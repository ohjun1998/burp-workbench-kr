package kr.workbench;

import java.nio.file.*;
import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.FontUIResource;

/** Production SCOPE dialog contents, rendered with synthetic host callbacks. */
public final class ScopeUiPreview {
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        Font font=Font.createFont(Font.TRUETYPE_FONT,Path.of(args[1]).toFile());
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        for(var key:java.util.Collections.list(UIManager.getDefaults().keys()))if(UIManager.get(key) instanceof FontUIResource)UIManager.put(key,new FontUIResource(font.deriveFont(14f)));
        UiLayoutPreview.rows.add("theme,width,height,view,label,x,y,controlWidth,controlHeight,fullyVisible,scrollable,enabled");
        try(var f=new TrafficScopeModesTest.Fixture()) {
            f.scope().add("https://demo.test/api",false);f.scope().add("https://demo.test/admin",false);f.scope().setEnabled(f.scope().rules().get(2),false);
            var refresh=WorkbenchExtension.class.getDeclaredMethod("refreshScopeList");refresh.setAccessible(true);
            SwingUtilities.invokeAndWait(()->{try{refresh.invoke(f.ext);}catch(Exception e){throw new RuntimeException(e);}});
            for(String theme:new String[]{"light","dark"})for(int[] size:new int[][]{{760,480},{560,300}}) {
                UIManager.put("Panel.background",theme.equals("dark")?new Color(30,30,30):new Color(246,247,249));
                UiLayoutPreview.theme=theme;UiLayoutPreview.width=size[0];UiLayoutPreview.height=size[1];
                SwingUtilities.invokeAndWait(()->{try{
                    var panel=WorkbenchUi.settingsBody((Component)WorkbenchTest.get(f.ext,"scopeContent"),()->{});
                    WorkbenchUi.style(panel);panel.addNotify();UiLayoutPreview.render(panel,out.resolve("scope-"+theme+"-"+size[0]+".png"),size[0],size[1]);
                }catch(Exception e){throw new RuntimeException(e);}});
            }
        }
        Files.write(out.resolve("controls.csv"),UiLayoutPreview.rows);
        System.out.println("SCOPE rendered at 760x480 and 560x300, light/dark; synthetic callbacks, no native Burp.");
        System.exit(0);
    }
}
