package kr.workbench;

/** Stable machine result. Display text is never used to classify a production packet. */
record AuthFinding(Kind kind,String reason,String changes) {
  enum Kind {
    NONE("미지정",AuthVerdict.NONE),PENDING("대기",AuthVerdict.NONE),REFERENCE("원본과 동일 인증",AuthVerdict.NONE),
    NO_RESPONSE("응답 없음",AuthVerdict.NONE),ERROR("전송 오류",AuthVerdict.NONE),CANCELLED("중지 / 미전송",AuthVerdict.NONE),
    SESSION("신원 검사 실패",AuthVerdict.NONE),REFRESH("갱신 실패",AuthVerdict.NONE),CHANGED("전송 세션 변경",AuthVerdict.NONE),
    BASELINE("판정 보류",AuthVerdict.REVIEW),INCOMPLETE("판정 보류",AuthVerdict.REVIEW),REVIEW("판정 보류",AuthVerdict.REVIEW),CONFLICT("설정 충돌",AuthVerdict.REVIEW),
    DENIAL_DATA("차단 응답과 데이터 충돌",AuthVerdict.REVIEW),
    WRITE_UNVERIFIED("쓰기 검증 미설정",AuthVerdict.REVIEW),WRITE_UNCHANGED("처리 결과 미확인",AuthVerdict.REVIEW),
    WRITE_PENDING("지연 처리 확인 필요",AuthVerdict.REVIEW),WRITE_PARTIAL("기대 상태 미도달",AuthVerdict.REVIEW),
    WRITE_READ_FAILED("후속 조회 실패",AuthVerdict.REVIEW),WRITE_SETUP_FAILED("전후 조회 준비 실패",AuthVerdict.REVIEW),
    BLOCKED("차단 근거 확인",AuthVerdict.BLOCKED),ALLOWED("공개/공유 허용",AuthVerdict.BLOCKED),
    SUSPECT("우회 의심",AuthVerdict.SUSPECT),OWNER("타인 객체 접근 의심",AuthVerdict.SUSPECT),
    OWNER_UNKNOWN("소유값 불일치 · 정책 확인",AuthVerdict.REVIEW),HIDDEN("은닉형 접근 차단 후보",AuthVerdict.REVIEW),MISSING("은닉형 차단/미발견 후보",AuthVerdict.REVIEW);
    final String label;final AuthVerdict verdict;
    Kind(String label,AuthVerdict verdict){this.label=label;this.verdict=verdict;}
  }
  AuthFinding {java.util.Objects.requireNonNull(kind);reason=java.util.Objects.requireNonNullElse(reason,"");changes=java.util.Objects.requireNonNullElse(changes,"");}
  String note(){return kind.label+(reason.isEmpty()?"":" · "+reason);}
  AuthVerdict verdict(){return kind.verdict;}
  boolean reference(){return kind==Kind.REFERENCE;}
  /** Import compatibility for old test fixtures/history, never called for newly produced findings. */
  static AuthFinding legacy(String note,String changes){
    if(note.startsWith("원본과 동일 인증"))return new AuthFinding(Kind.REFERENCE,"교차 인가 미검증",changes);
    if(note.startsWith("공개/공유 허용")||note.startsWith("기대 ALLOW · 성공 응답")&&!note.contains("설정 충돌")&&!note.contains("재검토"))return new AuthFinding(Kind.ALLOWED,note,changes);
    AuthVerdict verdict=AuthVerdict.of(note);return new AuthFinding(switch(verdict){case SUSPECT->Kind.SUSPECT;case BLOCKED->Kind.BLOCKED;case REVIEW->Kind.REVIEW;case NONE->Kind.NONE;},note,changes);
  }
}
