package kr.workbench;

import burp.IBurpExtenderCallbacks;
import burp.IHttpService;
import burp.api.montoya.MontoyaApi;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import kr.payloadrunner.*;

/** Re-displays recorded HTTP results in production Swing panels using the test API adapter. */
public class AssessmentUiEvidence extends WorkbenchTest {
  static void render(Component component,Path path,String title) throws Exception {
    JPanel shell=new JPanel(new BorderLayout(0,12));shell.setBackground(Color.WHITE);shell.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
    JLabel caption=new JLabel(title+" · 실제 HTTP 결과 재표시 / Burp API 어댑터 / 수동 GUI 검사 아님");caption.setFont(WorkbenchUi.font());shell.add(caption,BorderLayout.NORTH);shell.add(component);shell.setSize(1440,920);
    headers(shell);for(int i=0;i<6;i++)UiLayoutTest.layout(shell);
    var image=new BufferedImage(1440,920,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();shell.printAll(graphics);graphics.dispose();ImageIO.write(image,"png",path.toFile());
  }
  static void headers(Component c){if(c instanceof JTable t){var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,t);if(scroll!=null)scroll.setColumnHeaderView(t.getTableHeader());}if(c instanceof Container root)for(Component child:root.getComponents())headers(child);}
  public static void main(String[] args) throws Exception {
    Path data=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
    var xss=(List<?>)MinerJson.parse(Files.readString(data.resolve("after.json")),16*1024*1024,200000);
    var oops=(List<?>)MinerJson.parse(Files.readString(data.resolve("oops-after.json")),16*1024*1024,200000);
    edt(()->{try {
      var target=(Map<?,?>)xss.stream().filter(x->((Map<?,?>)x).get("id").equals("BenchmarkTest00375")).findFirst().orElseThrow();
      var runner=new PayloadRunnerPanel();runner.initializeEmbedded((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class),()->{});
      var table=(JTable)get(runner,"table");var model=(DefaultTableModel)get(runner,"model");var entries=(List<Object>)get(runner,"entries");
      var constructor=Class.forName("kr.payloadrunner.PayloadRunnerPanel$Entry").getDeclaredConstructors()[0];constructor.setAccessible(true);
      var first=(Map<?,?>)((List<?>)target.get("rows")).get(0);String baselineRequest=first.get("request").toString();
      ((JTextArea)get(runner,"request")).setText(baselineRequest.replace("=baseline HTTP/","="+Engine.MARK+" HTTP/"));((JTextField)get(runner,"base")).setText("baseline");
      var portMatcher=java.util.regex.Pattern.compile("Host: 127\\.0\\.0\\.1:(\\d+)").matcher(baselineRequest);if(portMatcher.find())((JSpinner)get(runner,"port")).setValue(Integer.parseInt(portMatcher.group(1)));
      int selected=-1,index=0;
      for(Object item:(List<?>)target.get("rows")) {var row=(Map<?,?>)item;int status=((Number)row.get("status")).intValue();String raw="HTTP/1.1 "+status+" Result\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"+row.get("body");var response=Engine.parse(raw.getBytes(StandardCharsets.UTF_8));
        var record=new Engine.Row(index,row.get("payload").toString(),-1,response,row.get("verdict").toString(),row.get("evidence").toString(),row.get("request").toString().getBytes(StandardCharsets.ISO_8859_1));entries.add(constructor.newInstance(record,PayloadIntegrationTest.host(IHttpService.class)));
        model.addRow(new Object[]{index,record.payload(),status,response.wireLength(),"미기록",record.verdict(),record.evidence()});if(selected<0&&record.verdict().equals("XSS 반사 후보"))selected=index;index++;
      }
      ((JComboBox<?>)get(runner,"mode")).setSelectedIndex(1);((JSpinner)get(runner,"budget")).setValue(50);table.setRowSelectionInterval(selected,selected);
      render(runner.getUiComponent(),out.resolve("payload-current.png"),"입력값 보안 검사 · BenchmarkTest00375");runner.extensionUnloaded();
      var panel=new ResponseReconPanel((MontoyaApi)PayloadIntegrationTest.host(MontoyaApi.class));
      for(Object item:oops) {var test=(Map<?,?>)item;for(Object finding:(List<?>)test.get("rules")){var f=(Map<?,?>)finding;String path=test.get("id").equals("OOPS-STREAM-SECRET")?"/api/live/stream":"/api/user/export";panel.findings.addRow(new Object[]{f.get("rule"),"http://127.0.0.1:24602"+path,f.get("location"),f.get("evidence"),1});}}
      panel.status.setText("OopsSec 실제 응답 4개 · 관련 후보 2개 · 정상/입력 오류 2개는 관련 경고 없음");
      StringBuilder detail=new StringBuilder();for(int i=0;i<panel.findings.getRowCount();i++)detail.append(panel.findings.getValueAt(i,0)).append("\n근거 위치: ").append(panel.findings.getValueAt(i,2)).append("\n").append(panel.findings.getValueAt(i,3)).append("\n\n");panel.findingDetail.setText(detail.toString());
      render(panel,out.resolve("response-current.png"),"응답 보안 분석 · OopsSec");panel.close();
    } catch(Exception e){throw new RuntimeException(e);}});
    System.out.println("Rendered current recorded HTTP results in two production panels");
  }
}
