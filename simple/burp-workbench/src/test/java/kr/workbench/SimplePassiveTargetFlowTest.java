package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Target preparation is visible, recoverable and separate from collection and HTTP. */
public final class SimplePassiveTargetFlowTest extends WorkbenchTest {
  private static Component named(Component root,String name) {
    if(name.equals(root.getName()))return root;
    if(root instanceof Container parent)for(Component child:parent.getComponents()){
      Component found=named(child,name);if(found!=null)return found;
    }
    return null;
  }
  private static JButton button(Component root,String text) {
    if(root instanceof JButton b&&text.equals(b.getText()))return b;
    if(root instanceof Container parent)for(Component child:parent.getComponents()){
      JButton found=button(child,text);if(found!=null)return found;
    }
    return null;
  }
  private static void layout(Component component) {
    if(component instanceof Container parent){parent.doLayout();for(Component child:parent.getComponents())layout(child);}
  }
  private static void settle(JPanel host)throws Exception {
    edt(()->{});edt(()->{for(int i=0;i<4;i++)layout(host);});edt(()->{});
  }
  private static boolean inline(JTextArea feedback,Component content,JPanel host) {
    if(!SwingUtilities.isDescendingFrom(feedback,content)||feedback.getText().isBlank())return false;
    for(Component c=feedback;c!=null;c=c.getParent()){
      if(!c.isVisible())return false;if(c==host)break;
    }
    Rectangle visible=feedback.getVisibleRect();return visible.width>=120&&visible.height>=20;
  }
  private static String summary(PassiveScanWorkspace workspace) {
    try{return ((JLabel)get(workspace,"targetSummary")).getText();}
    catch(Exception error){throw new IllegalStateException(error);}
  }
  private static TargetWindow settings(PassiveScanWorkspace workspace) {
    try{return (TargetWindow)get(workspace,"targetSettings");}
    catch(Exception error){throw new IllegalStateException(error);}
  }

  public static void main(String[] args)throws Exception {
    AtomicInteger sent=new AtomicInteger(),opened=new AtomicInteger();
    ObjectFactoryLocator.FACTORY=proxy(MontoyaObjectFactory.class,(p,m,a)->def(m));
    Http http=proxy(Http.class,(p,m,a)->{if(m.getName().equals("sendRequest"))sent.incrementAndGet();return def(m);});
    MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->m.getName().equals("http")?http:def(m));
    AtomicReference<PassiveScanWorkspace> reference=new AtomicReference<>();
    AtomicReference<Component> content=new AtomicReference<>();
    AtomicReference<JPanel> surface=new AtomicReference<>();
    Diagnostics diagnostics=new Diagnostics();
    try {
      edt(()->{
        var workspace=new PassiveScanWorkspace(api);reference.set(workspace);Diagnostics.bind(workspace,diagnostics);
        settings(workspace).opener=(owner,title,body,modal,closed)->{
          check(!modal,"passive target preparation remains modeless");opened.incrementAndGet();content.set(body);
          JPanel host=WorkbenchUi.settingsBody(body,()->{});host.setSize(760,480);surface.set(host);return null;
        };
        PassiveScanPanel active=workspace.open("https://active.test/read",false);
        active.add("https://active.test/retained","Burp History GET");
        active.packets.put("https://active.test/retained",new PassiveScanPanel.Packet("EXACT_REQUEST","EXACT_RESPONSE","EXACT_RESPONSE"));
        active.table.setRowSelectionInterval(0,0);
        ((AbstractButton)named(workspace,"wb.target.settings")).doClick(0);
      });
      PassiveScanWorkspace workspace=reference.get();settle(surface.get());
      JTextArea feedback=(JTextArea)named(content.get(),"wb.passive.target.feedback");
      JButton prepare=(JButton)named(content.get(),"wb.target.prepare");
      PassiveScanPanel active=(PassiveScanPanel)workspace.targets.getSelectedComponent();String activeSummary=summary(workspace);
      edt(()->{
        check(SwingUtilities.isDescendingFrom(workspace.input,content.get())&&!SwingUtilities.isDescendingFrom(workspace.input,workspace),
            "editable target draft stays in the existing target window");
        check(!feedback.isEditable()&&feedback.getLineWrap()&&!feedback.isVisible(),
            "inline feedback wraps literal text and stays hidden before an error");
        check(Arrays.stream(workspace.input.getActionListeners()).anyMatch(listener->listener==prepare.getAction()),
            "Enter and prepare button use the same action");
        workspace.input.setText("bad..test");
        check(summary(workspace).equals(activeSummary)&&workspace.targets.getSelectedComponent()==active,
            "editing an unapplied draft cannot change the active target");
        prepare.doClick(0);
      });settle(surface.get());
      edt(()->{
        check(inline(feedback,content.get(),surface.get())&&feedback.getText().contains("URL / 도메인")
            &&feedback.getText().contains("다시 시도"),
            "invalid target feedback is readable with an input recovery hint inside the current target content");
        check(workspace.input.getText().equals("bad..test")&&workspace.panels.size()==1&&workspace.targets.getSelectedComponent()==active
            &&summary(workspace).equals(activeSummary),"invalid preparation preserves draft and active target");
        check(active.selected().equals("https://active.test/retained")&&active.packets.get(active.selected()).raw().equals("EXACT_RESPONSE"),
            "failed preparation preserves selected URL and its exact retained packet");
        check(diagnostics.size()==1,"visible target failure still reaches shared diagnostics once");
        workspace.input.setText("https://button.test/read");prepare.doClick(0);
        check(workspace.panels.size()==2&&workspace.panels.values().stream().allMatch(panel->panel.job==null),
            "corrected button preparation creates a target without starting collection");
        check(feedback.getText().isEmpty()&&!feedback.isVisible(),"successful preparation clears previous inline error");
        ((AbstractButton)named(workspace,"wb.target.settings")).doClick(0);
        workspace.input.setText("bad..enter");workspace.input.postActionEvent();
      });settle(surface.get());
      edt(()->{
        check(inline(feedback,content.get(),surface.get())&&workspace.input.getText().equals("bad..enter")&&workspace.panels.size()==2,
            "Enter failure uses the same visible validation without applying its draft");
        check(diagnostics.size()==2,"Enter failure is recorded once through the shared action");
        workspace.input.setText("https://enter.test/read");workspace.input.postActionEvent();
        check(workspace.panels.size()==3&&summary(workspace).contains("enter.test")
            &&workspace.panels.values().stream().allMatch(panel->panel.job==null),
            "Enter prepares the corrected target without collection or button fallback");
        for(int i=workspace.panels.size();i<8;i++)workspace.open("https://slot"+i+".test/read",false);
        ((AbstractButton)named(workspace,"wb.target.settings")).doClick(0);
        workspace.input.setText("https://overflow.test/read");
      });
      PassiveScanPanel limitActive=(PassiveScanPanel)workspace.targets.getSelectedComponent();String limitSummary=summary(workspace);
      edt(()->((AbstractButton)named(content.get(),"wb.target.prepare")).doClick(0));settle(surface.get());
      edt(()->{
        check(inline(feedback,content.get(),surface.get())&&feedback.getText().contains("최대 8개"),
            "target limit is explained inline without a tooltip or diagnostic navigation");
        check(workspace.panels.size()==8&&workspace.targets.getSelectedComponent()==limitActive&&summary(workspace).equals(limitSummary)
            &&workspace.input.getText().equals("https://overflow.test/read"),
            "ninth target rejection preserves the eight targets active selection and unapplied draft");
        check(diagnostics.size()==3,"target limit records one failure without replacing existing diagnostics");
        button(workspace,"선택 대상 닫기").doClick(0);
        workspace.input.setText("https://overflow.test/read");((AbstractButton)named(content.get(),"wb.target.prepare")).doClick(0);
        check(workspace.panels.size()==8&&summary(workspace).contains("overflow.test")&&!feedback.isVisible(),
            "closing one target and retrying recovers from the limit");
        check(workspace.panels.values().stream().allMatch(panel->panel.job==null),
            "button Enter and error recovery never start a collector");
      });
      AbstractButton retainedPrepare=(AbstractButton)named(content.get(),"wb.target.prepare");
      int beforeCloseOpens=opened.get(),beforeCloseErrors=diagnostics.size();
      AtomicReference<PassiveScanPanel> lateDirect=new AtomicReference<>();
      AtomicBoolean mutationsOnEdt=new AtomicBoolean(true),monitorDuringRelease=new AtomicBoolean(),holdTimedOut=new AtomicBoolean();
      AtomicInteger targetChanges=new AtomicInteger(),releaseEvents=new AtomicInteger();
      edt(()->{
        workspace.targets.addChangeListener(event->{targetChanges.incrementAndGet();if(!SwingUtilities.isEventDispatchThread())mutationsOnEdt.set(false);});
        active.model.addTableModelListener(event->{
          releaseEvents.incrementAndGet();
          if(!SwingUtilities.isEventDispatchThread())mutationsOnEdt.set(false);
          if(Thread.holdsLock(workspace))monitorDuringRelease.set(true);
        });
      });
      var callers=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"passive-target-lifecycle-test");thread.setDaemon(true);return thread;});
      CountDownLatch heldEdt=new CountDownLatch(1),releaseEdt=new CountDownLatch(1);
      try {
        var offEdtOpen=callers.submit(()->workspace.open("https://active.test/read",false));
        check(offEdtOpen.get(5,TimeUnit.SECONDS)==active&&workspace.targets.getSelectedComponent()==active&&targetChanges.get()>0,
            "off-EDT open returns only after its target selection is applied on EDT");
        edt(()->workspace.input.setText("https://late.test/read"));
        SwingUtilities.invokeLater(()->{
          heldEdt.countDown();
          try{if(!releaseEdt.await(10,TimeUnit.SECONDS))holdTimedOut.set(true);}
          catch(InterruptedException error){Thread.currentThread().interrupt();holdTimedOut.set(true);}
        });
        check(heldEdt.await(5,TimeUnit.SECONDS),"controlled EDT hold starts before queued preparation and unload");
        SwingUtilities.invokeLater(()->retainedPrepare.doClick(0));
        SwingUtilities.invokeLater(()->workspace.input.postActionEvent());
        SwingUtilities.invokeLater(()->lateDirect.set(workspace.open("https://late-direct.test/read",true)));
        var offEdtClose=callers.submit(workspace::close);
        AtomicBoolean closed=(AtomicBoolean)get(workspace,"closed");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!closed.get()&&System.nanoTime()<end)Thread.sleep(1);
        check(closed.get()&&!offEdtClose.isDone(),"off-EDT close invalidates queued actions before waiting for held EDT cleanup");
        releaseEdt.countDown();offEdtClose.get(5,TimeUnit.SECONDS);edt(()->{});
        check(!holdTimedOut.get()&&releaseEvents.get()>0&&mutationsOnEdt.get()&&!monitorDuringRelease.get(),
            "released EDT completes unload without an off-EDT Swing mutation or held workspace monitor");
      } finally {releaseEdt.countDown();callers.shutdownNow();}
      edt(()->{
        check(workspace.panels.isEmpty()&&workspace.targets.getTabCount()==0&&active.closed.get(),
            "close releases targets and queued prepare actions cannot recreate them");
        check(lateDirect.get()==null,"late direct open is rejected even when collection was requested");
        settings(workspace).open();button(workspace,"대상 수집 시작").doClick(0);workspace.close();
        check(opened.get()==beforeCloseOpens&&workspace.panels.isEmpty(),
            "closed settings and repeated close cannot reopen a target surface");
        check(diagnostics.size()==beforeCloseErrors,"discarded closed actions do not emit new validation failures");
      });
      check(sent.get()==0,"target preparation validation Enter recovery and closed actions send zero target HTTP");
    } finally {
      if(reference.get()!=null)edt(reference.get()::close);diagnostics.close();
    }
    System.out.println("SimplePassiveTargetFlowTest: "+checks+" checks passed");
  }
}
