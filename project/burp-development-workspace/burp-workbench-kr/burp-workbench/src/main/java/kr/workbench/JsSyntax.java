package kr.workbench;
import java.util.*;
/** Lexical compatibility only; preserves every character offset and never evaluates code. */
final class JsSyntax {
 record Token(String text,int start,int end,boolean quoted){}
 record Compatible(String text,boolean changed){}
 static List<Token> tokens(String source){
  List<Token> out=new ArrayList<>();String previous="";
  for(int i=0;i<source.length()&&out.size()<100000;){char c=source.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
   if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='/'){int n=source.indexOf('\n',i+2);i=n<0?source.length():n;continue;}
   if(c=='/'&&i+1<source.length()&&source.charAt(i+1)=='*'){int n=source.indexOf("*/",i+2);i=n<0?source.length():n+2;continue;}
   int start=i;
   if(c=='\''||c=='"'||c=='`'){i++;while(i<source.length()){char x=source.charAt(i++);if(x=='\\'&&i<source.length())i++;else if(x==c)break;}out.add(new Token(source.substring(start,i),start,i,true));previous="literal";continue;}
   if(c=='/'&&Set.of("","=","(","[",",",":","return","!","&&","||","=>").contains(previous)){boolean bracket=false;i++;while(i<source.length()){char x=source.charAt(i++);if(x=='\\'&&i<source.length())i++;else if(x=='[')bracket=true;else if(x==']')bracket=false;else if(x=='/'&&!bracket)break;else if(x=='\n')break;}while(i<source.length()&&Character.isLetter(source.charAt(i)))i++;previous="regex";continue;}
   if(Character.isJavaIdentifierStart(c)||c=='$'){i++;while(i<source.length()&&(Character.isJavaIdentifierPart(source.charAt(i))||source.charAt(i)=='$'))i++;}else{i++;if(i<source.length()&&source.substring(start,Math.min(source.length(),start+2)).matches("=>|&&|\\|\\||\\?\\?"))i++;}
   previous=source.substring(start,i);out.add(new Token(previous,start,i,false));
  }return out;
 }
 static Compatible compatible(String source){char[] chars=source.toCharArray();boolean changed=false;List<Token> ts=tokens(source);
  for(int i=0;i<ts.size();i++){Token t=ts.get(i);if(t.quoted)continue;String prev=i==0?"":ts.get(i-1).text,next=i+1==ts.size()?"":ts.get(i+1).text;if(prev.equals(".")||next.equals(":"))continue;
   boolean mask=t.text.equals("await")&&!Set.of("var","let","const","function").contains(prev)
    ||t.text.equals("async")&&(next.equals("function")||next.equals("(")||i+2<ts.size()&&ts.get(i+2).text.equals("=>"))
    ||t.text.equals("export")&&Set.of("const","let","var","function","class","default","async").contains(next)
    ||t.text.equals("default")&&prev.equals("export");
   if(mask){blank(chars,t.start,t.end);changed=true;}
   // Treat dynamic import as an opaque call while preserving offsets and raw dependency mining.
   if(t.text.equals("import")&&next.equals("(")){chars[t.start]='_';changed=true;}
   if(t.text.equals("import")&&!next.equals("(")&&!next.equals(".")){int end=i+1;while(end<ts.size()&&!ts.get(end).text.equals(";")&&source.substring(t.end,ts.get(end).start).indexOf('\n')<0)end++;if(end<ts.size()&&ts.get(end).text.equals(";")){blank(chars,t.start,ts.get(end).end);i=end;changed=true;}}
  }return new Compatible(new String(chars),changed);
 }
 private static void blank(char[] chars,int start,int end){for(int i=start;i<end;i++)if(chars[i]!='\n'&&chars[i]!='\r')chars[i]=' ';}
}
