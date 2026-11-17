package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.Color;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;

/** Embedded styling settles while host look-and-feel replacements still restore real controls. */
public final class SimpleEmbeddedThemeTest extends WorkbenchTest {
  private static void flush() throws Exception {
    for (int pass = 0; pass < 6; pass++) edt(() -> {});
  }

  private static void mapperStyle(Component component){
    try{var method=Class.forName("kr.featuremapper.MapperUi").getDeclaredMethod("style",Component.class);method.setAccessible(true);method.invoke(null,component);}
    catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
  }

  private static void await(BooleanSupplier condition, String reason) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
    while (System.nanoTime() < deadline) {
      boolean[] complete = {false}; edt(() -> complete[0] = condition.getAsBoolean());
      if (complete[0]) return;
      Thread.sleep(10);
    }
    throw new AssertionError("Theme did not settle: " + reason);
  }

  private static void embeddedBoundary() throws Exception {
    JPanel[] outer = {null}, domain = {null};
    JButton[] local = {null}, global = {null};
    AtomicInteger delegated = new AtomicInteger(), applied = new AtomicInteger();
    try {
      edt(() -> {
        outer[0] = new JPanel(); domain[0] = new JPanel();
        local[0] = new JButton("Independent embedded control");
        global[0] = new JButton("Host control");
        domain[0].add(local[0]); outer[0].add(domain[0]); outer[0].add(global[0]);
        domain[0].putClientProperty("wb.embedded.style", (Runnable) () -> {
          delegated.incrementAndGet();
          // An embedded owner may replace its own delegate while applying a theme.
          local[0].setUI(new BasicButtonUI());
          local[0].setForeground(Color.BLUE);
        });
        WorkbenchSkin.install(outer[0], applied::incrementAndGet);
      });
      flush();
      check(applied.get() == 1 && delegated.get() == 1,
          "one outer installation delegates embedded styling once without a reciprocal style loop");
      edt(() -> check(Color.BLUE.equals(local[0].getForeground()),
          "embedded owner controls its own themed foreground"));
      int settled = applied.get(); flush();
      check(applied.get() == settled && delegated.get() == 1,
          "queued EDT barriers leave outer and embedded style callbacks settled");
      edt(() -> local[0].setUI(new BasicButtonUI()));
      flush();
      check(applied.get() == settled && delegated.get() == 1,
          "embedded delegate replacement does not trigger the outer component watcher");
      edt(() -> global[0].setUI(new BasicButtonUI()));
      await(() -> global[0].getUI().getClass().getName().contains("WorkbenchSkin$ButtonUi"),
          "outside host control restores its Workbench delegate");
      flush();
      check(applied.get() == settled + 1 && delegated.get() == 2,
          "outside host look-and-feel replacement still restores the outer and embedded themes once");
      int afterHost = applied.get(); flush();
      check(applied.get() == afterHost && delegated.get() == 2,
          "host restoration reaches a stable styling state");
    } finally {
      if (outer[0] != null) edt(() -> outer[0].putClientProperty("wb.skin.closed", true));
      flush();
    }
  }

  public static void main(String[] args) throws Exception {
    embeddedBoundary();
    BountyFeaturesTest.factory();
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi())); flush();
    var extension = (WorkbenchExtension) get(entry, "workbench");
    var root = (JPanel) get(extension, "root");
    var mapper = (kr.featuremapper.FeatureMapperWorkspace) get(extension, "featureMapper");
    var component = mapper.component();
    var button = (JButton) SimpleWorkflowTest.named(component, "fm.assessment.link");
    if (button == null) throw new AssertionError("Missing real Mapper feature handoff action");
    var table = (JTable) get(mapper, "table");
    var originalModel = table.getModel();
    var originalParent = button.getParent();
    int actionCount = button.getActionListeners().length;
    int sent = PayloadIntegrationTest.sent.get();
    AtomicInteger delegateChanges = new AtomicInteger();
    Object[] initial = {null};
    try {
      edt(() -> {
        initial[0] = button.getUI();
        button.addPropertyChangeListener("UI", event -> delegateChanges.incrementAndGet());
        for (int pass = 0; pass < 4; pass++) WorkbenchUi.style(root);
      });
      flush();
      edt(() -> check(button.getUI() == initial[0] && delegateChanges.get() == 0,
          "repeated outer style reuses the exact embedded Mapper button delegate"));
      int settled = delegateChanges.get(); flush();
      check(delegateChanges.get() == settled,
          "real Mapper button UI events stop after queued outer theme work settles");
      edt(() -> button.setUI(new BasicButtonUI()));
      await(() -> button.getUI().getClass() == initial[0].getClass(),
          "Mapper-owned watcher restores a host-replaced button delegate");
      flush();
      edt(() -> {
        check(button.getUI() != initial[0] && button.getUI().getClass() == initial[0].getClass(),
            "host replacement restores the real Mapper UI class without retaining the foreign delegate");
        check(button.getActionListeners().length == actionCount && actionCount > 0
            && button.getParent() == originalParent && "fm.assessment.link".equals(button.getName()),
            "theme restoration retains the exact production button, callbacks and contextual parent");
        check(table.getModel() == originalModel && getUnchecked(extension, "featureMapper") == mapper,
            "theme restoration preserves the Mapper engine and table model");
      });
      int restored = delegateChanges.get(); flush();
      check(delegateChanges.get() == restored,
          "restored embedded UI does not oscillate between host and Mapper delegates");
      check(PayloadIntegrationTest.sent.get() == sent,
          "theme restoration and delegate checks send zero target HTTP requests");
      edt(()->{
        var runs=(JComboBox<?>)getUnchecked(mapper,"runs");
        var runsUi=runs.getUI();var runsRenderer=runs.getRenderer();var runsModel=runs.getModel();Object run=runs.getSelectedItem();
        var editable=new JComboBox<>(new String[]{"원본 선택"});editable.setEditable(true);
        var customRenderer=new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){super.getListCellRendererComponent(list,value,index,selected,focus);setText("계정 · "+value);return this;}};
        editable.setRenderer(customRenderer);mapperStyle(editable);
        var editor=(JTextField)editable.getEditor().getEditorComponent();var document=editor.getDocument();editor.setText("작성 중 초안");editor.setCaretPosition(3);
        var scrollbar=new JScrollBar(JScrollBar.HORIZONTAL,50,10,0,100);mapperStyle(scrollbar);var scrollbarUi=scrollbar.getUI();
        var moon=(JButton)SimpleWorkflowTest.named(root,"wb.traffic.header.tools");boolean initialTheme=WorkbenchUi.dark();
        for(boolean dark:new boolean[]{!initialTheme,initialTheme}){
          moon.doClick(0);mapperStyle(editable);mapperStyle(scrollbar);
          check(runs.getUI()==runsUi&&runs.getRenderer()==runsRenderer&&runs.getModel()==runsModel&&java.util.Objects.equals(runs.getSelectedItem(),run),
              "a manual theme toggle retains the real Mapper run filter UI, renderer, model and selection in "+dark);
          for(JComboBox<?> combo:new JComboBox<?>[]{runs,editable}){
            combo.setSize(180,32);combo.doLayout();var image=new BufferedImage(180,32,BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();combo.paint(graphics);graphics.dispose();
            check(image.getRGB(75,5)==WorkbenchUi.surface().getRGB(),"the actual Mapper combo surface follows the chosen palette in "+dark);
            var popup=(javax.swing.plaf.basic.BasicComboPopup)combo.getUI().getAccessibleChild(combo,0);var list=popup.getList();
            Component plain=list.getCellRenderer().getListCellRendererComponent(list,combo.getItemAt(0),0,false,false);
            check(plain.getBackground().equals(WorkbenchUi.surface())&&plain.getForeground().equals(WorkbenchUi.ink()),
                "Mapper popup rows follow the local normal palette in "+dark);
            Component selected=list.getCellRenderer().getListCellRendererComponent(list,combo.getItemAt(0),0,true,false);
            check(selected.getBackground().equals(WorkbenchUi.selected())&&selected.getForeground().equals(WorkbenchUi.ink()),
                "Mapper popup rows follow the local selected palette in "+dark);
          }
          check(editable.getRenderer()==customRenderer&&editable.getEditor().getEditorComponent()==editor&&editor.getDocument()==document
                  &&editor.getText().equals("작성 중 초안")&&editor.getCaretPosition()==3&&editor.getBackground().equals(WorkbenchUi.surface())
                  &&editor.getForeground().equals(WorkbenchUi.ink()),
              "Mapper local styling retains custom renderers and an unfinished editable choice with its caret in "+dark);
          scrollbar.setSize(180,10);scrollbar.doLayout();var track=new BufferedImage(180,10,BufferedImage.TYPE_INT_RGB);
          var graphics=track.createGraphics();scrollbar.paint(graphics);graphics.dispose();
          check(scrollbar.getUI()==scrollbarUi&&track.getRGB(3,5)==WorkbenchUi.background().getRGB(),
              "the retained Mapper scrollbar paints its new track palette in "+dark);
        }
        check(PayloadIntegrationTest.sent.get()==sent,"manual themes and Mapper display updates send zero target HTTP");
      });
    } finally { SimpleWorkflowTest.unload(); }
    System.out.println("SimpleEmbeddedThemeTest: " + checks + " checks passed (styling settles, host restoration retained)");
  }

  private static Object getUnchecked(Object owner, String field) {
    return SimpleWorkflowTest.unchecked(owner, field);
  }
}
