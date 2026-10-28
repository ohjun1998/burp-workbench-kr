package kr.workbench;

/** One aggregate per request; only stable result codes enter the production decision. */
final class AuthRunSummary {
  record Result(String label,AuthVerdict verdict,String reason){}
  static boolean reference(AuthComparePanel.Packet packet){return packet.finding().reference();}
  static String responseLabel(AuthComparePanel.Run run,int slot){
    var p=run.packets[slot];if(run.context.profiles()[slot]==null)return "미지정";
    if(p.response!=null)return p.response.statusCode()+(reference(p)?" · 기준":"");
    return run.note.equals("진행 중")&&p.note.equals("대기")?"대기":"응답 없음";
  }
  static Result of(AuthComparePanel.Run run){
    if(run.note.equals("진행 중"))return new Result("비교 중",AuthVerdict.NONE,"계정별 비교가 끝난 뒤 종합 결과를 표시합니다.");
    if(run.baselineState==AuthComparePanel.Run.Baseline.INVALID)return new Result("세션 확인 필요",AuthVerdict.REVIEW,run.baselineNote+" · 인가 차단 판정 보류");
    // Old imported snapshots also cannot treat a failed current reference as successful denial.
    for(var p:run.packets)if(reference(p)&&p.response!=null&&(p.response.statusCode()<200||p.response.statusCode()>=300))return new Result("세션 확인 필요",AuthVerdict.REVIEW,"현재 기준 계정이 HTTP "+p.response.statusCode()+" · 저장된 원본 2xx만으로 권한 차단을 판단하지 않습니다.");
    int compared=0,suspect=0,blocked=0,allowed=0,uncertain=0,missing=0;
    for(int i=0;i<run.packets.length;i++){
      if(run.context.profiles()[i]==null||reference(run.packets[i]))continue;
      var p=run.packets[i];var f=p.finding();
      if(p.response==null||f.verdict()==AuthVerdict.NONE){missing++;continue;}compared++;
      if(f.verdict()==AuthVerdict.SUSPECT)suspect++;else if(f.kind()==AuthFinding.Kind.ALLOWED)allowed++;else if(f.verdict()==AuthVerdict.BLOCKED)blocked++;else uncertain++;
    }
    if(compared==0&&java.util.Arrays.stream(run.packets).anyMatch(p->p.finding().verdict()==AuthVerdict.REVIEW))return new Result("확인 필요",AuthVerdict.REVIEW,"세션·사전 조회 또는 실행 조건 확인 필요 · 상세 근거를 확인하세요.");
    if(compared==0)return new Result("비교 미성립",AuthVerdict.NONE,"기준 계정을 제외한 유효한 비교 응답이 없습니다. 상세에서 미전송·세션 상태를 확인하세요.");
    if(run.baseline()==null||run.baseline().statusCode()<200||run.baseline().statusCode()>=300)return new Result("확인 필요",AuthVerdict.REVIEW,"기준 응답이 정상 성공 응답이 아닙니다. 기준 계정·세션을 확인하세요.");
    String counts="기준 계정 제외 · 비교 응답 "+compared+"개 · 우회 의심 "+suspect+" · 차단 "+blocked+" · 허용 정책 일치 "+allowed+" · 확인 필요 "+uncertain+" · 비교 미성립 "+missing;
    if(suspect>0&&java.util.Arrays.stream(run.packets).anyMatch(p->p.finding().verdict()==AuthVerdict.SUSPECT&&p.effect!=null&&AuthEffectCheck.numeric(p.effect.config().mode())&&p.effect.outcome()==AuthEffectCheck.Outcome.OBSERVED))return new Result("업무 규칙 위반 후보",AuthVerdict.SUSPECT,"설정한 숫자 변화 경계를 넘는 전후 상태를 관측했습니다. 권한 정책과 별개로 실제 업무 규칙과 동시 변경을 확인하세요.");
    if(suspect>0)return new Result("우회 의심",AuthVerdict.SUSPECT,counts+" · 실제 권한 위반 여부는 상세 근거로 확인하세요.");
    if(uncertain>0||missing>0||!run.note.equals("완료"))return new Result("확인 필요",AuthVerdict.REVIEW,counts+" · 일부 결과가 미확인 상태입니다.");
    if(run.baselineState==AuthComparePanel.Run.Baseline.WRITE&&java.util.Arrays.stream(run.packets).noneMatch(p->p.effect!=null&&p.effect.outcome()==AuthEffectCheck.Outcome.OBSERVED))return new Result("확인 필요",AuthVerdict.REVIEW,counts+" · 쓰기 기준 세션/실제 처리 결과 재검증 필요");
    if(allowed>0)return new Result("정책 일치",AuthVerdict.BLOCKED,counts+" · 설정한 허용/차단 정책과 관측 결과가 일치합니다.");
    return new Result("차단 응답 확인",AuthVerdict.BLOCKED,counts+" · 비교 대상 모두에서 차단 근거를 확인했습니다.");
  }
}
