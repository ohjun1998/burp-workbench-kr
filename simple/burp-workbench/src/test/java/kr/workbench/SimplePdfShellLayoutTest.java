package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.Preferences;
import java.awt.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** The approved PDF shell keeps real routes, user sizing and controls intact. */
public final class SimplePdfShellLayoutTest extends WorkbenchTest {
  private static MontoyaApi api(Map<String,String> saved) {
    Preferences preferences = proxy(Preferences.class, (owner, method, args) -> switch (method.getName()) {
      case "getString" -> saved.get((String) args[0]);
      case "setString" -> { saved.put((String) args[0], (String) args[1]); yield null; }
      default -> def(method);
    });
    Persistence persistence = proxy(Persistence.class, (owner, method, args) ->
        method.getName().equals("preferences") ? preferences : def(method));
    return proxy(MontoyaApi.class, (owner, method, args) ->
        method.getName().equals("persistence") ? persistence : def(method));
  }

  private static JPanel page(String id) {
    JPanel page = new JPanel();
    page.putClientProperty("wb.feature.id", id);
    page.add(new JTextField("KEEP " + id + " original bytes"));
    return page;
  }

  private static void layout(FolderNavigation navigation, int width) {
    navigation.setSize(width, 800);
    for (int pass=0; pass<6; pass++) {
      UiLayoutTest.layout(navigation);
      navigation.restoreLayout();
    }
  }

  public static void main(String[] args) throws Exception {
    edt(() -> {
      for (int width : new int[] {1024,1280,1600}) {
        Map<String,String> saved = new HashMap<>();
        FolderNavigation.Pages pages = new FolderNavigation.Pages();
        JPanel traffic = page("traffic"), dashboard = page("dashboard"), payload = page("payload");
        pages.addTab("traffic",traffic); pages.addTab("dashboard",dashboard); pages.addTab("payload",payload);
        AtomicInteger settings = new AtomicInteger();
        JButton help = new JButton("도움말"), logs = new JButton("기록");
        help.setName("pdf.help"); logs.setName("pdf.logs");
        try (FolderNavigation navigation = new FolderNavigation(api(saved),pages,
            settings::incrementAndGet,help,logs,true)) {
          WorkbenchUi.style(navigation);
          layout(navigation,width);
          JSplitPane split = (JSplitPane) SimpleWorkflowTest.named(navigation,"wb.navigation.split");
          Component toolbar = SimpleWorkflowTest.named(navigation,"wb.navigation.toolbar");
          JLabel brand = (JLabel) SimpleWorkflowTest.named(navigation,"wb.navigation.brand");
          JLabel breadcrumb = (JLabel) SimpleWorkflowTest.named(navigation,"wb.navigation.breadcrumb");
          check(brand != null && "Workbench".equals(brand.getText()) && brand.getIcon()!=null,
              "approved PDF shell retains its Workbench wordmark and local vector mark at " + width);
          check(toolbar.getHeight()==46,"approved header is one 46px row at " + width);
          check(Math.abs(split.getDividerLocation()-(width<=1300?174:190))<=1,
              "fresh layout follows the approved responsive sidebar width at " + width);
          JTree tree = (JTree) SimpleWorkflowTest.named(navigation,"wb.navigation.tree");
          tree.setSelectionPath(SimpleWorkflowTest.toolPath(navigation,"dashboard"));
          check(pages.getSelectedComponent()==dashboard && breadcrumb.getText().contains("점검 관리·증거"),
              "visible dashboard leaf opens its retained page and updates the breadcrumb at " + width);
          tree.setSelectionPath(SimpleWorkflowTest.toolPath(navigation,"payload"));
          check(pages.getSelectedComponent()==payload && breadcrumb.getText().contains("보안 분석·검증"),
              "a second real route changes the breadcrumb without replacing its page at " + width);
          check(((JTextField)traffic.getComponent(0)).getText().equals("KEEP traffic original bytes"),
              "route selection preserves authored page bytes at " + width);
          split.setDividerLocation(285);
          layout(navigation,width);
          check(Math.abs(split.getDividerLocation()-285)<=1,
              "explicit user divider width overrides the PDF default at " + width);
          JButton toggle = (JButton) SimpleWorkflowTest.named(navigation,"wb.navigation.toggle");
          toggle.doClick(0); layout(navigation,width);
          check(!split.getLeftComponent().isVisible() && "메뉴 보기".equals(toggle.getAccessibleContext().getAccessibleName()),
              "the retained header action restores a hidden sidebar at " + width);
          toggle.doClick(0); layout(navigation,width);
          check(split.getLeftComponent().isVisible() && Math.abs(split.getDividerLocation()-285)<=1,
              "hide and restore preserve the exact user width at " + width);
          JButton options = (JButton) SimpleWorkflowTest.named(navigation,"wb.navigation.settings");
          options.doClick(0);
          check(settings.get()==1 && help.getParent()!=null && logs.getParent()!=null,
              "settings help and log controllers remain connected after shell placement at " + width);
        }
      }
    });
    System.out.println("SimplePdfShellLayoutTest: " + checks + " checks passed (retained routes, zero HTTP)");
  }
}
