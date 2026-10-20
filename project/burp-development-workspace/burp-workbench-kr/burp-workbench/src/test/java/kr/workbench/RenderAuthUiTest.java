package kr.workbench;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import com.sun.net.httpserver.HttpServer;
import burp.api.montoya.MontoyaApi;

/** Production Swing, synthetic credentials, and a loopback canary for accidental resource loading. */
public final class RenderAuthUiTest extends WorkbenchTest {
  static JMenuItem item(JPopupMenu menu,String label){return Arrays.stream(menu.getComponents()).filter(c->c instanceof JMenuItem i&&label.equals(i.getText())).map(c->(JMenuItem)c).findFirst().orElseThrow();}
  static String content(JEditorPane pane){try{return pane.getDocument().getText(0,pane.getDocument().getLength());}catch(Exception ex){throw new RuntimeException(ex);}}
  public static void main(String[] args)throws Exception{
    AtomicInteger requests=new AtomicInteger();HttpServer canary=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);canary.createContext("/",x->{requests.incrementAndGet();x.sendResponseHeaders(200,-1);x.close();});canary.start();
    String url="http://127.0.0.1:"+canary.getAddress().getPort()+"/";
    AtomicReference<PacketInspector> editor=new AtomicReference<>();AtomicReference<AuthComparePanel> auth=new AtomicReference<>();
    try{
      var zipped=new java.io.ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(zipped)){gzip.write("x".repeat(300000).getBytes(StandardCharsets.UTF_8));}
      try{JsBodyDecoder.decode(zipped.toByteArray(),"gzip","application/json",128*1024);throw new AssertionError("unbounded evidence decoding");}catch(java.io.IOException expected){check(true,"evidence decompression has an independent 128 KiB budget");}
      edt(()->{
        String html="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<html><head><link rel='stylesheet' href='"+url+"'><style>@import url('"+url+"');</style><script>badScript()</script></head><body background='"+url+"'><h1>렌더 확인</h1><table><tr><th>계정</th><td>일반</td></tr></table><img src='"+url+"'><iframe src='"+url+"'></iframe><form action='"+url+"'><input src='"+url+"' type='image'></form><a href='"+url+"'>링크 내용</a><p style='background:url("+url+")' onclick='bad()'>본문</p></body></html>";
        byte[] wire=html.getBytes(StandardCharsets.UTF_8);var p=new PacketInspector("test.render",true);editor.set(p);p.setPacket(wire,null);p.tabs.get(3).doClick(0);
        check(p.mode()==PacketInspector.Mode.RENDER&&!p.text.isEditable()&&SwingUtilities.isDescendingFrom(p.rendered,p),"Render is a mounted read-only view");
        check(content(p.rendered).contains("렌더 확인")&&content(p.rendered).contains("계정")&&content(p.rendered).contains("본문"),"HTML headings table and Korean text render");
        String sanitized=PacketRender.sanitize(html.substring(html.indexOf("<html>")));
        check(!sanitized.contains(url)&&!sanitized.contains("badScript")&&!sanitized.contains("onclick")&&!sanitized.contains("@import")&&!sanitized.contains("<input"),"active HTML and URL attributes stripped before Swing parsing");
        String nested=PacketRender.sanitize("<div>".repeat(6000)+"deep"+"</div>".repeat(6000));check(nested.split("<div>",-1).length<=65&&nested.contains("deep"),"deep captured HTML is bounded before Swing layout");
        p.setSize(780,440);for(int i=0;i<5;i++)UiLayoutTest.layout(p);var image=new BufferedImage(780,440,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();p.paint(g);g.dispose();
        check(Arrays.equals(wire,p.editedBytes()),"Render never changes authored bytes");p.tabs.get(1).doClick(0);check(p.text.getText().equals(html),"Raw restores exact CRLF after Render");
        p.text.setText(html.replace("본문","수정 본문"));byte[] edited=p.editedBytes();p.tabs.get(3).doClick(0);check(content(p.rendered).contains("수정 본문")&&Arrays.equals(edited,p.editedBytes()),"Render reflects Raw edits while preserving transmission source");
        p.setPacket("HTTP/1.1 200 OK\nContent-Type: application/json\n\n{\"html\":\"<b>literal</b>\"}".getBytes(StandardCharsets.UTF_8),null);check(content(p.rendered).contains("<b>literal</b>"),"JSON remains escaped text rather than interpreted HTML");
        p.setPacket(new byte[100000],null);check(content(p.rendered).contains("64 KiB"),"Render declares truncated input");
        check(p.text.getFont().getFamily().equals(WorkbenchUi.font().getFamily())&&WorkbenchUi.textFont("ASCII").getFamily().equals(WorkbenchUi.textFont("한글").getFamily()),"packet and prose share requested font family");
        Set<String> fonts=Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());if(fonts.contains("GulimChe")||fonts.contains("굴림체")||fonts.contains("Gulim")||fonts.contains("굴림"))check(Set.of("GulimChe","굴림체","Gulim","굴림").contains(WorkbenchUi.font().getFamily()),"installed Gulim is selected");else check(WorkbenchUi.font().canDisplayUpTo("굴림 대체 한글")<0,"missing Gulim has a Korean-capable fallback");
        var api=proxy(MontoyaApi.class,(o,m,a)->def(m));var panel=new AuthComparePanel(api,List::of);auth.set(panel);
        panel.observe(AuthRecordTest.q("first-secret"),response(200,"{}"));panel.observe(AuthRecordTest.q("second-secret"),response(200,"{}"));
        var cs=new ArrayList<>(panel.credentials.values());panel.candidates.setRowSelectionInterval(0,0);JPopupMenu menu=(JPopupMenu)panel.candidates.getClientProperty("wb.auth.popup");
        check(Arrays.stream(menu.getComponents()).filter(c->c instanceof JMenuItem).map(c->((JMenuItem)c).getText()).toList().equals(List.of("관리자","일반","후보1","비로그인","해제","삭제"))&&Arrays.stream(menu.getComponents()).noneMatch(c->c instanceof JMenu),"candidate actions flat with only six requested items");
        item(menu,"관리자").doClick(0);item(menu,"일반").doClick(0);check(panel.slots[0]==cs.get(0)&&panel.slots[1]==cs.get(0),"flat assignments work directly");item(menu,"해제").doClick(0);check(panel.slots[0]==null&&panel.slots[1]==null,"clear detaches all uses of selected candidate");
        item(menu,"비로그인").doClick(0);check(panel.slots[3].headers().isEmpty()&&panel.label(3).equals("비로그인"),"anonymous uses independent fourth comparison position");item(menu,"비로그인").doClick(0);check(panel.slots[3]==null&&!panel.anonymousItem.isSelected(),"same anonymous menu clears its assignment without deleting another candidate");item(menu,"비로그인").doClick(0);item(menu,"후보1").doClick(0);check(panel.slots[2]==cs.get(0),"candidate one coexists with anonymous");
        panel.openCandidate(cs.get(0));check(java.util.stream.IntStream.range(0,panel.candidateDetails.headers.getRowCount()).anyMatch(row->panel.candidateDetails.headers.getValueAt(row,1).equals("first-secret")),"detail credential values unmasked");
        panel.ignoredFields.setText("$.ignored");
        var run=new AuthComparePanel.Run(1,AuthRecordTest.q("base"),response(200,"{\"owner\":1,\"missing\":null,\"ignored\":9}"),panel.context());run.note="완료";run.packets[2].response=response(200,"{\"owner\":2,\"new\":[],\"ignored\":10}");run.packets[2].note="판정 보류 · JSON 필드 차이 · 데이터/실행 결과 확인";panel.runs.add(run);panel.historyModel.addRow(new Object[]{1,run.original.url(),"","","","완료","GET",""});panel.show(run);
        check(content(panel.evidence.summary).contains("판단 이유")&&content(panel.evidence.summary).contains("다음 확인")&&panel.evidence.tabs.getTabCount()==3&&panel.evidence.tabs.getTitleAt(2).equals("비교 전 설정"),"evidence separates summary, fields and stored comparison readiness");
        check(content(panel.expandedEvidence.summary).equals(content(panel.evidence.summary)),"expanded evidence preserves the same stored verdict");
        String fields=content(panel.evidence.differences);check(fields.contains("JSON 필드")&&fields.contains("원본 값")&&fields.contains("계정 응답 값")&&fields.contains("[필드 없음]")&&fields.contains("[]")&&!fields.contains("$.ignored"),"field comparison shows original compared missing and empty values");
        panel.candidates.getRowSorter().toggleSortOrder(0);panel.candidates.getRowSorter().toggleSortOrder(0);panel.candidates.setRowSelectionInterval(0,0);panel.deleteSelectedCandidate();check(!panel.credentials.containsKey(cs.get(1).key())&&panel.credentials.containsKey(cs.get(0).key()),"sorted candidate deletion targets selected model row");
        panel.candidates.setRowSelectionInterval(0,0);panel.active.set(1);try{panel.deleteSelectedCandidate();throw new AssertionError("busy deletion allowed");}catch(IllegalArgumentException expected){check(panel.credentials.size()==1,"in-flight comparison blocks candidate deletion");}panel.active.set(0);
        panel.deleteSelectedCandidate();check(panel.slots[2]==null&&panel.runs.size()==1&&run.context.profiles()[2]!=null,"candidate deletion detaches current slot and preserves historical snapshot");
        var second=new AuthComparePanel.Run(2,run.original,run.response,run.context);second.note="완료";panel.runs.add(second);panel.historyModel.addRow(new Object[]{2,run.original.url(),"","","","완료","GET",""});panel.history.getRowSorter().toggleSortOrder(0);panel.history.getRowSorter().toggleSortOrder(0);panel.history.setRowSelectionInterval(0,0);panel.detailRun=second;panel.deleteSelectedHistory();check(panel.runs.equals(List.of(run))&&panel.detailRun==null&&panel.historyModel.getRowCount()==1,"sorted history deletion closes deleted detail and retains other run");panel.history.clearSelection();panel.deleteSelectedHistory();check(panel.runs.size()==1,"empty selection deletion is a no-op");
      });
      check(requests.get()==0,"painting Render performs zero loopback resource requests");
    }finally{edt(()->{if(editor.get()!=null)editor.get().close();if(auth.get()!=null)auth.get().close();});canary.stop(0);}
    System.out.println("RenderAuthUiTest: "+checks+" checks passed");
  }
}
