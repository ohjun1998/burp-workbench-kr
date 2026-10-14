package kr.workbench;

import static kr.workbench.BountyFeaturesTest.*;
import burp.api.montoya.MontoyaApi;
import javax.swing.*;
import java.util.Set;

/** Selection guards and contextual toolbars must preserve working completion flows. */
public final class AssessmentUiOptimizationTest extends WorkbenchTest {
 public static void main(String[] args)throws Exception {
  factory();MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->def(m));
  edt(()->{
   ActivityPanel panel=new ActivityPanel(api);panel.timer.stop();
   try {
    AssessmentPanel ui=panel.assessmentPanel;
    check(!panel.finish.isEnabled()&&!panel.finishParam.isEnabled()&&!panel.draft.isEnabled(),"empty URL list disables selection actions");
    check(!panel.resolve.isEnabled()&&!panel.sourceButton.isEnabled()&&!panel.pendingResolved.isEnabled(),"unresolved controls wait for a selected JS discovery");
    check(!ui.accountEdit.isEnabled()&&!ui.accountBind.isEnabled()&&!ui.operationFinish.isEnabled(),"empty accounts disable account and operation actions");
    check(!ui.taskEdit.isEnabled()&&!ui.taskFinish.isEnabled()&&!ui.taskRemove.isEnabled(),"empty tasks disable task actions");
    check(SwingUtilities.isDescendingFrom(panel.finish,panel.coveragePages.getComponentAt(0))&&SwingUtilities.isDescendingFrom(panel.filter,panel.coveragePages.getComponentAt(0)),"URL actions and filter belong to the URL tab rather than every coverage page");
    panel.tracker.discover(new ReconMetadata.Discovery("https://auth.test/items?id=1","GET",Set.of("id"),Set.of(),"UI fixture"));
    String alice=ui.tracker.register(new AssessmentTracker.AccountInput("Alice","https://auth.test","USER","A",false));ui.tracker.observed(alice,"GET","https://auth.test/items?id=1",Set.of("URL:id"),"UI fixture");panel.refresh();ui.selectAccount(alice);
    check(ui.accountEdit.isEnabled()&&ui.accountBind.isEnabled()&&!ui.operationFinish.isEnabled(),"account actions available before selecting an API");
    panel.table.setRowSelectionInterval(0,0);
    check(panel.finish.isEnabled()&&panel.draft.isEnabled()&&!panel.finishParam.isEnabled(),"URL selection enables URL actions but requires an input selection separately");
    panel.parameterTable.setRowSelectionInterval(0,0);panel.finishParam.doClick();
    check(panel.finishParam.isEnabled()&&panel.finishParam.getText().equals("입력 완료 해제"),"completed input stays selected and offers explicit undo");
    panel.finishParam.doClick();
    check(panel.finishParam.getText().equals("입력 완료"),"input undo restores the completion action");
    panel.finish.doClick();check(panel.finish.getText().equals("URL 완료 해제"),"URL completion action describes its current effect");
    panel.search.setText("no-matching-url");check(!panel.finish.isEnabled()&&!panel.finishParam.isEnabled(),"filtering away the selected URL clears unsafe selection actions");panel.search.setText("");
    ui.accountTable.setRowSelectionInterval(0,0);check(ui.operationFinish.isEnabled()&&!ui.inputFinish.isEnabled()&&!ui.accountTarget.isEnabled(),"account API selection waits for an input or existing task as appropriate");
    ui.inputTable.setRowSelectionInterval(0,0);ui.inputFinish.doClick();
    check(ui.inputTable.getSelectedRow()>=0&&ui.inputFinish.isEnabled()&&ui.inputModel.getValueAt(0,1).equals("점검 완료"),"account input completion preserves selection for quick undo");
    String task=ui.tracker.createTask("검토","",Set.of(alice),false);ui.tracker.addTarget(task,"GET","https://auth.test/items?id=1");ui.refresh();
    check(ui.accountTarget.isEnabled(),"new task enables adding the selected account API");
    ui.taskTable.setRowSelectionInterval(0,0);check(ui.taskEdit.isEnabled()&&ui.taskFinish.isEnabled()&&!ui.taskRemove.isEnabled(),"task selection keeps valid completion flow available and waits for a detail before removing APIs");
    ui.detailTable.setRowSelectionInterval(0,0);check(ui.taskRemove.isEnabled()&&ui.taskTarget.isEnabled(),"detail selection enables the related API actions");
    ui.taskTable.clearSelection();check(!ui.taskEdit.isEnabled()&&!ui.taskRemove.isEnabled(),"clearing task selection clears detail action availability");
    ui.accountTable.clearSelection();check(!ui.operationFinish.isEnabled()&&!ui.inputFinish.isEnabled(),"clearing account API selection clears input action availability");
   } finally {panel.close();}
  });
  System.out.println("AssessmentUiOptimizationTest: "+checks+" checks passed");
 }
}
