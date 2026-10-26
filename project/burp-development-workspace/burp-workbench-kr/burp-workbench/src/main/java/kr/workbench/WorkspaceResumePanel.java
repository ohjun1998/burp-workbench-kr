package kr.workbench;

import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.swing.*;

/** Explicit restore choices; opening this panel never restores a view or sends a request. */
final class WorkspaceResumePanel extends JPanel {
  final JTextArea details = new JTextArea(14, 52);
  final JButton resume = new JButton("화면·검색 조건 이어가기"), fresh = new JButton("새 작업으로 시작");
  WorkspaceResumePanel(WorkspaceResume.Snapshot snapshot, String taskSummary, boolean originalAvailable,
      String problem, Consumer<WorkspaceResume.Snapshot> restore, Runnable startFresh) {
    super(new BorderLayout(8, 8));
    setName("wb.workspace.resume"); setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
    details.setName("wb.workspace.resume.details");
    String text;
    if (snapshot == null) text = problem.isEmpty() ? "저장된 작업 위치가 없습니다. 화면·업무·검색 조건은 이 프로젝트에 저장됩니다." : problem;
    else {
      String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(snapshot.savedAt()).atZone(ZoneId.systemDefault()));
      text = "마지막 저장: " + time + "\n화면: " + FeatureGuide.title(snapshot.feature())
          + "\n\n업무: " + taskSummary
          + "\n\n원본: " + (snapshot.original().isEmpty() ? "선택 기록 없음"
              : originalAvailable ? "현재 임시 저장소에서 정확한 원본을 확인했습니다."
              : "이전 원본이 현재 임시 저장소에 없습니다. 트래픽을 다시 모으거나 원본을 직접 선택하세요.")
          + "\n\n검색: " + filterSummary(snapshot.filters()) + "\n검색어는 저장하지 않습니다."
          + "\n\n계정: 인증값은 복원하지 않습니다. 인증 후보를 비교 계정에 다시 연결하고 신원을 확인하세요."
          + "\n증거: 저장 이력은 파일 존재·해시 확인과 다릅니다. 점검 진행 현황에서 증거 ZIP을 확인하세요."
          + "\n\n이어가기는 화면·선택·조건만 복원합니다. 비교 실행과 업무 완료는 직접 선택합니다.";
    }
    details.setText(text); details.setCaretPosition(0); add(new JScrollPane(details));
    resume.setName("wb.workspace.resume.apply"); fresh.setName("wb.workspace.resume.fresh");
    resume.setEnabled(snapshot != null); resume.addActionListener(e -> restore.accept(snapshot));
    fresh.addActionListener(e -> startFresh.run());
    add(WorkbenchUi.row(fresh, resume), BorderLayout.SOUTH); WorkbenchUi.style(this);
  }

  private static String filterSummary(String encoded) {
    TrafficQuery query = TrafficSearchPresets.restore(encoded);
    java.util.StringJoiner fields = new java.util.StringJoiner(" · ");
    fields.add(query.method()).add(query.status());
    fields.add(new String[] {"목록", "요청 포함", "전체 패킷"}[query.range()]);
    if (!query.account().isEmpty()) fields.add("계정 " + query.account());
    if (query.minutes() > 0) fields.add("최근 " + query.minutes() + "분");
    if (query.group()) fields.add("비슷한 요청 묶기");
    if (query.important()) fields.add("중요 표시만");
    if (query.regex()) fields.add("정규식");
    if (query.sensitive()) fields.add("대소문자 구분");
    return fields.toString();
  }
}
