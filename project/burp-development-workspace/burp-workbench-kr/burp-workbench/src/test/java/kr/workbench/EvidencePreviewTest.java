package kr.workbench;

import burp.api.montoya.MontoyaApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/** Same packet formatter drives preview and export; capture keeps complete source bytes in a private temporary disk store. */
public final class EvidencePreviewTest extends WorkbenchTest {
 public static void main(String[] args)throws Exception{
  BountyFeaturesTest.factory();
  String q="POST /objects?token=QUERY_SECRET HTTP/1.1\r\nCookie: SID=COOKIE_SECRET\r\nX-Private: CUSTOM_SECRET\r\nContent-Type: application/json\r\n\r\n{\"name\":\"demo\",\"password\":\"BODY_SECRET\"}";
  String s="HTTP/1.1 200 OK\r\nSet-Cookie: SID=NEW_COOKIE\r\nContent-Type: application/json\r\n\r\n{\"title\":\"public evidence\",\"token\":\"TOKEN_SECRET\"}";
  var items=List.of(new BountyEvidence.Item("observed","POST","https://auth.test/objects?token=QUERY_SECRET",q,s,"COOKIE_SECRET reflected"));
  var headers=Set.of("x-private");var secrets=Set.of("SID=COOKIE_SECRET","CUSTOM_SECRET");
  String preview=BountyEvidence.preview("title","impact","steps",items,headers,secrets,true,false);
  for(String value:List.of("QUERY_SECRET","COOKIE_SECRET","CUSTOM_SECRET","BODY_SECRET","TOKEN_SECRET","NEW_COOKIE"))check(!preview.contains(value),"preview excludes "+value);
  check(preview.contains("public evidence")&&preview.contains("demo"),"explicit JSON bodies retain nonsecret evidence");
  check(!BountyEvidence.preview("title","impact","steps",items,headers,secrets,false,false).contains("public evidence"),"body inclusion defaults off");
  check(BountyEvidence.preview("title","impact","steps",items,headers,secrets,false,true).contains("이미지 내용은 자동 가림되지"),"image not represented as automatically redacted");
  Path zip=Files.createTempFile("preview-contract-",".zip");
  try{
   BountyEvidence.save(zip,"title","impact","steps",items,headers,secrets,true,"{}",null);
   try(ZipFile z=new ZipFile(zip.toFile())){for(String name:List.of("requests/1.http","responses/1.http"))check(preview.contains(new String(z.getInputStream(z.getEntry(name)).readAllBytes(),StandardCharsets.UTF_8)),"preview matches saved "+name);}
  }finally{Files.deleteIfExists(zip);}
  Path image=Files.createTempFile("browser-proof-",".png"),imageZip=Files.createTempFile("image-proof-",".zip");
  try{
   var png=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
   javax.imageio.ImageIO.write(png,"png",image.toFile());String digest=Core.hash(Files.readAllBytes(image));
   BountyEvidence.save(imageZip,"title","impact","steps",items,headers,secrets,true,"{}",image,List.of(),digest);
   try(ZipFile z=new ZipFile(imageZip.toFile())){
    check(Core.hash(z.getInputStream(z.getEntry("browser-evidence.png")).readAllBytes()).equals(digest),"ZIP image bytes match attached proof hash");
    check(new String(z.getInputStream(z.getEntry("browser-evidence.json")).readAllBytes(),StandardCharsets.UTF_8).contains(digest),"image hash recorded in evidence");
   }
   byte[] saved=Files.readAllBytes(imageZip);png.setRGB(0,0,0xffffff);javax.imageio.ImageIO.write(png,"png",image.toFile());
   BountyFeaturesTest.fails(()->BountyEvidence.save(imageZip,"title","impact","steps",items,headers,secrets,true,"{}",image,List.of(),digest),"changed browser proof rejected before ZIP write");
   check(Arrays.equals(saved,Files.readAllBytes(imageZip)),"changed proof leaves previous ZIP intact");
  }finally{Files.deleteIfExists(image);Files.deleteIfExists(imageZip);}
  MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->def(m));var scope=WorkbenchScope.install(api);scope.add("https://auth.test/",true);
  var valid=AuthRecordTest.rr(BountyFeaturesTest.rq("https://auth.test/objects","GET","",List.of(header("Cookie","SID=COOKIE_SECRET"))),response(200,"{}"));
  var outside=AuthRecordTest.rr(BountyFeaturesTest.rq("https://other.test/objects","GET","",List.of()),response(200,"{}"));
  AtomicReference<BountySupportPanel> panel=new AtomicReference<>();
  AtomicReference<ActivityPanel> activity=new AtomicReference<>();AtomicReference<AuthComparePanel> auth=new AtomicReference<>();
  edt(()->{
   var a=new AuthComparePanel(api,List::of);var t=new ActivityPanel(api);var b=new BountySupportPanel(api,List::of,a,t);panel.set(b);activity.set(t);auth.set(a);
   rejects(()->b.captureObserved(List.of(valid,outside),"mixed scope"),"scope batch rejected");
   check(b.evidence.isEmpty()&&b.evidenceSecrets.isEmpty(),"mixed scope rejection leaves evidence untouched");
   b.captureObserved(List.of(valid),"source-linked observation");
   check(b.evidence.size()==1&&b.evidence.get(0).request().contains("COOKIE_SECRET"),"source preview loaded from retained disk bytes");
   check(!b.evidencePreview().contains("COOKIE_SECRET"),"connected source preview redacts credentials");
   rejects(()->b.captureObserved(Collections.nCopies(32,valid),"over capacity"),"over capacity rejected");
   check(b.evidence.size()==1,"over capacity rejection is atomic");
   try{
    Path first=Files.createTempFile("flow-image-a-",".png"),second=Files.createTempFile("flow-image-b-",".png");
    try{
     var png=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);javax.imageio.ImageIO.write(png,"png",first.toFile());
     png.setRGB(0,0,0xffffff);javax.imageio.ImageIO.write(png,"png",second.toFile());
     b.captureObservedWithImage(List.of(valid),"flow A",first,Core.hash(Files.readAllBytes(first)));int count=b.evidence.size();
     rejects(()->b.captureObservedWithImage(List.of(valid),"flow B",second,null),"different flow image rejected");
     check(b.evidence.size()==count&&b.browserImage.equals(first),"image conflict adds no packets and preserves original proof");
     rejects(()->b.attachBrowserEvidence(second,null),"manual image replacement cannot contradict captured flow");
     b.captureObservedWithImage(List.of(valid),"same image reused",first,b.browserImageSha256);
     check(b.evidence.size()==count+1,"same validated proof can be reused");
    }finally{Files.deleteIfExists(first);Files.deleteIfExists(second);}
   }catch(Exception ex){throw new RuntimeException(ex);}
   scope.remove(scope.rules().get(0));rejects(()->b.captureObserved(List.of(valid),"scope revoked"),"current scope checked again");
   b.close();a.close();t.close();
   check(b.evidence.isEmpty()&&b.evidenceSecrets.isEmpty(),"unload clears private evidence state");
  });WorkbenchScope.release(api);
  System.out.println("EvidencePreviewTest: "+checks+" checks passed");
 }
}
