package kr.workbench;

import java.awt.*;
import java.util.*;
import javax.swing.*;

/** Visual explanation of stored verdicts; never reclassifies or retransmits a response. */
final class AuthEvidencePanel extends JPanel {
  final JTabbedPane tabs=new JTabbedPane();
  final JEditorPane summary=PacketRender.pane(),differences=PacketRender.pane();
  AuthComparePanel.Run run;
  AuthEvidencePanel(){super(new BorderLayout());setName("wb.auth.evidence");summary.setName("wb.auth.evidence.summary");differences.setName("wb.auth.evidence.fields");tabs.addTab("판정 요약",new JScrollPane(summary));tabs.addTab("JSON 필드 차이",new JScrollPane(differences));add(tabs);showRun(null);}
  static String e(String value){return PacketRender.escape(value);}
  static String color(Color c){return String.format("#%02x%02x%02x",c.getRed(),c.getGreen(),c.getBlue());}
  String page(String content){return "<html><head><style>body{font-family:'"+WorkbenchUi.font().getFamily()+"';font-size:13pt;color:"+color(WorkbenchUi.ink())+";background-color:"+color(WorkbenchUi.surface())+";margin:12px}h2{font-size:17pt;margin-top:4px}h3{font-size:14pt;margin-bottom:5px}p{margin-top:5px;margin-bottom:9px}td,th{padding:7px;border-bottom:1px solid "+color(WorkbenchUi.line())+"}th{background-color:"+color(WorkbenchUi.raised())+";text-align:left}pre{font-family:'"+WorkbenchUi.font().getFamily()+"'}</style></head><body>"+content+"</body></html>";}
  void showRun(AuthComparePanel.Run next){
    boolean changed=run!=next;run=next;
    if(run==null){summary.setText(page("<p>비교 기록을 선택하면 종합 결과와 판단 이유가 표시됩니다.</p>"));differences.setText(page("<p>비교 기록을 선택하세요.</p>"));return;}
    var result=AuthRunSummary.of(run);
    StringBuilder out=new StringBuilder("<h2 style='color:").append(color(WorkbenchUi.toneInk(result.verdict().tone))).append("'>").append(e(result.label())).append("</h2><p><b>판단 이유</b><br>").append(e(run.baselineState==AuthComparePanel.Run.Baseline.INVALID?run.baselineNote:explanation(result))).append("</p>");
    out.append("<p><b>다음 확인</b><br>").append(e(nextStep(result))).append("</p><table width='100%' cellspacing='0'><tr><th>계정</th><th>응답</th><th>비교에서의 의미</th></tr>");
    for(int i=0;i<run.packets.length;i++){
      var packet=run.packets[i];boolean reference=AuthRunSummary.reference(packet);
      String meaning=run.context.profiles()[i]==null?"비교 대상에 지정하지 않음":reference?"기준 계정 · 원본과 같은 인증이므로 종합 판정에서 제외":packet.note;
      out.append("<tr><td><b>").append(e(run.context.labels()[i])).append("</b></td><td>").append(e(AuthRunSummary.responseLabel(run,i))).append("</td><td>").append(e(observation(meaning)).replace(" · ","<br>")).append("</td></tr>");
    }
    out.append("</table><p><b>기준 검증</b><br>").append(e(run.baselineNote)).append("</p>");
    for(int i=0;i<run.packets.length;i++){var evidence=run.packets[i].effect;if(evidence==null)continue;out.append("<h3>").append(e(run.context.labels()[i])).append(" · 실제 처리 결과</h3><p>").append(e(evidence.note())).append("</p><table width='100%'><tr><th>검증</th><th>변경 전</th><th>변경 후</th></tr><tr><td>").append(e(evidence.config().jsonPath().isBlank()?"본문 SHA-256":evidence.config().jsonPath())).append("</td><td>").append(e(effectValue(evidence.before()))).append("</td><td>").append(e(effectValue(evidence.after()))).append("</td></tr></table>");}
    out.append("<p>기준 응답: ").append(run.baseline()==null?"없음":"HTTP "+run.baseline().statusCode()).append(" · 계정별 내용은 위 종합 결과의 근거입니다.</p>");
    summary.setText(page(out.toString()));
    differences.setText(page(fieldTable(run)));if(changed){summary.setCaretPosition(0);differences.setCaretPosition(0);tabs.setSelectedIndex(0);}
  }
  static String observation(String note){if(note.startsWith("기준 계정")||note.equals("비교 대상에 지정하지 않음"))return note;int at=note.indexOf(" · ");return at<0?note:note.substring(at+3);}
  static String explanation(AuthRunSummary.Result result){
    if(result.label().equals("업무 규칙 위반 후보"))return result.reason();
    if(result.label().equals("정책 일치"))return "다른 계정의 허용·차단 응답이 실행 당시 설정한 접근 정책과 일치했습니다.";
    if(result.label().equals("비교 중"))return "응답을 수집하고 있습니다. 모든 비교가 끝나면 종합 결과를 표시합니다.";
    return switch(result.verdict()){
      case SUSPECT->"원본과 다른 인증으로 보낸 요청에서도 접근이 허용된 것으로 의심되는 응답을 받았습니다.";
      case BLOCKED->"기준 계정을 제외한 비교 대상 모두에서 HTTP 401·403 또는 설정한 차단 규칙을 확인했습니다.";
      case REVIEW->result.reason().startsWith("기준 응답")?result.reason():"일부 응답만으로 허용·차단을 판단하기 어렵거나 비교가 끝나지 않았습니다.";
      case NONE->"다른 인증으로 비교할 수 있는 유효한 응답이 없습니다. 기준 계정만으로는 교차 권한을 판단하지 않습니다.";
    };
  }
  static String nextStep(AuthRunSummary.Result result){
    if(result.label().equals("업무 규칙 위반 후보"))return "아래 전후 값과 변화 경계를 확인하고, 동시 요청 없는 초기 상태에서 재현하세요. 업무 규칙 위반 후보이며 확정 판정이 아닙니다.";return switch(result.verdict()){
    case SUSPECT->"대상 계정에 이 데이터의 조회·실행 권한이 있는지 확인하세요. 응답 코드나 본문 일치만으로 취약점을 확정하지 않습니다.";
    case BLOCKED->result.label().equals("정책 일치")?"설정한 허용 정책이 실제 업무 권한과 맞는지 확인하세요.":"관측한 비교 응답이 차단 근거와 일치합니다. 필요한 다른 계정·객체도 같은 정책으로 확인하세요.";
    case NONE->"다른 인증 후보를 비교 계정에 지정한 뒤 다시 비교하세요. 전송되지 않은 항목은 요청·세션 상태를 확인하세요.";
    case REVIEW->result.label().equals("정책 일치")?"설정한 허용 정책과 관측 결과가 일치합니다. 정책이 실제 업무 권한과 맞는지 확인하세요.":"기준 응답이 정상인지 먼저 확인한 뒤, 아래 계정별 근거와 JSON 필드 차이를 확인하세요.";
  };}
  static String fieldTable(AuthComparePanel.Run run){
    StringBuilder out=new StringBuilder("<p>검증한 현재 기준 응답과 각 계정의 응답을 비교합니다. 중요 필드는 값이 같아도 표시합니다. 실행 당시 제외 필드 설정을 적용하며 계정당 최대 100개 차이를 표시합니다.</p>");
    for(int i=0;i<run.packets.length;i++){
      if(run.context.profiles()[i]==null)continue;
      out.append("<h3>").append(e(run.context.labels()[i])).append("</h3>");
      var packet=run.packets[i];
      if(run.baseline()==null||packet.response==null){out.append("<p>응답이 없어 필드를 비교할 수 없습니다.</p>");continue;}
      // Same bounded JSON parsing as the classifier. Large bodies remain packet-only evidence.
      if(run.baseline().body().length()>128*1024||packet.response.body().length()>128*1024){out.append("<p>대용량 응답 · 패킷에서 본문을 확인하세요.</p>");continue;}
      Map<String,Object> a,b;try{a=AuthJudgement.json(readBody(run.baseline()),run.context.rules());b=AuthJudgement.json(readBody(packet.response),run.context.rules());}catch(java.io.IOException ex){out.append("<p>본문 디코딩 실패 또는 128 KiB 초과 · 패킷에서 확인하세요.</p>");continue;}
      if(a==null||b==null){out.append("<p>JSON 구조로 비교할 수 없습니다. 응답 패킷에서 본문을 확인하세요.</p>");continue;}
      a=AuthJudgement.without(a,run.context.rules().ignored());b=AuthJudgement.without(b,run.context.rules().ignored());
      Set<String> keys=new TreeSet<>(Comparator.<String,Boolean>comparing(key->run.context.rules().important().stream().noneMatch(rule->AuthJudgement.match(key,rule))).thenComparing(Comparator.naturalOrder()));keys.addAll(a.keySet());keys.addAll(b.keySet());int count=0;
      StringBuilder rows=new StringBuilder();
      for(String key:keys){boolean important=run.context.rules().important().stream().anyMatch(rule->AuthJudgement.match(key,rule));if(!important&&a.containsKey(key)==b.containsKey(key)&&Objects.equals(a.get(key),b.get(key)))continue;if(count++==100)break;rows.append("<tr><td>").append(important?"<b>중요 · "+e(key)+"</b>":e(key)).append("</td><td>").append(e(value(a,key))).append("</td><td>").append(e(value(b,key))).append("</td></tr>");}
      if(count==0)out.append("<p>비교 대상 필드 차이 없음</p>");else{out.append("<table width='100%' cellspacing='0'><tr><th>JSON 필드</th><th>원본 값</th><th>계정 응답 값</th></tr>").append(rows).append("</table>");if(count>100)out.append("<p>차이가 많아 앞 100개만 표시했습니다.</p>");}
    }
    return out.toString();
  }
  static String effectValue(AuthEffectCheck.Snapshot s){return s==null?"미관측":!s.present()?"[대상·필드 없음]":shortValue(s.value() instanceof java.math.BigDecimal?AuthEffectCheck.display(s.value()):s.value());}
  static String shortValue(Object value){String text=String.valueOf(value);return text.length()>160?text.substring(0,160)+"…":text;}
  static String readBody(burp.api.montoya.http.message.responses.HttpResponse response)throws java.io.IOException{return JsBodyDecoder.decode(response.body().getBytes(),response.headerValue("Content-Encoding"),response.headerValue("Content-Type"),128*1024);}
  static String value(Map<String,Object> map,String key){if(!map.containsKey(key))return "[필드 없음]";Object value=map.get(key);if(value==AuthJudgement.Empty.OBJECT)return "{}";if(value==AuthJudgement.Empty.ARRAY)return "[]";String s=MetadataExport.json(value);return s.length()>160?s.substring(0,160)+"…":s;}
}
