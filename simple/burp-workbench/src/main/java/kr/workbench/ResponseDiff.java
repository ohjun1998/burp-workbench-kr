package kr.workbench;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Bounded linear-time aligned-line preview; never a vulnerability verdict. */
final class ResponseDiff {
    static final int CHAR_LIMIT=65536,LINE_LIMIT=2000;
    record View(String left,String right,String summary,List<int[]> leftRanges,List<int[]> rightRanges,List<Integer> rightOffsets){}
    static View compare(String a,String b){
        if(a==null||b==null)return new View(a==null?"응답 없음":a.substring(0,Math.min(a.length(),CHAR_LIMIT)),b==null?"응답 없음":b.substring(0,Math.min(b.length(),CHAR_LIMIT)),"양쪽 응답이 있어야 비교할 수 있습니다.",List.of(),List.of(),List.of());
        if(a.indexOf('\0')>=0||b.indexOf('\0')>=0)return new View("바이너리 응답 · 원본 메시지에서 확인","바이너리 응답 · 비교 메시지에서 확인","바이너리 본문은 텍스트 차이를 표시하지 않습니다.",List.of(),List.of(),List.of());
        boolean capped=a.length()>CHAR_LIMIT||b.length()>CHAR_LIMIT;
        String[] x=a.substring(0,Math.min(a.length(),CHAR_LIMIT)).split("\\R",-1),y=b.substring(0,Math.min(b.length(),CHAR_LIMIT)).split("\\R",-1);
        if(x.length>LINE_LIMIT||y.length>LINE_LIMIT)capped=true;
        x=Arrays.copyOf(x,Math.min(x.length,LINE_LIMIT));y=Arrays.copyOf(y,Math.min(y.length,LINE_LIMIT));
        int prefix=0;while(prefix<Math.min(x.length,y.length)&&x[prefix].equals(y[prefix]))prefix++;
        int suffix=0;while(suffix<Math.min(x.length,y.length)-prefix&&x[x.length-1-suffix].equals(y[y.length-1-suffix]))suffix++;
        List<int[]> l=new ArrayList<>(),r=new ArrayList<>();List<Integer> offsets=new ArrayList<>();StringBuilder left=new StringBuilder(),right=new StringBuilder();int changed=0;
        int middle=Math.max(x.length-prefix-suffix,y.length-prefix-suffix);
        for(int i=0;i<prefix;i++){append(left,x[i],false,l,null);append(right,y[i],false,r,offsets);}
        for(int i=0;i<middle;i++){
            String xx=i<x.length-prefix-suffix?x[prefix+i]:null,yy=i<y.length-prefix-suffix?y[prefix+i]:null;
            boolean different=!Objects.equals(xx,yy);if(different)changed++;
            append(left,xx,different,l,null);append(right,yy,different,r,offsets);
        }
        for(int i=suffix;i>0;i--){append(left,x[x.length-i],false,l,null);append(right,y[y.length-i],false,r,offsets);}
        String summary="본문 "+a.getBytes(StandardCharsets.UTF_8).length+" → "+b.getBytes(StandardCharsets.UTF_8).length+" bytes · "+(a.equals(b)?"동일":"정렬 비교 변경 "+changed+"줄")+(capped?" · 미리보기 제한 64K자 / 2,000줄":"")+" · 취약점 확정 아님";
        return new View(left.toString(),right.toString(),summary,List.copyOf(l),List.copyOf(r),List.copyOf(offsets));
    }
    private static void append(StringBuilder out,String line,boolean changed,List<int[]> ranges,List<Integer> offsets){int start=out.length();out.append(line==null?"":line).append('\n');if(changed){ranges.add(new int[]{start,out.length()});if(offsets!=null)offsets.add(start);}}
}
