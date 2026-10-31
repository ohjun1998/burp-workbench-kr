package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;

/** Simulated host look-and-feel replacement preserves Simple work and core controls. */
public final class SimpleAppearanceTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    var errors = new AtomicInteger();
    var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> { errors.incrementAndGet(); error.printStackTrace(); });
    ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> def(m));
    var entry = new BurpExtender();
    entry.registerExtenderCallbacks((IBurpExtenderCallbacks) PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(() -> entry.initialize(SimpleWorkflowTest.previewApi())); edt(() -> {});
    var extension = (WorkbenchExtension) get(entry,"workbench");
    var root = (JPanel) get(extension,"root");
    var mode = (JButton) get(extension,"captureButton");
    var traffic = (JTable) get(extension,"logTable");
    var input = (JTextArea) get(extension,"codecIn");
    var evidence = (BountySupportPanel) get(extension,"bountySupport");
    try {
      edt(() -> {
        check(WorkbenchUi.font().canDisplayUpTo("요청·응답 목록 · 기능 자동 매핑 · 계정 권한 비교 · 요청 재현·증거") < 0, "bundled font displays the retained tool captions");
        Dimension stable = mode.getPreferredSize();
        for (String expected : new String[]{"OFF","SCOPE"}) {
          mode.setSize(stable);
          var picture = new BufferedImage(stable.width,stable.height,BufferedImage.TYPE_INT_RGB);
          var graphics = picture.createGraphics(); mode.paint(graphics); graphics.dispose();
          check(mode.getText().equals(expected) && picture.getRGB(3,3)==(expected.equals("SCOPE")?new Color(233,247,240):new Color(248,249,251)).getRGB(), "capture state and approved status chip agree: "+expected);
          check(mode.getPreferredSize().equals(stable), "capture state preserves button geometry: "+expected);
          mode.doClick(0);
        }
        input.setText("SIMPLE_UNSAVED_INPUT"); evidence.evidenceTitle.setText("저장 전 작성 중인 제목");
        traffic.setRowHeight(48); traffic.setFont(new Font(Font.SERIF,Font.PLAIN,18));
        mode.setUI(new BasicButtonUI());
        SwingUtilities.updateComponentTreeUI(root);
      });
      edt(() -> {}); edt(() -> {});
      edt(() -> {
        check(input.getText().equals("SIMPLE_UNSAVED_INPUT") && evidence.evidenceTitle.getText().equals("저장 전 작성 중인 제목"), "host theme replacement preserves unsaved input and evidence draft");
        check(traffic.getRowHeight()==35 && traffic.getFont().getSize()==12, "PDF table typography recovers after host theme replacement");
        check(mode.getText().equals("OFF"), "host theme replacement preserves capture OFF state");
        mode.setSize(mode.getPreferredSize());
        var picture=new BufferedImage(mode.getWidth(),mode.getHeight(),BufferedImage.TYPE_INT_RGB);
        var graphics=picture.createGraphics();mode.paint(graphics);graphics.dispose();
        check(picture.getRGB(3,3)==new Color(248,249,251).getRGB(), "capture status chip recovers after host button UI replacement");
        SimpleWorkflowTest.click(root,"wb.simple.tool.bounty");
        SimpleWorkflowTest.click(root,"wb.simple.tool.traffic");
        check(SimpleWorkflowTest.toolSelected(root,"traffic"), "folder navigation remains selected and usable after host theme replacement");
        check(input.getText().equals("SIMPLE_UNSAVED_INPUT"), "moving between retained tools preserves local conversion input");
        check(PayloadIntegrationTest.sent.get()==0, "appearance and capture-state changes send zero HTTP");
        check(errors.get()==0, "simulated host look-and-feel change emits no asynchronous UI errors");
      });
    } finally { SimpleWorkflowTest.unload(); Thread.setDefaultUncaughtExceptionHandler(previousHandler); }
    System.out.println("SimpleAppearanceTest: "+checks+" checks passed (production Swing; simulated host theme; zero HTTP)");
  }
}
