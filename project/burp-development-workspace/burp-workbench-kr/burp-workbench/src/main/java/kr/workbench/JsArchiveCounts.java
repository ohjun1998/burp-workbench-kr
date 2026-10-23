package kr.workbench;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Disk hash table of finding reference counts; only one fixed-size slot is read at a time. */
final class JsArchiveCounts implements AutoCloseable {
  private static final long MAGIC=0x57424a53434e5432L;
  private static final int HEADER=64,SLOT=48,INITIAL=4096,MAXIMUM=1<<24;
  private final Path path;
  private RandomAccessFile file;
  private int capacity;
  private long occupied;
  private final long[] unique=new long[4];
  JsArchiveCounts(Path path)throws IOException {
    this.path=path;
    if(!Files.exists(path)) {
      try(var out=new RandomAccessFile(path.toFile(),"rw")){out.setLength(HEADER+(long)INITIAL*SLOT);}
      capacity=INITIAL;file=new RandomAccessFile(path.toFile(),"rw");header();
    } else {
      file=new RandomAccessFile(path.toFile(),"rw");
      try {
      if(file.readLong()!=MAGIC)throw new IOException("JS count index format");
      capacity=file.readInt();file.readInt();occupied=file.readLong();
      for(int i=0;i<4;i++)unique[i]=file.readLong();
      if(capacity<INITIAL||capacity>MAXIMUM||Integer.bitCount(capacity)!=1||file.length()!=HEADER+(long)capacity*SLOT)
        throw new IOException("JS count index size");
      } catch(IOException|RuntimeException ex){file.close();throw ex;}
    }
  }
  long[] counts(){return unique.clone();}
  void apply(JsArchive.Summary summary,int delta)throws IOException {
    List<List<String>> lists=List.of(summary.apiKeys(),summary.secretKeys(),summary.domains(),summary.clouds());
    for(int category=0;category<lists.size();category++)for(String value:lists.get(category))change(category,value,delta);
    header();
  }
  boolean contains(int category,String value)throws IOException {
    byte[] hash=HexFormat.of().parseHex(JsMiner.hash(category+"\n"+value)),actual=new byte[32];int start=slot(hash,capacity);
    for(int probe=0;probe<capacity;probe++){int at=(start+probe)&(capacity-1);file.seek(HEADER+(long)at*SLOT);long count=file.readLong();file.readFully(actual);int cat=file.readInt();file.readInt();if(cat==0)return false;if(cat==category+1&&Arrays.equals(hash,actual))return count>0;}return false;
  }
  private void change(int category,String value,int delta)throws IOException {
    byte[] hash=HexFormat.of().parseHex(JsMiner.hash(category+"\n"+value));
    if(delta>0&&occupied*10>=capacity*7L)grow();
    int start=slot(hash,capacity),tombstone=-1;
    byte[] actual=new byte[32];
    for(int probe=0;probe<capacity;probe++) {
      int position=(start+probe)&(capacity-1);file.seek(HEADER+(long)position*SLOT);
      long count=file.readLong();file.readFully(actual);int storedCategory=file.readInt();file.readInt();
      boolean empty=storedCategory==0;
      if(!empty&&storedCategory==category+1&&Arrays.equals(actual,hash)) {
        long next=count+delta;if(next<0)throw new IOException("JS count index reference underflow");
        if(count==0&&next>0)unique[category]++;if(count>0&&next==0)unique[category]--;
        file.seek(HEADER+(long)position*SLOT);file.writeLong(next);return;
      }
      if(!empty&&count==0&&tombstone<0)tombstone=position;
      if(empty) {
        if(delta<0)throw new IOException("JS count index missing finding");
        if(tombstone>=0)position=tombstone;else occupied++;
        write(file,position,hash,category+1,delta);unique[category]++;return;
      }
    }
    throw new IOException("JS count index full");
  }
  private static int slot(byte[] hash,int capacity){return java.nio.ByteBuffer.wrap(hash).getInt()&(capacity-1);}
  private static void write(RandomAccessFile out,int slot,byte[] hash,int category,long count)throws IOException {
    out.seek(HEADER+(long)slot*SLOT);out.writeLong(count);out.write(hash);out.writeInt(category);out.writeInt(0);
  }
  private void grow()throws IOException {
    if(capacity>=MAXIMUM)throw new IOException("JS finding index capacity reached");
    int next=capacity*2;Path temp=path.resolveSibling(path.getFileName()+".grow");long live=0;
    try(var out=new RandomAccessFile(temp.toFile(),"rw")) {
      out.setLength(0);out.setLength(HEADER+(long)next*SLOT);byte[] hash=new byte[32];
      for(int i=0;i<capacity;i++) {
        file.seek(HEADER+(long)i*SLOT);long count=file.readLong();file.readFully(hash);int category=file.readInt();file.readInt();
        if(count<=0||category==0)continue;
        int at=slot(hash,next);
        while(true){out.seek(HEADER+(long)at*SLOT+40);if(out.readInt()==0)break;at=(at+1)&(next-1);}
        write(out,at,hash,category,count);live++;
      }
      out.seek(0);out.writeLong(MAGIC);out.writeInt(next);out.writeInt(0);out.writeLong(live);
      for(long value:unique)out.writeLong(value);
    }
    file.close();
    try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    catch(AtomicMoveNotSupportedException ex){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    file=new RandomAccessFile(path.toFile(),"rw");capacity=next;occupied=live;
  }
  private void header()throws IOException {
    file.seek(0);file.writeLong(MAGIC);file.writeInt(capacity);file.writeInt(0);file.writeLong(occupied);
    for(long value:unique)file.writeLong(value);
  }
  public void close()throws IOException {if(file!=null)file.close();}
}
