package kr.workbench;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Decodes stacked content encodings in reverse order, with a separate working-memory budget. */
final class JsBodyDecoder {
  static final int WORK_LIMIT=32*1024*1024;
  static String decode(byte[] original,String encoding,String type)throws IOException{
    return decode(original,encoding,type,WORK_LIMIT);
  }
  static String decode(byte[] original,String encoding,String type,int limit)throws IOException{
    if(limit<1||limit>WORK_LIMIT)throw new IllegalArgumentException("디코딩 크기 제한");
    byte[] bytes=original;
    if(encoding!=null&&!encoding.isBlank()){
      String[] encodings=encoding.toLowerCase(Locale.ROOT).split(",");
      if(encodings.length>4)throw new IOException("Content-Encoding 중첩 제한 · 미분석");
      for(int i=encodings.length-1;i>=0;i--){String code=encodings[i].strip();if(code.isEmpty()||code.equals("identity"))continue;
        if(code.equals("deflate")){
          try{bytes=read(new InflaterInputStream(new ByteArrayInputStream(bytes)),limit);}
          catch(ZipException ex){bytes=read(new InflaterInputStream(new ByteArrayInputStream(bytes),new Inflater(true)),limit);}
        }else if(code.equals("gzip")||code.equals("x-gzip")){
          // Some hosts supply already-decoded content while retaining the original header.
          if(bytes.length>=2&&(bytes[0]&255)==31&&(bytes[1]&255)==139)bytes=read(new GZIPInputStream(new ByteArrayInputStream(bytes)),limit);
        }else if(code.equals("br"))bytes=read(new org.brotli.dec.BrotliInputStream(new ByteArrayInputStream(bytes)),limit);
        else throw new IOException("미지원 Content-Encoding: "+code);
      }
    }
    if(bytes.length>limit)throw new IOException("분석 작업 메모리 한도 · 원본은 History에 보존");
    return HttpText.decode(bytes,type);
  }
  static byte[] read(InputStream input)throws IOException{
    return read(input,WORK_LIMIT);
  }
  static byte[] read(InputStream input,int limit)throws IOException{
    try(input;ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buffer=new byte[16384];int n;long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted()||System.nanoTime()>end)throw new IOException("압축 해제 중단 · 미분석");if(n>limit-out.size())throw new IOException("압축 해제 메모리 한도 · 미분석");out.write(buffer,0,n);}return out.toByteArray();
    }
  }
}
