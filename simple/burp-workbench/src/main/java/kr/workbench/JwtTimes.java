package kr.workbench;
import java.time.*;import java.math.*;import java.util.*;
/** Displays unverified NumericDate claims; never validates a signature. */
final class JwtTimes {
 static String describe(String payload,Instant now){
  Object parsed;try{parsed=MinerJson.parse(payload);}catch(Exception e){return "\n\n시간 정보: payload JSON 형식/한도 확인 필요";}
  if(!(parsed instanceof Map<?,?> map))return "\n\n시간 정보: JSON 객체가 아님";
  StringBuilder out=new StringBuilder("\n\n시간 정보 · 서명 미검증 · 평가 시각 ").append(now).append(" (UTC)");
  boolean any=false;for(String key:List.of("exp","nbf","iat")){if(!map.containsKey(key))continue;any=true;try{
   if(!(map.get(key) instanceof Number n))throw new IllegalArgumentException();BigDecimal value=new BigDecimal(n.toString());if(value.precision()>32||Math.abs((long)value.scale())>12||value.compareTo(new BigDecimal("-62135596800"))<0||value.compareTo(new BigDecimal("253402300800"))>=0)throw new IllegalArgumentException();BigDecimal seconds=value.setScale(0,RoundingMode.FLOOR);
   long whole=seconds.longValueExact();if(whole < -62135596800L||whole > 253402300799L)throw new IllegalArgumentException();int nanos=value.subtract(seconds).movePointRight(9).intValue();Instant t=Instant.ofEpochSecond(whole,nanos);
   out.append('\n').append(key).append(": ").append(t).append(" (UTC) · ");
   out.append(switch(key){case "exp"->!now.isBefore(t)?"만료 시각 경과":"만료 전";case "nbf"->now.isBefore(t)?"사용 시작 전":"사용 시작 시각 경과";default->now.isBefore(t)?"발급 시각이 미래 · 서버/PC 시각 확인":"발급 시각 경과";});
  }catch(Exception e){out.append('\n').append(key).append(": 숫자/표시 가능한 시간 범위 확인 필요");}}
  if(!any)out.append("\nexp / nbf / iat 없음");return out.append("\n시간 표시만으로 서버의 토큰 수용 여부를 판단하지 않습니다.").toString();
 }
}
