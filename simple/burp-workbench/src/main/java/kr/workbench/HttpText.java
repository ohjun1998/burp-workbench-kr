package kr.workbench;
import java.nio.*;
import java.nio.charset.*;
import java.util.regex.*;
/** Byte decoding and conservative display-only mojibake recovery. Never edits HTTP bytes. */
public final class HttpText {
 private HttpText(){}
 static String strict(byte[] bytes,Charset charset)throws CharacterCodingException{return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
 public static String decode(byte[] bytes,String contentType){
  if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191)return new String(bytes,3,bytes.length-3,StandardCharsets.UTF_8);
  if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
  if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255)return new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
  Matcher declared=Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^\\s;\"']+)").matcher(contentType==null?"":contentType);
  if(declared.find())try{return strict(bytes,Charset.forName(declared.group(1).matches("(?i)(windows-949|cp949|uhc)")?"MS949":declared.group(1)));}catch(Exception ignored){}
  try{return strict(bytes,StandardCharsets.UTF_8);}catch(CharacterCodingException ignored){return new String(bytes,StandardCharsets.ISO_8859_1);}
 }
 private static int hangul(String s){return (int)s.codePoints().filter(c->c>=0xac00&&c<=0xd7a3).count();}
 private static int artifacts(String s){return (int)s.codePoints().filter(c->c>=0x80&&c<=0x9f||"ÃÂâêëìíðï".indexOf(c)>=0).count();}
 private static String recover(String s){if(artifacts(s)<2)return s;
  String best=s;for(String charset:new String[]{"ISO-8859-1","windows-1252"})try{
   var encoded=Charset.forName(charset).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));byte[] b=new byte[encoded.remaining()];encoded.get(b);String candidate=strict(b,StandardCharsets.UTF_8);
   if(hangul(candidate)>hangul(best)&&artifacts(candidate)<artifacts(best))best=candidate;
  }catch(Exception ignored){}return best;
 }
 public static String display(String s){if(s==null||s.length()>65536||artifacts(s)<2)return s;String whole=recover(s);if(!whole.equals(s))return whole;
  Matcher runs=Pattern.compile("[\\u0080-\\u00ff\\u0152\\u0153\\u0160\\u0161\\u0178\\u017d\\u017e\\u0192\\u02c6\\u02dc\\u2013-\\u201a\\u201c-\\u201e\\u2020-\\u2022\\u2026\\u2030\\u2039\\u203a\\u20ac\\u2122]+").matcher(s);StringBuffer out=new StringBuffer();while(runs.find())runs.appendReplacement(out,Matcher.quoteReplacement(recover(runs.group())));runs.appendTail(out);return out.toString();
 }
}
