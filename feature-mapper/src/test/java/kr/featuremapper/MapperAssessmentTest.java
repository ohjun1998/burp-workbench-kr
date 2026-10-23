package kr.featuremapper;

import burp.api.montoya.MontoyaApi;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Exact feature provenance and a local embedded-host callback; no HTTP is sent. */
final class MapperAssessmentTest {
  static void run() throws Exception {
    try (Mapper mapper = new Mapper(); Mapper another = new Mapper()) {
      var first = mapper.accept(MapperTest.input(1,"GET","https://assessment.test/api/items/12?token=PRIVATE","","{}","SESSION"));
      var duplicate = mapper.accept(MapperTest.input(2,"GET","https://assessment.test/api/items/12?token=OTHER","","{}","SESSION"));
      mapper.move(duplicate.id, first.primary);
      var draft = mapper.assessmentCandidate(first.primary);
      MapperTest.check(draft.endpoints().equals(List.of("GET https://assessment.test/api/items/12")), "assessment draft deduplicates APIs and removes query credentials");
      MapperTest.check(draft.sourceId().startsWith("mapper-") && draft.sourceId().equals(mapper.assessmentCandidate(first.primary).sourceId()), "same feature has a stable opaque assessment source ID");
      mapper.edit(first.primary,"상품 조회","완료","classification only");
      MapperTest.check(mapper.assessmentCandidate(first.primary).title().equals("상품 조회"), "assessment draft uses the latest feature label");
      MapperTest.check(draft.sourceId().equals(mapper.assessmentCandidate(first.primary).sourceId()), "renaming or classifying a feature preserves its task identity");
      var other = another.accept(MapperTest.input(1,"GET","https://assessment.test/api/items/12","","{}","SESSION"));
      MapperTest.check(!draft.sourceId().equals(another.assessmentCandidate(other.primary).sourceId()), "different mapping projects never share task identities");
      var file = Files.createTempFile("mapper-assessment-", ".fmkr");
      try {
        ProjectIO.save(mapper,file);
        try (Mapper loaded=ProjectIO.load(file)) {
          MapperTest.check(draft.sourceId().equals(loaded.assessmentCandidate(first.primary).sourceId()), "save and reload retain feature task identity");
          MapperTest.check(draft.endpoints().equals(loaded.assessmentCandidate(first.primary).endpoints()), "save and reload retain exact API candidates");
        }
      } finally { Files.deleteIfExists(file); }
      boolean absent=false;try {mapper.assessmentCandidate("missing");}catch(IllegalArgumentException expected){absent=true;}
      MapperTest.check(absent && mapper.assessmentSourceId("missing").isEmpty(), "expired features cannot create task drafts");
    }
    AtomicInteger callbacks=new AtomicInteger(), failures=new AtomicInteger();
    AtomicBoolean allowed=new AtomicBoolean(true);
    AtomicReference<String> source=new AtomicReference<>();
    FeatureMapperWorkspace workspace=new FeatureMapperWorkspace();
    int sendsBefore=MapperUiWorkflowTest.sent.get();
    try {
      JPanel panel=workspace.initializeEmbedded((MontoyaApi)MapperUiWorkflowTest.host(MontoyaApi.class),new FeatureMapperWorkspace.EmbeddedHost(){
        public String captureMode(){return "OFF";} public void toggleCapture(){}
        public boolean acceptsUrl(String url){return false;}
        public boolean allowsAssessmentUrl(String url){return allowed.get();}
        public boolean supportsAssessmentTasks(){return true;}
        public void failure(String stage,Exception error){failures.incrementAndGet();}
        public void assessmentDraft(String id,String title,List<String> endpoints){source.set(id);callbacks.incrementAndGet();}
      });
      var packet=workspace.mapper.accept(MapperTest.input(1,"GET","https://assessment.test/api/items/12","","{}","SESSION"));
      Field selected=FeatureMapperWorkspace.class.getDeclaredField("selectedFeature");selected.setAccessible(true);
      SwingUtilities.invokeAndWait(()->{try{selected.set(workspace,packet.primary);}catch(Exception e){throw new RuntimeException(e);}});
      JButton button=(JButton)MapperUiWorkflowTest.named(panel,"fm.assessment.link");
      MapperTest.check(button!=null,"embedded mapping exposes the assessment link action");
      SwingUtilities.invokeAndWait(()->button.doClick(0));await(callbacks,1);
      MapperTest.check(callbacks.get()==1 && workspace.isAssessmentSourceCurrent(source.get()),"stored feature can be linked while traffic capture is OFF");
      allowed.set(false);SwingUtilities.invokeAndWait(()->button.doClick(0));await(failures,1);
      MapperTest.check(callbacks.get()==1 && failures.get()>0,"scope changes block task draft delivery");
      SwingUtilities.invokeAndWait(()->{try{selected.set(workspace,null);}catch(Exception e){throw new RuntimeException(e);}});
      MapperTest.check(!workspace.isAssessmentSourceCurrent(source.get()),"selection changes invalidate an open task review");
      MapperTest.check(MapperUiWorkflowTest.sent.get()==sendsBefore,"mapping task preparation sends no HTTP requests");
    } finally {workspace.close();SwingUtilities.invokeAndWait(()->{});}
    MapperTest.check(!workspace.isAssessmentSourceCurrent(source.get()),"unload invalidates pending mapping drafts");
  }
  private static void await(AtomicInteger value,int minimum)throws Exception {
    long end=System.nanoTime()+5_000_000_000L;
    while(value.get()<minimum&&System.nanoTime()<end){Thread.sleep(10);SwingUtilities.invokeAndWait(()->{});}
    if(value.get()<minimum)throw new AssertionError("mapping callback timed out");
  }
}
