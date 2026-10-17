package kr.workbench;

import java.awt.*;import java.net.URI;import javax.swing.*;

/** Additional settings stay inside the existing comparison settings window. */
final class AuthEffectPanel extends JPanel {
  final JCheckBox enabled=new JCheckBox("쓰기 요청 전후 조회로 처리 결과 확인");
  final JComboBox<String> method=new JComboBox<>(new String[]{"POST","PUT","PATCH","DELETE"}),mode=new JComboBox<>(new String[]{"기대값으로 변경","값 변경 관측","대상/필드 삭제"});
  final JTextField path=new JTextField(22),url=new JTextField(35),jsonPath=new JTextField(20),expected=new JTextField(18);
  final JSpinner attempts=new JSpinner(new SpinnerNumberModel(1,1,3,1));
  AuthEffectPanel(AuthComparePanel owner){super(new BorderLayout());setName("wb.auth.effect.settings");mode.setPreferredSize(new Dimension(180,32));method.setPreferredSize(new Dimension(90,32));
    JPanel body=TargetWindow.options(WorkbenchUi.row(enabled),WorkbenchUi.row(new JLabel("대상 요청"),method,path,owner.button("선택 요청 연결",()->{owner.editable();var requests=owner.selected.get();if(requests.size()!=1)throw new IllegalArgumentException("쓰기 요청 한 개를 선택하세요.");var q=requests.get(0).request();if(!java.util.Set.of("POST","PUT","PATCH","DELETE").contains(q.method()))throw new IllegalArgumentException("쓰기 요청을 선택하세요.");method.setSelectedItem(q.method());path.setText(URI.create(q.url()).getPath());})),WorkbenchUi.row(new JLabel("조회 GET URL"),url),WorkbenchUi.row(new JLabel("결과 JSON"),jsonPath,new JLabel("기대값"),expected),WorkbenchUi.row(mode,new JLabel("최대 조회"),attempts),new JLabel("기준 요청의 인증으로 전후 조회합니다. 대상 쓰기 요청은 계정당 한 번만 보내며 되돌리지는 않습니다."),new JLabel("같은 Origin/SCOPE · 최대 128KiB · 이미 기대 상태이면 대상 요청을 보내지 않습니다."));add(body);
  }
  AuthEffectCheck.Config snapshot(){return new AuthEffectCheck.Config(enabled.isSelected(),(String)method.getSelectedItem(),path.getText().strip(),url.getText().strip(),jsonPath.getText().strip(),expected.getText(),AuthEffectCheck.Mode.values()[mode.getSelectedIndex()],(Integer)attempts.getValue());}
}
