package kr.workbench;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Curated synthetic examples only; never reads the user's requests, browser or filesystem. */
final class VisualGuideCatalog {
  record Step(int number,String title,String description,double x,double y) {}
  record Screen(String id,String feature,String title,String image,List<Step> steps,String notice) {
    public String toString(){return title;}
  }
  private static final List<Screen> SCREENS=load();
  private VisualGuideCatalog() {}
  static List<Screen> all(){return SCREENS;}
  static Screen screen(String id){return SCREENS.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("가이드 화면을 찾을 수 없습니다: "+id));}
  private static List<Screen> load(){
    try(InputStream in=VisualGuideCatalog.class.getResourceAsStream("/guides/catalog.json")){
      if(in==null)throw new IOException("가이드 목차 누락");
      Map<?,?> root=ExposureResearch.map(MinerJson.parse(new String(in.readNBytes(256*1024),StandardCharsets.UTF_8)));
      List<Screen> screens=new ArrayList<>();Set<String> ids=new HashSet<>();
      for(Object entry:ExposureResearch.list(root.get("guides"))){
        Map<?,?> row=ExposureResearch.map(entry);String id=ExposureResearch.str(row.get("id")),feature=ExposureResearch.str(row.get("feature")),image=ExposureResearch.str(row.get("image"));
        if(!id.matches("[a-z][a-z0-9.-]{0,50}")||!ids.add(id)||!image.matches("[a-z0-9-]+\\.png"))throw new IOException("가이드 식별자 형식 오류");
        FeatureGuide.feature(feature);List<Step> steps=new ArrayList<>();
        for(Object item:ExposureResearch.list(row.get("steps"))){Map<?,?> s=ExposureResearch.map(item);int number=((Number)s.get("number")).intValue();double x=((Number)s.get("x")).doubleValue(),y=((Number)s.get("y")).doubleValue();
          if(number!=steps.size()+1||!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw new IOException("가이드 번호/좌표 오류");
          steps.add(new Step(number,ExposureResearch.str(s.get("title")),ExposureResearch.str(s.get("description")),x,y));
        }
        if(steps.isEmpty()||steps.size()>8)throw new IOException("가이드 단계 개수 오류");
        screens.add(new Screen(id,feature,ExposureResearch.str(row.get("title")),image,List.copyOf(steps),ExposureResearch.str(row.get("notice"))));
      }
      if(screens.isEmpty())throw new IOException("가이드 목차가 비어 있습니다.");return List.copyOf(screens);
    }catch(IOException|RuntimeException error){throw new IllegalStateException("가이드 목차를 읽을 수 없습니다.",error);}
  }
  static BufferedImage readImage(Screen screen,boolean dark)throws IOException {
    String resource="/guides/screens/"+(dark?"dark/":"light/")+screen.image();
    try(InputStream input=VisualGuideCatalog.class.getResourceAsStream(resource)){
      if(input==null)throw new IOException("가이드 예시 이미지 누락");
      byte[] data=input.readNBytes(4*1024*1024+1);if(data.length>4*1024*1024)throw new IOException("가이드 이미지 크기 제한");
      try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(data))){
        var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new IOException("가이드 이미지 형식 오류");
        ImageReader reader=readers.next();try{reader.setInput(stream);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<400||height<160||(long)width*height>4000000)throw new IOException("가이드 이미지 해상도 제한");return reader.read(0);}finally{reader.dispose();}
      }
    }
  }
}
