package kr.workbench;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.*;

/** Separate headful Windows check; never opens Burp or transmits HTTP. */
public final class JsRepeaterWindowTest {
  static int checks;
  static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
  static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
  static void state(JFrame frame,int expected)throws Exception{
    long deadline=System.nanoTime()+5_000_000_000L;
    do{int[] value={0};edt(()->value[0]=frame.getExtendedState());if((value[0]&Frame.MAXIMIZED_BOTH)==expected)return;Thread.sleep(25);}while(System.nanoTime()<deadline);
    throw new AssertionError("Native window state did not reach "+expected);
  }
  public static void main(String[] args)throws Exception{
    if(GraphicsEnvironment.isHeadless())throw new AssertionError("A native desktop is required for this separate window check");
    JsRepeater[] owner={null};JFrame[] frame={null};JsRepeater.Session[] session={null};
    try{
      edt(()->{
        owner[0]=new JsRepeater(null,null);owner[0].open(List.of(new JsRequestDraft.Draft("https://example.test/api/demo","GET","")));
        check(owner[0].windows.size()==1&&owner[0].windows.get(0) instanceof JFrame,"Repeater opens a standard frame with native caption controls");
        frame[0]=(JFrame)owner[0].windows.get(0);session[0]=owner[0].sessions.get(0);
        check(!frame[0].isUndecorated()&&frame[0].isResizable()&&frame[0].getRootPane().getWindowDecorationStyle()==JRootPane.NONE,"OS decorations and resize/maximize capability remain enabled");
        check(frame[0].getDefaultCloseOperation()==WindowConstants.DISPOSE_ON_CLOSE,"the caption close button disposes only this frame");
        frame[0].setExtendedState(Frame.MAXIMIZED_BOTH);
      });state(frame[0],Frame.MAXIMIZED_BOTH);
      edt(()->{check(session[0].request.text.getText().contains("GET /api/demo"),"native maximize preserves the request editor");frame[0].setExtendedState(Frame.NORMAL);});state(frame[0],0);
      edt(()->{check(frame[0].isDisplayable()&&session[0].request.text.getText().contains("GET /api/demo"),"native restore retains the same Repeater session");frame[0].dispatchEvent(new WindowEvent(frame[0],WindowEvent.WINDOW_CLOSING));});
      edt(()->{});
      edt(()->check(!frame[0].isDisplayable()&&session[0].closed&&session[0].worker.isShutdown()&&owner[0].windows.isEmpty()&&owner[0].sessions.isEmpty(),"caption close releases the window, session, shortcuts and worker"));
    }finally{if(owner[0]!=null)edt(owner[0]::close);}
    System.out.println("JsRepeaterWindowTest: "+checks+" checks passed (native Swing frame; no Burp or HTTP)");
  }
}
