package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** User-facing action state and usable editor space; headless Swing, not native Burp. */
public final class UiPanelsOptimizationTest extends WorkbenchTest {
    static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    static JViewport viewport(Component component) { return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, component); }
    public static void main(String[] args) throws Exception {
        BountyFeaturesTest.factory();
        MontoyaApi api = proxy(MontoyaApi.class, (p,m,a) -> def(m));
        WorkbenchScope.install(api).add("https://ui.test/", true);
        HttpRequestResponse first = AuthRecordTest.rr(request("https://ui.test/one?token=local-secret", "GET", true, List.of()), response(200,"{\"a\":1}"));
        HttpRequestResponse second = AuthRecordTest.rr(request("https://ui.test/two", "GET", true, List.of()), response(403,"{\"a\":2}"));
        AtomicReference<AuthComparePanel> auth = new AtomicReference<>();
        AtomicReference<ActivityPanel> activity = new AtomicReference<>();
        AtomicReference<BountySupportPanel> support = new AtomicReference<>();
        AtomicReference<ResponseReconPanel> recon = new AtomicReference<>();
        edt(() -> {
            StructuredComparePanel compare = new StructuredComparePanel(api);
            check(!compare.edit.isEnabled() && !compare.next.isEnabled(), "comparison starts with input actions and no stale navigation");
            compare.selectedTraffic = () -> List.of(first); compare.loadLeft.doClick();
            compare.selectedTraffic = () -> List.of(second); compare.loadRight.doClick(); compare.compare();
            check(compare.leftSource == first && compare.rightSource == second && compare.captureEvidence.isEnabled(), "side-local import buttons preserve original evidence order");
            check(!compare.leftSourceLabels[0].getToolTipText().contains("local-secret"), "compact source tooltip still redacts query values");
            check(compare.edit.isEnabled() && compare.next.isEnabled(), "comparison result enables editing and difference navigation");
            compare.edit.doClick();
            check(!compare.showing && !compare.edit.isEnabled() && !compare.next.isEnabled() && !compare.previous.isEnabled(), "returning to input disables navigation into hidden old results");
            check(compare.leftSource == first && compare.rightSource == second, "changing view alone does not invalidate original evidence");
            compare.compare(); compare.loadSource(true, first);
            check(!compare.next.isEnabled() && !compare.edit.isEnabled(), "loading another source clears stale result actions");
            compare.clearSources();
            check(!compare.captureEvidence.isEnabled() && compare.leftSourceLabels[0].getText().equals("원본 없음"), "reset clears evidence availability and each side source status");

            auth.set(new AuthComparePanel(api, () -> List.of(first)));
            activity.set(new ActivityPanel(api));
            support.set(new BountySupportPanel(api, () -> List.of(first), auth.get(), activity.get()));
            BountySupportPanel bounty = support.get();
            check(!bounty.flowStop.isEnabled() && !bounty.flowCapture.isEnabled(), "idle empty workflow offers no stop or missing-results export");
            check(!bounty.evidenceSave.isEnabled() && !bounty.evidencePreview.isEnabled(), "empty evidence offers no save or preview");
            bounty.captureSelected();
            check(bounty.evidenceSave.isEnabled() && bounty.evidencePreview.isEnabled(), "adding real evidence enables preview and save");
            check(bounty.impact.getLineWrap() && bounty.repro.getLineWrap(), "human-written report fields wrap instead of requiring horizontal scrolling");
            bounty.evidenceClear.doClick();
            check(bounty.evidence.isEmpty() && !bounty.evidenceSave.isEnabled(), "clear action immediately removes stale save availability");

            ResponseReconPanel response = new ResponseReconPanel(api); recon.set(response);
            check(!response.apiDraft.isEnabled() && !response.findingCapture.isEnabled() && !response.flowCapture.isEnabled(), "response actions require a selected usable source");
            response.displayedFindingKeys = List.of("fixture"); response.displayedFindingPackets = Map.of("fixture", first);
            response.findings.addRow(new Object[]{"rule","https://ui.test/one","Header","candidate",1});
            response.findingTable.setRowSelectionInterval(0,0);
            check(response.findingCapture.isEnabled(), "selecting retained passive evidence enables export");
            response.findingTable.clearSelection();
            check(!response.findingCapture.isEnabled() && response.findingDetail.getText().isEmpty(), "clearing passive selection clears its detail and disables export");
            response.findingTable.setRowSelectionInterval(0,0); response.displayedFindingPackets = Map.of(); response.refreshActions();
            check(!response.findingCapture.isEnabled(), "expired source cannot advertise evidence export");
        });
        Path imageA=Files.createTempFile("ui-image-a-",".png"), imageB=Files.createTempFile("ui-image-b-",".png");
        try {
            var pixels=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
            javax.imageio.ImageIO.write(pixels,"png",imageA.toFile());pixels.setRGB(0,0,0xffffff);javax.imageio.ImageIO.write(pixels,"png",imageB.toFile());
            edt(() -> {
                var bounty=support.get();bounty.attachBrowserEvidence(imageA,null);
                check(bounty.evidence.isEmpty() && bounty.evidenceClear.isEnabled() && !bounty.evidenceSave.isEnabled(), "image-only evidence can be cleared without advertising an empty packet ZIP");
                bounty.evidenceClear.doClick();
                check(bounty.browserImage==null && bounty.browserImageSha256==null && !bounty.evidenceClear.isEnabled(), "clearing image-only evidence removes its hash and resets button state");
                bounty.attachBrowserEvidence(imageB,null);
                check(imageB.equals(bounty.browserImage) && bounty.evidenceClear.isEnabled(), "another browser image can be attached after clearing the first");
                bounty.evidenceClear.doClick();
            });
        } finally { Files.deleteIfExists(imageA);Files.deleteIfExists(imageB); }
        for (int width : new int[]{1024,1280}) {
            edt(() -> { var panel=support.get();panel.pages.setSelectedIndex(1);panel.setSize(width,720);layout(panel); });
            edt(() -> layout(support.get()));
            edt(() -> {
                var panel=support.get();
                check(viewport(panel.workflow).getWidth()>=300 && viewport(panel.workflow).getHeight()>=150, "workflow editor remains usable at "+width);
                check(viewport(panel.workflowDetail).getWidth()>=280 && viewport(panel.workflowDetail).getHeight()>=150, "selected response remains visible below results at "+width);
                check(viewport(panel.flowTable).getHeight()>=100, "result table retains multiple readable rows at "+width);
            });
        }
        CountDownLatch finish = new CountDownLatch(1), started = new CountDownLatch(1);
        edt(() -> support.get().task(() -> { started.countDown(); try { finish.await(5, TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }));
        check(started.await(5,TimeUnit.SECONDS), "fixture worker started");
        edt(() -> check(!support.get().flowRun.isEnabled() && support.get().flowStop.isEnabled(), "busy worker disables duplicate run and offers stop"));
        finish.countDown(); BountyFeaturesTest.idle(support.get());
        edt(() -> {
            check(support.get().flowRun.isEnabled() && !support.get().flowStop.isEnabled(), "completion restores idle action state");
            recon.get().close(); support.get().close(); auth.get().close(); activity.get().close();
        });
        WorkbenchScope.release(api);
        System.out.println("UiPanelsOptimizationTest: "+checks+" checks passed");
    }
}
