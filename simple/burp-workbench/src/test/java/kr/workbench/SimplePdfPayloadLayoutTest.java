package kr.workbench;

import burp.IBurpExtenderCallbacks;
import burp.IHttpService;
import java.awt.Component;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import kr.payloadrunner.Engine;
import kr.payloadrunner.PayloadRunnerPanel;

/** PDF result controls must operate on retained rows after real filtering and numeric sorting. */
public final class SimplePdfPayloadLayoutTest extends WorkbenchTest {
  private static Component named(Component root,String name) {
    Component found=SimpleWorkflowTest.named(root,name);
    if(found==null)throw new AssertionError("Missing PDF payload control: "+name);
    return found;
  }
  private static AbstractButton button(Component root,String name) {
    return (AbstractButton)named(root,name);
  }
  private static Rectangle bounds(Component component,Component root) {
    return SwingUtilities.convertRectangle(component.getParent(),component.getBounds(),root);
  }
  private static void fixture(Object runner,List<Engine.Row> rows,IHttpService service) {
    try {
      Class<?> entry=Class.forName("kr.payloadrunner.PayloadRunnerPanel$Entry");
      var constructor=entry.getDeclaredConstructor(Engine.Row.class,IHttpService.class);
      constructor.setAccessible(true);
      @SuppressWarnings("unchecked") List<Object> entries=(List<Object>)get(runner,"entries");
      DefaultTableModel model=(DefaultTableModel)get(runner,"model");
      for(Engine.Row row:rows) {
        entries.add(constructor.newInstance(row,service));
        model.addRow(new Object[]{row.index(),row.payload(),row.response().status(),row.response().wireLength(),row.millis(),row.verdict(),row.evidence()});
      }
    } catch(Exception error) { throw new IllegalStateException(error); }
  }
  private static Engine.Row row(int index,String payload,long time,String verdict,String evidence) {
    byte[] request=("GET /search?q="+index+" HTTP/1.1\r\nHost: pdf-payload.test\r\nX-Original: "+payload+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
    String body="ORIGINAL_BODY_"+index;
    byte[] response=("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n"+body).getBytes(StandardCharsets.UTF_8);
    return new Engine.Row(index,payload,time,new Engine.Response(200,response.length,"text/plain",body,false,response,true),verdict,evidence,request);
  }
  private static List<Engine.Row> blockedBaselines()throws Exception {
    List<Engine.Row> rows=new ArrayList<>();
    for(int status:new int[]{401,403,429,503}) {
      var config=new Engine.Config(Engine.Profile.CUSTOM,Engine.Encoding.URL,
          "GET /baseline?q=§PAYLOAD§ HTTP/1.1\r\nHost: blocked-payload.test\r\n\r\n",
          "BASELINE_"+status,List.of("PROBE_MUST_NOT_BE_SENT"),0,1,"");
      AtomicInteger calls=new AtomicInteger();
      List<Engine.Row> run=new ArrayList<>();
      var summary=Engine.run(config,request->{calls.incrementAndGet();return
          ("HTTP/1.1 "+status+" Blocked\r\nContent-Type: text/plain\r\n\r\nBASELINE_BLOCKED_"+status).getBytes(StandardCharsets.UTF_8);
      },new java.util.concurrent.atomic.AtomicBoolean(),run::add);
      check(calls.get()==1&&summary.requests()==1&&run.size()==1&&run.get(0).index()==0
              &&run.get(0).response().status()==status,
          "actual engine records the blocked baseline without an attack request for HTTP "+status);
      rows.addAll(run);
    }
    return rows;
  }
  public static void main(String[] args)throws Exception {
    int sent=PayloadIntegrationTest.sent.get();
    AtomicInteger repeaterCalls=new AtomicInteger();
    AtomicReference<byte[]> prepared=new AtomicReference<>();
    AtomicReference<List<Engine.Row>> evidence=new AtomicReference<>();
    var fallback=(IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class);
    IBurpExtenderCallbacks callbacks=proxy(IBurpExtenderCallbacks.class,(p,m,a)->{
      if(m.getName().equals("sendToRepeater")){repeaterCalls.incrementAndGet();prepared.set(((byte[])a[3]).clone());return null;}
      return m.invoke(fallback,a);
    });
    var runner=new PayloadRunnerPanel();
    JPanel root=(JPanel)runner.initializeEmbedded(callbacks,()->{});
    List<Engine.Row> originals=List.of(
        row(0,"original",84,"기준 응답","원래 값으로 요청한 비교 기준"),
        row(1,"baseline",9,"반사 관찰","반사 관찰 · 기준이라는 문자열만으로 대조가 되지 않음"),
        row(2,"control",105,"대조 응답","서로 다른 출력 대조 · 실제 기록한 대조"),
        row(3,"attack",12,"반사 관찰","문자열 반사 탐색 · 브라우저 실행 미확인"));
    List<Engine.Row> supplementary=new ArrayList<>(blockedBaselines());
    supplementary.add(row(41,"PR_CTRL_MATCH",32,"출력 대조 일치","PR_CMD/PR_CTRL 서로 다른 조합 출력 확인 · 동일 입력문 반사는 제외"));
    supplementary.add(row(42,"PR_CTRL_INCOMPLETE",33,"출력 대조 미완료","PR_CMD/PR_CTRL 서로 다른 조합 출력 확인 · 동일 입력문 반사는 제외"));
    IHttpService service=proxy(IHttpService.class,(p,m,a)->switch(m.getName()){
      case "getHost"->"pdf-payload.test";case "getPort"->443;case "getProtocol"->"https";default->def(m);
    });
    try {
      edt(()->{
        runner.setEvidenceSink((actualService,rows)->{
          check(actualService==service,"filtered evidence keeps the exact selected original service");
          evidence.set(new ArrayList<>(rows));
        });
        fixture(runner,originals,service);
        JTable table=(JTable)named(root,"wb.payload.results.table");
        JTabbedPane phases=(JTabbedPane)named(root,"wb.payload.steps");
        JTextArea request=(JTextArea)named(root,"wb.simple.payload.request");
        request.setText("GET /keep?q=§PAYLOAD§ HTTP/1.1\r\nHost: pdf-payload.test\r\nX-Memo: KEEP\r\n\r\n");
        String authored=request.getText();
        check(table.getColumnCount()==6&&table.convertColumnIndexToView(4)>=0
                &&table.getColumnModel().getColumn(table.convertColumnIndexToView(4)).getHeaderValue().toString().contains("응답 시간"),
            "PDF result table shows all six columns including the recorded response duration");
        check(button(root,"wb.payload.results.filter.all").getText().equals("전체 4")
                &&button(root,"wb.payload.results.filter.observed").getText().equals("관찰 2")
                &&button(root,"wb.payload.results.filter.control").getText().equals("기준·대조 2"),
            "result filter counts derive from recorded engine stages rather than arbitrary payload labels");
        phases.setSelectedIndex(1);
        for(int width:new int[]{800,1024,1400}) {
          root.setSize(width,width==800?630:900);
          for(int pass=0;pass<8;pass++)UiLayoutTest.layout(root);
          var primary=(JComponent)named(root,"wb.simple.payload.evidence");
          check(ApprovedWorkflowTestSupport.visible(primary,root)
                  &&Boolean.TRUE.equals(primary.getClientProperty("wb.primary"))
                  &&bounds(primary,root).y<bounds(table,root).y,
              "result evidence is the direct primary action above the result table at "+width);
          check(!ApprovedWorkflowTestSupport.visible(named(root,"wb.simple.payload.start"),root)
                  &&ApprovedWorkflowTestSupport.available(named(root,"wb.simple.payload.start"),root)
                  &&ApprovedWorkflowTestSupport.visible(named(root,"wb.simple.payload.stop"),root),
              "results retain explicit reinspection through the management menu and direct stop at "+width);
        }
        var readers=(JSplitPane)named(root,"wb.payload.results.messages");
        check(readers.getOrientation()==JSplitPane.HORIZONTAL_SPLIT
                &&!button(root,"wb.payload.results.reason.toggle").isSelected(),
            "PDF readers place actual request and response side by side with full reasoning folded");
        table.getRowSorter().toggleSortOrder(4);
        check(table.convertRowIndexToModel(0)==1&&table.convertRowIndexToModel(1)==3,
            "response durations sort numerically rather than lexicographically");
        table.setRowSelectionInterval(0,0);
        button(root,"wb.payload.results.filter.observed").doClick(0);
        check(table.getRowCount()==2&&table.convertRowIndexToModel(table.getSelectedRow())==1,
            "observed filter preserves the selected exact model row after numeric sorting");
        button(root,"wb.payload.repeater").doClick(0);
        check(repeaterCalls.get()==1&&Arrays.equals(prepared.get(),originals.get(1).request()),
            "filtered Repeater preparation uses the exact selected request bytes once");
        runner.captureEvidence();
        check(evidence.get()!=null&&evidence.get().size()==4
                &&java.util.stream.IntStream.range(0,4).allMatch(i->evidence.get().get(i)==originals.get(i)),
            "filtering the view preserves every exact baseline, attack and control row in evidence order");
        JLabel summary=(JLabel)named(root,"wb.payload.results.selection");
        check(summary.getText().contains("반사 관찰")&&summary.getText().contains("브라우저 실행 미확인"),
            "selected result summary distinguishes reflection observation from browser execution");
        button(root,"wb.payload.results.filter.control").doClick(0);
        check(table.getRowCount()==2&&table.getSelectedRow()==-1
                &&!button(root,"wb.payload.repeater").isEnabled()
                &&!button(root,"wb.simple.payload.evidence").isEnabled()
                &&summary.getText().equals("결과 행을 선택하세요."),
            "a filter hiding the original clears stale readers and disables selection-dependent handoffs");
        button(root,"wb.payload.results.filter.all").doClick(0);
        check(table.getRowCount()==4&&((DefaultTableModel)getUnchecked(runner,"model")).getRowCount()==4,
            "restoring all results keeps every original model row intact");
        fixture(runner,supplementary,service);
        check(button(root,"wb.payload.results.filter.all").getText().equals("전체 10")
                &&button(root,"wb.payload.results.filter.observed").getText().equals("관찰 2")
                &&button(root,"wb.payload.results.filter.control").getText().equals("기준·대조 8"),
            "blocked baselines and rewritten adaptive output-control notes remain controls rather than observations");
        button(root,"wb.payload.results.filter.observed").doClick(0);
        check(table.getRowCount()==2&&java.util.stream.IntStream.range(4,10).allMatch(index->table.convertRowIndexToView(index)==-1),
            "observation filter excludes all four halted baselines and both explicit adaptive control outcomes");
        button(root,"wb.payload.results.filter.control").doClick(0);
        check(table.getRowCount()==8,"control filter includes halted baselines and complete or incomplete output controls");
        for(int index=0;index<supplementary.size();index++) {
          int view=table.convertRowIndexToView(4+index);table.setRowSelectionInterval(view,view);
          button(root,"wb.payload.repeater").doClick(0);
          check(Arrays.equals(prepared.get(),supplementary.get(index).request()),
              "filtered baseline/control handoff retains exact original request bytes for supplementary row "+index);
        }
        runner.captureEvidence();
        List<Engine.Row> allOriginals=new ArrayList<>(originals);allOriginals.addAll(supplementary);
        check(evidence.get().size()==10&&java.util.stream.IntStream.range(0,10).allMatch(i->evidence.get().get(i)==allOriginals.get(i)),
            "baseline/control filtering preserves the entire original evidence sequence including blocked and incomplete controls");
        button(root,"wb.payload.results.filter.all").doClick(0);
        phases.setSelectedIndex(0);
        check(request.getText().equals(authored)
                &&ApprovedWorkflowTestSupport.visible(named(root,"wb.simple.payload.start"),root)
                &&!ApprovedWorkflowTestSupport.visible(named(root,"wb.simple.payload.evidence"),root),
            "returning to preparation restores the original start action and preserves authored request bytes");
      });
      check(PayloadIntegrationTest.sent.get()==sent,"navigation, filters, sorting, evidence and Repeater preparation send zero target HTTP");
    } finally { runner.extensionUnloaded();edt(()->{}); }
    System.out.println("SimplePdfPayloadLayoutTest: "+checks+" checks passed");
  }
  private static Object getUnchecked(Object owner,String field){try{return get(owner,field);}catch(Exception error){throw new IllegalStateException(error);}}
}
