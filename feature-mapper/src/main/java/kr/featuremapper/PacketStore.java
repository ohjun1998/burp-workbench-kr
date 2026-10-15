package kr.featuremapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Raw traffic is kept on a private temporary file, never retained in the row model. */
final class PacketStore implements AutoCloseable {
    record Ref(long offset,int requestBytes,int responseBytes){}
    private final Path path;
    private final RandomAccessFile file;
    private boolean closed;
    PacketStore()throws IOException{
        Path p;
        try{p=Files.createTempFile("feature-mapper-", ".packets",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
        catch(UnsupportedOperationException e){p=Files.createTempFile("feature-mapper-", ".packets");}
        path=p;
        try{file=new RandomAccessFile(p.toFile(),"rw");}
        catch(IOException e){Files.deleteIfExists(p);throw e;}
        p.toFile().deleteOnExit();
    }
    synchronized Ref append(byte[] request,byte[] response)throws IOException{
        ensureOpen();long start=file.length();file.seek(start);
        try{file.write(request);file.write(response);return new Ref(start,request.length,response.length);}
        catch(IOException e){file.setLength(start);throw e;}
    }
    synchronized byte[][] read(Ref ref)throws IOException{
        validate(ref);file.seek(ref.offset());byte[] q=new byte[ref.requestBytes()],s=new byte[ref.responseBytes()];file.readFully(q);file.readFully(s);return new byte[][]{q,s};
    }
    synchronized void validate(Ref r)throws IOException{
        ensureOpen();long n=(long)r.requestBytes()+r.responseBytes();
        if(r.offset()<0||r.requestBytes()<0||r.responseBytes()<0||r.offset()>file.length()||n>file.length()-r.offset())throw new IOException("패킷 저장 위치가 잘못됐습니다.");
    }
    synchronized long bytes()throws IOException{ensureOpen();return file.length();}
    synchronized void copyTo(OutputStream out)throws IOException{ensureOpen();file.seek(0);byte[] buf=new byte[65536];long left=file.length();while(left>0){int n=file.read(buf,0,(int)Math.min(left,buf.length));if(n<0)throw new EOFException();out.write(buf,0,n);left-=n;}}
    synchronized void restore(InputStream in)throws IOException{ensureOpen();file.setLength(0);file.seek(0);byte[] buf=new byte[65536];for(int n;(n=in.read(buf))>=0;)file.write(buf,0,n);}
    Path path(){return path;}
    private void ensureOpen()throws IOException{if(closed)throw new IOException("패킷 저장소가 닫혔습니다.");}
    public synchronized void close()throws IOException{if(closed)return;closed=true;try{file.close();}finally{Files.deleteIfExists(path);}}
}
