package kr.workbench;
import java.util.*;
/** Bounded raw preview. Highlighting never modifies stored or transmitted bytes. */
final class PacketDiff {
 enum Kind { AUTH,HEADER,BODY }
 record Range(int start,int end,Kind kind){}
 record View(String text,List<Range> ranges,boolean limited){}
 static View[] compare(String[] packets,Set<String> credentials){
  int n=packets.length,maximum=0,active=0;String[] text=new String[n];String[][] lines=new String[n][];boolean[] limited=new boolean[n],body=new boolean[n];int[] offsets=new int[n];List<List<Range>> marks=new ArrayList<>();
  for(int i=0;i<n;i++){marks.add(new ArrayList<>());if(packets[i]==null){text[i]="패킷 없음 · 미지정 / 대기 / 오류";continue;}active++;String raw=packets[i];limited[i]=raw.length()>65536;text[i]=raw.substring(0,Math.min(raw.length(),65536)).replace("\r\n","\n");lines[i]=text[i].split("\n",-1);if(lines[i].length>2000){lines[i]=Arrays.copyOf(lines[i],2000);text[i]=String.join("\n",lines[i]);limited[i]=true;}maximum=Math.max(maximum,lines[i].length);}
  for(int line=0;line<maximum;line++){Set<String> values=new HashSet<>();for(int i=0;i<n;i++)if(lines[i]!=null)values.add(line<lines[i].length?lines[i][line]:"\0<missing>");boolean different=active>1&&values.size()>1;
   for(int i=0;i<n;i++)if(lines[i]!=null&&line<lines[i].length){String v=lines[i][line];if(v.isEmpty())body[i]=true;if(different&&!v.isEmpty()){int colon=v.indexOf(':');String key=colon<0?"":v.substring(0,colon).toLowerCase(Locale.ROOT);marks.get(i).add(new Range(offsets[i],offsets[i]+v.length(),body[i]?Kind.BODY:credentials.contains(key)?Kind.AUTH:Kind.HEADER));}offsets[i]+=v.length()+1;}}
  View[] result=new View[n];for(int i=0;i<n;i++)result[i]=new View(text[i],List.copyOf(marks.get(i)),limited[i]);return result;
 }
}
