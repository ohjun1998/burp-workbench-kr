package kr.workbench;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

public final class GettingStartedPanelTest extends WorkbenchTest {
  public static void main(String[] args) throws Exception {
    SwingUtilities.invokeAndWait(()->{
      AtomicReference<GettingStartedPanel.Readiness> readiness=new AtomicReference<>(new GettingStartedPanel.Readiness(false,false,0,0,0,false));
      List<String> opened=new ArrayList<>();GettingStartedPanel panel=new GettingStartedPanel(readiness::get,opened::add);
      check(panel.summary.getText().contains("0 / 5"),"empty project is not displayed as complete");
      check(panel.states[1].getText().contains("0건")&&panel.states[1].getText().contains("OFF"),"collection readiness distinguishes OFF and no observations");
      for(JButton action:panel.actions)action.doClick();
      check(opened.equals(List.of("scope","traffic","auth.settings","auth","evidence")),"checklist buttons only route to existing setup screens");
      readiness.set(new GettingStartedPanel.Readiness(true,true,3,2,1,true));panel.refresh();
      check(panel.summary.getText().contains("5 / 5"),"observed setup, completed run and actual save event populate all five steps");
      check(panel.states[2].getText().contains("세션·기대 권한"),"account connection does not claim a validated session or policy");
      check(panel.states[3].getText().contains("근거를 검토"),"completed execution is not displayed as security or task completion");
      check(panel.states[4].getText().contains("파일과 내용은 별도로"),"saved event does not claim current file existence or integrity");
      readiness.set(new GettingStartedPanel.Readiness(false,false,0,0,0,false));panel.refresh();
      check(panel.summary.getText().contains("0 / 5"),"project state reset removes previous project's readiness");
      check(panel.actions[0].isEnabled(),"empty project keeps first setup action available");panel.close();
    });
    System.out.println("GettingStartedPanelTest: "+checks+" checks passed");
  }
}
