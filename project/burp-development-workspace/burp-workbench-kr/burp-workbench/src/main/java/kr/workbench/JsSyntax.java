package kr.workbench;
import java.util.*;
/** Lexical compatibility only; preserves every character offset and never evaluates code. */
final class JsSyntax {
 record Token(String text,int start,int end,boolean quoted){}
 record Compatible(String text,boolean changed){}
 static final JsStaticContentCache CONTENT_CACHE=new JsStaticContentCache(JsStaticContentCache.DEFAULT_BUDGET);
 static List<Token> tokens(String source){
  List<Token> out=new ArrayList<>();Cursor cursor=new Cursor(source);Token token;
  while(out.size()<100000&&(token=cursor.next())!=null)out.add(token);
  return out;
 }
 /** Streaming lexer for large bundles; callers supply their own time/result budgets. */
 static final class Cursor {
  final String source;int position;String previous="";
  Cursor(String source){this.source=source;}
  Token next(){
   while(position<source.length()){
    char c=source.charAt(position);
    if(Character.isWhitespace(c)){position++;continue;}
    if(c=='/'&&position+1<source.length()&&source.charAt(position+1)=='/'){int n=source.indexOf('\n',position+2);position=n<0?source.length():n;continue;}
    if(c=='/'&&position+1<source.length()&&source.charAt(position+1)=='*'){int n=source.indexOf("*/",position+2);position=n<0?source.length():n+2;continue;}
    int start=position++;
    if(c=='\''||c=='"'||c=='`'){
     while(position<source.length()){char x=source.charAt(position++);if(x=='\\'&&position<source.length())position++;else if(x==c)break;}
     previous="literal";return new Token(source.substring(start,position),start,position,true);
    }
    if(c=='/'&&Set.of("","=","(","[",",",":","return","!","&&","||","=>").contains(previous)){
     boolean bracket=false;
     while(position<source.length()){char x=source.charAt(position++);if(x=='\\'&&position<source.length())position++;else if(x=='[')bracket=true;else if(x==']')bracket=false;else if(x=='/'&&!bracket)break;else if(x=='\n')break;}
     while(position<source.length()&&Character.isLetter(source.charAt(position)))position++;
     previous="regex";continue;
    }
    if(Character.isJavaIdentifierStart(c)||c=='$'){while(position<source.length()&&(Character.isJavaIdentifierPart(source.charAt(position))||source.charAt(position)=='$'))position++;}
    else if(position<source.length()&&source.substring(start,Math.min(source.length(),start+2)).matches("=>|&&|\\|\\||\\?\\?"))position++;
    previous=source.substring(start,position);return new Token(previous,start,position,false);
   }
   return null;
  }
 }
 static Compatible compatible(String source){return CONTENT_CACHE.get(source,JsStaticContentCache.PARSER_CONFIG,()->normalize(source));}
 private static Compatible normalize(String source){char[] chars=source.toCharArray();boolean changed=false;List<Token> ts=tokens(source);
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
