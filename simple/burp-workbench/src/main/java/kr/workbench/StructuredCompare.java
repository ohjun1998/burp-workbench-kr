package kr.workbench;
import java.util.*;
/** Bounded line alignment for formatted views. Inputs are never rewritten. */
final class StructuredCompare {
 record Change(int leftStart,int leftEnd,int rightStart,int rightEnd){}
 record Result(String left,String right,List<Change> changes,boolean limited){}
 static String format(String text){if(text.matches("(?s)^(?:HTTP/\\d|[A-Z]+ [^\\r\\n]+ HTTP/\\d).*"))return PacketFormat.pretty(text);String prefix="HTTP/1.1 200\n\n";String formatted=PacketFormat.pretty(prefix+text);return formatted.substring(prefix.length());}
 static Result compare(String left,String right,boolean formatted){boolean limited=left.length()>65536||right.length()>65536;left=left.substring(0,Math.min(left.length(),65536));right=right.substring(0,Math.min(right.length(),65536));if(formatted){left=format(left);right=format(right);}String[] a=left.replace("\r\n","\n").split("\n",-1),b=right.replace("\r\n","\n").split("\n",-1);if(a.length>1500||b.length>1500){a=Arrays.copyOf(a,Math.min(a.length,1500));b=Arrays.copyOf(b,Math.min(b.length,1500));limited=true;}
 int[][] lcs=new int[a.length+1][b.length+1];for(int i=a.length-1;i>=0;i--)for(int j=b.length-1;j>=0;j--)lcs[i][j]=a[i].equals(b[j])?1+lcs[i+1][j+1]:Math.max(lcs[i+1][j],lcs[i][j+1]);StringBuilder x=new StringBuilder(),y=new StringBuilder();List<Change> marks=new ArrayList<>();int i=0,j=0;while(i<a.length||j<b.length){if(i<a.length&&j<b.length&&a[i].equals(b[j])){line(x,a[i++]);line(y,b[j++]);continue;}int xs=x.length(),ys=y.length();List<String> deleted=new ArrayList<>(),added=new ArrayList<>();while(i<a.length||j<b.length){if(i<a.length&&j<b.length&&a[i].equals(b[j]))break;if(j==b.length||(i<a.length&&lcs[i+1][j]>=lcs[i][j+1]))deleted.add(a[i++]);else added.add(b[j++]);}for(int k=0;k<Math.max(deleted.size(),added.size());k++){line(x,k<deleted.size()?deleted.get(k):"");line(y,k<added.size()?added.get(k):"");}marks.add(new Change(xs,x.length(),ys,y.length()));}return new Result(x.toString(),y.toString(),List.copyOf(marks),limited);
 }
 static void line(StringBuilder s,String line){s.append(line).append('\n');}
}
