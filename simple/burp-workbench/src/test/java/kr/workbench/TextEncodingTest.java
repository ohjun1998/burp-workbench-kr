package kr.workbench;
import java.nio.charset.*;
import javax.swing.*;
public final class TextEncodingTest extends WorkbenchTest {
 public static void main(String[] args)throws Exception {
  String korean="한글 요청 응답 환경 탐색";String json="{\"content\":\""+korean+"\"}";byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
  check(HttpText.decode(bytes,"application/json").equals(json),"UTF-8 bytes decoded without host string");
  check(HttpText.decode(korean.getBytes(Charset.forName("EUC-KR")),"text/plain; charset=EUC-KR").equals(korean),"declared EUC-KR");
  check(HttpText.decode(korean.getBytes(Charset.forName("MS949")),"text/plain; charset=windows-949").equals(korean),"declared CP949");
  check(HttpText.decode(bytes,"text/plain; charset=unsupported").equals(json),"unknown charset falls back to valid UTF-8");
  String garbled=new String(json.getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1);check(HttpText.display(garbled).equals(json),"Latin-1 mojibake display recovery");
  check(HttpText.display("정상 한글 + "+garbled).equals("정상 한글 + "+json),"mixed normal and garbled Korean");check(HttpText.display("café © 2026").equals("café © 2026"),"normal Latin text preserved");
  String packet="HTTP/1.1 200\r\nContent-Type: application/json\r\n\r\n"+garbled;check(PacketFormat.pretty(packet).contains(korean),"structured packet displays Korean");
  var host=response(200,json);var misleading=proxy(burp.api.montoya.http.message.responses.HttpResponse.class,(p,m,a)->m.getName().equals("bodyToString")?garbled:m.invoke(host,a));check(JsAnalysisPanel.readBody(misleading).equals(json),"HTTP body decoded from raw bytes even if host string is garbled");
  var parsed=kr.payloadrunner.Engine.parse(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=EUC-KR\r\n\r\n").getBytes(StandardCharsets.US_ASCII));check(parsed.status()==200,"payload engine retains status");
  edt(()->{check(WorkbenchUi.font().canDisplayUpTo(korean)<0,"bundled fallback can draw Korean");PacketView view=new PacketView();view.setPacket(packet);check(view.text.getText().contains(korean),"packet viewer repairs display");view.formatted.doClick();check(view.text.getText().equals(packet),"original mojibake packet remains intact");view.formatted.doClick();check(view.text.getFont().canDisplayUpTo(korean)<0,"packet font draws restored text");});
  System.out.println("TextEncodingTest: "+checks+" checks passed");
 }
}
