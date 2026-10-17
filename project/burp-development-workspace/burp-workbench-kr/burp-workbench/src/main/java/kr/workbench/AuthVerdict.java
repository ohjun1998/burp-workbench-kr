package kr.workbench;

import java.awt.*;
import javax.swing.*;

/** Authorization verdicts deliberately do not use the generic HTTP-success color. */
enum AuthVerdict {
  SUSPECT("우회 의심", WorkbenchUi.Tone.ERROR),
  BLOCKED("차단 응답 확인", WorkbenchUi.Tone.SUCCESS),
  REVIEW("확인 필요", WorkbenchUi.Tone.WARNING),
  NONE("비교 미성립", WorkbenchUi.Tone.NEUTRAL);
  final String label;
  final WorkbenchUi.Tone tone;
  AuthVerdict(String label, WorkbenchUi.Tone tone) { this.label = label; this.tone = tone; }
  static AuthVerdict of(String note) {
    if (note.contains("미검증") || note.contains("미전송") || note.contains("전송 오류")
        || note.contains("전송 세션 변경") || note.contains("실제 전송 대상/메서드 변경")
        || note.equals("응답 없음") || note.contains("저장 한도") || note.equals("대기")
        || note.equals("미지정") || note.startsWith("중지")) return NONE;
    if (note.contains("판정 보류") || note.contains("재검토") || note.contains("설정 충돌")
        || note.contains("404") || note.contains("410")) return REVIEW;
    if (note.startsWith("차단 근거 확인") || note.startsWith("기대 DENY · 차단 근거 일치")) return BLOCKED;
    if (note.startsWith("우회 의심") || note.startsWith("타인 객체 접근 의심")
        || note.contains("권한 위반 후보") || note.startsWith("동일 파일 접근 후보")) return SUSPECT;
    return REVIEW;
  }
  String describe(String note) { return note.startsWith(label) ? note : label + " · " + note; }
  Icon icon() {
    return new Icon() {
      public int getIconWidth() { return 12; }
      public int getIconHeight() { return 12; }
      public void paintIcon(Component c, Graphics g, int x, int y) {
        g.setColor(WorkbenchUi.toneInk(tone)); g.fillOval(x + 2, y + 2, 8, 8);
      }
    };
  }
}
