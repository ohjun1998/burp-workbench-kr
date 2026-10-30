package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Frozen before/after observation of target preparation; no Burp desktop or target HTTP. */
public final class PassiveTargetFlowProbe extends WorkbenchTest {
  private static Map<String,Object> map(Object... entries) {
    var result=new LinkedHashMap<String,Object>();
    for(int i=0;i<entries.length;i+=2)result.put(entries[i].toString(),entries[i+1]);
    return result;
  }
  private static Component named(Component root,String name) {
    if(name.equals(root.getName()))return root;
    if(root instanceof Container parent)for(Component child:parent.getComponents()){
      Component found=named(child,name);if(found!=null)return found;
    }
    return null;
  }
  private static AbstractButton button(Component root,String text) {
    if(root instanceof AbstractButton b&&text.equals(b.getText()))return b;
    if(root instanceof Container parent)for(Component child:parent.getComponents()){
      AbstractButton found=button(child,text);if(found!=null)return found;
    }
    return null;
  }
  private static void layout(Component component) {
    if(component instanceof Container parent){parent.doLayout();for(Component child:parent.getComponents())layout(child);}
  }
  private static void settle(JPanel host)throws Exception {
    edt(()->{});edt(()->{for(int i=0;i<4;i++)layout(host);});edt(()->{});
  }
  private static Map<String,Object> feedback(Component body,JPanel host) {
    Component component=named(body,"wb.passive.target.feedback");
    String text=component instanceof JLabel label?label.getText():component instanceof JTextComponent input?input.getText():"";
    boolean descendant=component!=null&&SwingUtilities.isDescendingFrom(component,body);
    boolean visible=descendant&&!text.isBlank();
    for(Component c=component;c!=null&&visible;c=c.getParent()){
      visible=c.isVisible();if(c==host)break;
    }
    Rectangle bounds=component instanceof JComponent j?j.getVisibleRect():new Rectangle();
    return map("namedComponentPresent",component!=null,"belongsToCurrentTargetContent",descendant,
        "visibleInlineError",visible&&bounds.width>=120&&bounds.height>=20,
        "visibleWidth",bounds.width,"visibleHeight",bounds.height,"text",text);
  }
  private static boolean shown(Map<String,Object> evidence){return Boolean.TRUE.equals(evidence.get("visibleInlineError"));}
  private static boolean pending(PassiveScanWorkspace workspace){return workspace.panels.values().stream().anyMatch(panel->panel.job!=null);}
  private static String summary(PassiveScanWorkspace workspace){try{return ((JLabel)get(workspace,"targetSummary")).getText();}catch(Exception error){throw new IllegalStateException(error);}}

  public static void main(String[] args)throws Exception {
    if(args.length<1||args.length>2)throw new IllegalArgumentException("Usage: PassiveTargetFlowProbe output-directory [phase]");
    String phase=args.length==2?args[1]:"unspecified";
    Path product=Path.of(PassiveScanWorkspace.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String jarSHA256=Files.isRegularFile(product)?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(product))):"not-a-jar";
    Path output=Path.of(args[0]);Files.createDirectories(output);
    var result=map("schemaVersion",1,"phase",phase,"productionVersion",BuildInfo.VERSION,
        "jarSHA256",jarSHA256,"startedAtUtc",Instant.now().toString(),"nativeBurp",false,"osClicks",false,
        "fixture","Production headless Swing target settings, Montoya adapter, synthetic retained URL/packet; no HTTP fetch",
        "actionUnit","One explicit control activation after the draft is filled; no optional window closes or estimated mouse/typing counts",
        "productCodeSource",PassiveScanWorkspace.class.getProtectionDomain().getCodeSource().getLocation().toString());
    AtomicInteger sends=new AtomicInteger(),opened=new AtomicInteger();
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    Http http=proxy(Http.class,(p,m,a)->{if(m.getName().equals("sendRequest"))sends.incrementAndGet();return def(m);});
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    Diagnostics diagnostics=new Diagnostics();
    AtomicReference<PassiveScanWorkspace> reference=new AtomicReference<>();
    AtomicReference<Component> content=new AtomicReference<>();
    AtomicReference<JPanel> surface=new AtomicReference<>();
    try {
      edt(()->{
        PassiveScanWorkspace workspace=new PassiveScanWorkspace(api);reference.set(workspace);Diagnostics.bind(workspace,diagnostics);
        try {
          TargetWindow window=(TargetWindow)get(workspace,"targetSettings");
          window.opener=(owner,title,body,modal,closed)->{
            if(modal)throw new AssertionError("target settings must remain modeless");
            opened.incrementAndGet();content.set(body);JPanel host=WorkbenchUi.settingsBody(body,()->{});host.setSize(760,480);surface.set(host);return null;
          };
        }catch(Exception error){throw new IllegalStateException(error);}
        PassiveScanPanel active=workspace.open("https://active.example.test/read",false);
        active.add("https://active.example.test/retained","Burp History GET");
        active.packets.put("https://active.example.test/retained",new PassiveScanPanel.Packet("RETAINED_REQUEST","RETAINED_RESPONSE","RETAINED_RESPONSE"));
        active.table.setRowSelectionInterval(0,0);
        ((AbstractButton)named(workspace,"wb.target.settings")).doClick(0);
      });
      PassiveScanWorkspace workspace=reference.get();settle(surface.get());
      PassiveScanPanel active=(PassiveScanPanel)workspace.targets.getSelectedComponent();
      String activeSummary=summary(workspace),bad="bad..example";
      edt(()->{workspace.input.setText(bad);((AbstractButton)named(content.get(),"wb.target.prepare")).doClick(0);});settle(surface.get());
      var invalidFeedback=feedback(content.get(),surface.get());
      result.put("invalidTarget",map("feedback",invalidFeedback,"draftRetained",workspace.input.getText().equals(bad),
          "activePanelRetained",workspace.targets.getSelectedComponent()==active,"activeSummaryRetained",summary(workspace).equals(activeSummary),
          "retainedPacketUnchanged",active.packets.get("https://active.example.test/retained").raw().equals("RETAINED_RESPONSE"),
          "targetCount",workspace.panels.size(),"diagnosticsRecorded",diagnostics.size()==1));
      edt(()->{workspace.input.setText("https://button.example.test/read");((AbstractButton)named(content.get(),"wb.target.prepare")).doClick(0);});
      boolean buttonReady=workspace.panels.size()==2&&!pending(workspace);
      result.put("buttonRecovery",map("preparedWithoutLeavingTool",buttonReady,"collecting",pending(workspace),"retryControlActivations",1,
          "visibleFailureAndInlineRecovery",shown(invalidFeedback)&&buttonReady));

      edt(()->{((AbstractButton)named(workspace,"wb.target.settings")).doClick(0);workspace.input.setText("https://enter.example.test/read");workspace.input.postActionEvent();});settle(surface.get());
      boolean enterReady=workspace.panels.size()==3&&!pending(workspace);
      int enterActions=1;
      if(!enterReady){edt(()->((AbstractButton)named(content.get(),"wb.target.prepare")).doClick(0));enterActions++;}
      result.put("enterCompletion",map("preparedByEnter",enterReady,"mousePrepareFallbackRequired",!enterReady,
          "controlActivationsForEnterThenFallback",enterActions,"buttonPreparationControlActivations",1,"collecting",pending(workspace)));

      edt(()->{for(int i=workspace.panels.size();i<8;i++)workspace.open("https://slot"+i+".example.test/read",false);
        ((AbstractButton)named(workspace,"wb.target.settings")).doClick(0);workspace.input.setText("https://overflow.example.test/read");});
      PassiveScanPanel beforeLimit=(PassiveScanPanel)workspace.targets.getSelectedComponent();String beforeLimitSummary=summary(workspace);
      edt(()->((AbstractButton)named(content.get(),"wb.target.prepare")).doClick(0));settle(surface.get());
      var limitFeedback=feedback(content.get(),surface.get());
      result.put("targetLimit",map("feedback",limitFeedback,"targetCount",workspace.panels.size(),"activePanelRetained",workspace.targets.getSelectedComponent()==beforeLimit,
          "activeSummaryRetained",summary(workspace).equals(beforeLimitSummary),"draftRetained",workspace.input.getText().equals("https://overflow.example.test/read"),
          "diagnosticsRecorded",diagnostics.size()==2));
      AtomicReference<AbstractButton> retainedPrepare=new AtomicReference<>();
      edt(()->{retainedPrepare.set((AbstractButton)named(content.get(),"wb.target.prepare"));button(workspace,"선택 대상 닫기").doClick(0);
        workspace.input.setText("https://overflow.example.test/read");retainedPrepare.get().doClick(0);});
      result.put("limitRecovery",map("preparedAfterExplicitCloseAndRetry",workspace.panels.size()==8&&summary(workspace).contains("overflow.example.test"),"collecting",pending(workspace)));

      edt(workspace::close);int opensBefore=opened.get();
      edt(()->{try{((TargetWindow)get(workspace,"targetSettings")).open();}catch(Exception error){throw new IllegalStateException(error);}
        workspace.input.setText("https://late-button.example.test/read");retainedPrepare.get().doClick(0);});
      int afterLateButton=workspace.panels.size();
      edt(workspace::close);
      edt(()->workspace.open("https://late-direct.example.test/read",false));int afterLateDirect=workspace.panels.size();
      edt(workspace::close);
      edt(()->{workspace.input.setText("https://late-enter.example.test/read");workspace.input.postActionEvent();});
      result.put("closedWorkspace",map("settingsCannotReopen",opened.get()==opensBefore,
          "retainedPrepareCannotRecreateTargets",afterLateButton==0,"directLateOpenCannotRecreateTargets",afterLateDirect==0,
          "lateEnterCannotRecreateTargets",workspace.panels.isEmpty(),"lateButtonTargetCount",afterLateButton,"lateDirectTargetCount",afterLateDirect));
      result.put("targetHttpRequests",sends.get());result.put("collectingAtEnd",pending(workspace));
      result.put("observedImprovementCriteria",map("invalidErrorVisible",shown(invalidFeedback),"limitErrorVisible",shown(limitFeedback),
          "enterPreparesWithoutMouseFallback",enterReady,"closedActionsCannotResurrectTargets",afterLateButton==0&&afterLateDirect==0&&workspace.panels.isEmpty()));
      result.put("result","OBSERVED");
      if(sends.get()!=0)throw new AssertionError("preparation must send no target HTTP");
    } finally {
      if(reference.get()!=null)edt(reference.get()::close);diagnostics.close();
      result.put("finishedAtUtc",Instant.now().toString());
      Files.writeString(output.resolve("result.json"),MetadataExport.json(result)+"\n",StandardCharsets.UTF_8);
    }
    System.out.println(MetadataExport.json(map("result",result.get("result"),"phase",phase,"targetHttpRequests",sends.get(),"criteria",result.get("observedImprovementCriteria"))));
  }
}
