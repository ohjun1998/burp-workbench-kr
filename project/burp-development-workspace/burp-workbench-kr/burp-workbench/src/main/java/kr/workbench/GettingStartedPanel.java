package kr.workbench;

import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;

/** Actionable status of observed setup steps. Opening a step never starts a network request. */
final class GettingStartedPanel extends JPanel implements AutoCloseable {
  record Readiness(boolean scoped,boolean collecting,long traffic,int accounts,int comparisons,boolean savedEvidence) {}
  private static final String[] TITLES={"1. 점검 SCOPE 등록","2. 요청·응답 수집 확인","3. 비교 계정 연결","4. 첫 비교 결과 확인","5. 증거 ZIP 저장"};
  private static final String[] ROUTES={"scope","traffic","auth.settings","auth","evidence"};
  final JTextArea[] states=new JTextArea[5];
  final JButton[] actions=new JButton[5];
  final JLabel summary=new JLabel();
  private final Supplier<Readiness> source;
  private final javax.swing.Timer timer=new javax.swing.Timer(700,e->refresh());
  GettingStartedPanel(Supplier<Readiness> source,Consumer<String> open) {
    super(new BorderLayout(6,6));this.source=source;setName("wb.getting.started");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
    JPanel steps=new JPanel();steps.setLayout(new BoxLayout(steps,BoxLayout.Y_AXIS));
    for(int i=0;i<5;i++){
      int step=i;JPanel row=new JPanel(new BorderLayout(6,4));row.setBorder(BorderFactory.createEmptyBorder(7,4,7,4));
      JPanel labels=new JPanel(new BorderLayout(0,3));labels.add(new JLabel(TITLES[i]),BorderLayout.NORTH);states[i]=new JTextArea(2,20);states[i].setEditable(false);states[i].setOpaque(false);states[i].setLineWrap(true);states[i].setWrapStyleWord(true);states[i].setMinimumSize(new Dimension(80,38));states[i].setName("wb.getting.started.state."+i);labels.add(states[i]);
      actions[i]=new JButton(i==0?"SCOPE 열기":i==1?"트래픽 열기":i==2?"계정 설정 열기":i==3?"비교 화면 열기":"증거 화면 열기");
      actions[i].setName("wb.getting.started.open."+i);actions[i].addActionListener(e->open.accept(ROUTES[step]));
      row.add(labels);row.add(actions[i],BorderLayout.EAST);steps.add(row);
    }
    summary.setName("wb.getting.started.summary");add(summary,BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(steps);scroll.setBorder(null);add(scroll);
    JTextArea note=new JTextArea("각 버튼은 해당 화면만 엽니다. 수집 ON, 세션 확인, 비교 전송과 증거 저장은 그 화면에서 직접 실행하세요. 완료 표시는 보안 판정·업무 완료를 뜻하지 않습니다.");note.setEditable(false);note.setLineWrap(true);note.setWrapStyleWord(true);note.setRows(3);note.setOpaque(false);add(note,BorderLayout.SOUTH);refresh();WorkbenchUi.style(this);
  }
  void refresh(){
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::refresh);return;}
    Readiness r=source.get();boolean[] done={r.scoped(),r.traffic()>0,r.accounts()>0,r.comparisons()>0,r.savedEvidence()};int count=0;for(boolean b:done)if(b)count++;
    states[0].setText(r.scoped()?"체크된 범위 있음 · 대상 주소를 확인하세요.":"등록 후 사용할 범위를 체크하세요.");
    states[1].setText("현재 "+r.traffic()+"건 · 수집 "+(r.collecting()?"SCOPE ON":"OFF")+(r.traffic()==0?" · 범위 안의 요청을 관측하거나 History에서 가져오세요.":""));
    states[2].setText(r.accounts()>0?"연결 "+r.accounts()+"계정 · 비교 준비에서 세션·기대 권한을 확인하세요.":"관측한 인증 후보를 비교 계정에 연결하세요.");
    states[3].setText(r.comparisons()>0?"정상 종료한 실행 "+r.comparisons()+"개 · 결과 사유와 근거를 검토하세요.":"원본을 선택하고 비교 준비를 확인한 뒤 직접 시작하세요.");
    states[4].setText(r.savedEvidence()?"ZIP 저장 완료 이력 있음 · 실제 파일과 내용은 별도로 확인하세요.":"원본·설명·가림 미리보기를 확인하고 ZIP을 저장하세요.");
    summary.setText("준비·사용 이력 "+count+" / 5단계 · 현재 프로젝트 상태");
  }
  @Override public void addNotify(){super.addNotify();timer.start();refresh();}
  @Override public void removeNotify(){timer.stop();super.removeNotify();}
  @Override public void close(){timer.stop();}
}
