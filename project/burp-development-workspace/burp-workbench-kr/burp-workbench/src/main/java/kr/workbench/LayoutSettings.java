package kr.workbench;
import burp.api.montoya.MontoyaApi;import javax.swing.*;import java.awt.*;
/** Shared layout preference, edited only from the independent Settings tab. */
public final class LayoutSettings extends JPanel {
 final Component page;final MontoyaApi api;final String key;final JComboBox<String> choice;final JLabel hint=new JLabel();final JButton reset=new JButton("배치 초기화");
 public LayoutSettings(MontoyaApi api,Component page){
  super();setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setName("wb.settings.layout.common");this.api=api;this.page=page;key="wb.layout.horizontal";
  choice=new JComboBox<>(new String[]{"상/하 · 목록 왼쪽","좌/우 · 목록 위"});choice.setName("wb.layout.choice");
  boolean horizontal=false;try{Boolean saved=api.persistence().preferences().getBoolean(key);horizontal=Boolean.TRUE.equals(saved);}catch(Exception ignored){}
  choice.setSelectedIndex(horizontal?1:0);add(WorkbenchUi.row(new JLabel("요청 / 응답 배치"),choice));add(WorkbenchUi.row(hint));add(WorkbenchUi.row(reset,new JLabel("현재 배치의 분할 비율 복원")));choice.addActionListener(e->apply());reset.addActionListener(e->apply());WorkbenchUi.style(this);apply();
 }
 void apply(){boolean horizontal=choice.getSelectedIndex()==1;hint.setText(page instanceof JComponent j&&Boolean.TRUE.equals(j.getClientProperty("wb.data.tools"))?"변환과 JWT에 같은 배치를 적용합니다.":horizontal?"목록은 위에, 요청과 응답은 아래에 나란히 표시합니다.":"목록은 왼쪽에, 요청과 응답은 오른쪽에 위아래로 표시합니다.");if(page instanceof JComponent j)j.putClientProperty("wb.packet.horizontal",horizontal);WorkbenchUi.packetLayout(page,horizontal);try{api.persistence().preferences().setBoolean(key,horizontal);}catch(Exception ignored){} }
}
