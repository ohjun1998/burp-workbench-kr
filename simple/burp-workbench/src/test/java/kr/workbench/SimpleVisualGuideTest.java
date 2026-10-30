package kr.workbench;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Packaged local illustrations, real numbered UI and async resource lifecycle; no target API. */
public final class SimpleVisualGuideTest extends WorkbenchTest {
  private static void await(BooleanSupplier ready, String reason) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      boolean[] done = {false}; edt(() -> done[0] = ready.getAsBoolean());
      if (done[0]) return;
      Thread.sleep(15);
    }
    throw new AssertionError("Guide image wait: " + reason);
  }
  private static void layout(VisualGuidePanel panel) {
    panel.setSize(1340, 900);
    for (int pass = 0; pass < 8; pass++) UiLayoutTest.layout(panel);
  }
  private static int signature(BufferedImage image) {
    return Arrays.hashCode(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
  }
  private static Map<?, ?> cache(VisualGuidePanel panel) {
    try { return (Map<?, ?>) get(panel, "images"); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }
  public static void main(String[] args) throws Exception {
    List<VisualGuideCatalog.Screen> catalog = VisualGuideCatalog.all();
    Set<String> expected = Set.of("traffic", "mapper", "js", "environment", "exposure.search", "exposure.cve",
        "auth", "response.recon", "payload", "data", "activity", "bounty", "dashboard", "payload.results", "bounty.replay", "auth.accounts");
    check(catalog.stream().map(VisualGuideCatalog.Screen::id).collect(java.util.stream.Collectors.toSet()).equals(expected),
        "catalog covers every menu plus both CVE tabs and the major account, payload and replay screens");
    for (VisualGuideCatalog.Screen screen : catalog) {
      check(!screen.title().isBlank() && !screen.notice().isBlank() && screen.steps().size() == 6,
          "screen has its own title, six steps and execution notice: " + screen.id());
      for (boolean dark : new boolean[]{false, true}) {
        BufferedImage image = VisualGuideCatalog.readImage(screen, dark);
        check(image != null && image.getWidth() >= 400 && image.getHeight() >= 160,
            "packaged example decodes in " + (dark ? "dark" : "light") + " theme: " + screen.id());
        for (int i = 0; i < screen.steps().size(); i++) {
          VisualGuideCatalog.Step step = screen.steps().get(i);
          check(step.number() == i + 1 && !step.title().isBlank() && !step.description().isBlank(),
              "visible step number/title/explanation correspond: " + screen.id() + "/" + step.number());
          check(Double.isFinite(step.x()) && Double.isFinite(step.y()) && step.x() > 0 && step.x() < 1 && step.y() > 0 && step.y() < 1,
              "marker is inside its actual source image: " + screen.id() + "/" + step.number());
          for (int j = 0; j < i; j++) {
            VisualGuideCatalog.Step other = screen.steps().get(j);
            check(Math.hypot((step.x() - other.x()) * image.getWidth(), (step.y() - other.y()) * image.getHeight()) > 40,
                "source-sized numbered badges do not overlap: " + screen.id() + "/" + other.number() + ":" + step.number());
          }
        }
      }
    }

    AtomicReference<VisualGuidePanel> reference = new AtomicReference<>();
    edt(() -> reference.set(new VisualGuidePanel()));
    VisualGuidePanel panel = reference.get();
    try {
      edt(() -> panel.select("auth"));
      await(() -> panel.imageReady() && panel.selectedId().equals("auth"), "initial auth resource");
      edt(() -> {
        WorkbenchUi.style(panel); layout(panel);
        check(panel.screens.getItemCount() == catalog.size() && panel.zoom.getItemCount() == 2,
            "guide exposes complete screen choices and fit/100 percent modes");
        check(SimpleWorkflowTest.named(panel, "wb.visual.guide.text") == null,
            "numbered guide does not retain a duplicate legacy text-help button");
        check(panel.notice.getText().equals(VisualGuideCatalog.screen("auth").notice()),
            "selected tool's execution notice is visible in the illustrated guide");
        check(panel.selectedStep() == 1, "new screen begins at its first ordered usage step");
        Rectangle bounds = panel.canvas.imageBounds();
        check(bounds.width > 400 && bounds.height > 200 && bounds.x >= 0 && bounds.y >= 0
                && bounds.x + bounds.width <= panel.canvas.getWidth() && bounds.y + bounds.height <= panel.canvas.getHeight(),
            "fit mode retains the entire source image inside the image viewport");
        BufferedImage capture = new BufferedImage(panel.canvas.getWidth(), panel.canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = capture.createGraphics(); panel.canvas.paint(graphics); graphics.dispose();
        for (VisualGuideCatalog.Step step : VisualGuideCatalog.screen("auth").steps()) {
          Point point = panel.canvas.markerCenter(step.number());
          check(capture.getRGB(point.x + 10, point.y) == VisualGuidePanel.NUMBER_RED.getRGB(),
              "real canvas paints red numbered marker " + step.number());
          check(SimpleWorkflowTest.named(panel, "wb.visual.guide.step." + step.number()) != null,
              "matching explanation card exists for marker " + step.number());
        }
        panel.selectStep(6);
        Component selected = SimpleWorkflowTest.named(panel, "wb.visual.guide.step.6");
        check(panel.selectedStep() == 6 && selected.getBackground().equals(WorkbenchUi.selected()),
            "step selection visibly highlights its explanation card");
        Point first = panel.canvas.markerCenter(1);
        panel.canvas.dispatchEvent(new MouseEvent(panel.canvas, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
            first.x, first.y, 1, false, MouseEvent.BUTTON1));
        check(panel.selectedStep() == 1, "clicking a red screen marker selects the matching explanation");
        selected.dispatchEvent(new MouseEvent(selected, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
            12, 12, 1, false, MouseEvent.BUTTON1));
        check(panel.selectedStep() == 6, "clicking an explanation selects its screen marker");
        panel.selectStep(99); check(panel.selectedStep() == 6, "invalid step cannot desynchronize screenshot and explanation");
        panel.zoom.setSelectedIndex(1); layout(panel);
        check(!panel.canvas.getScrollableTracksViewportWidth() && !panel.canvas.getScrollableTracksViewportHeight()
                && panel.canvas.getPreferredSize().width > panel.imageScroll.getViewport().getWidth(),
            "100 percent retains source resolution with scrollable image dimensions");
        panel.zoom.setSelectedIndex(0); layout(panel);
        check(panel.canvas.getScrollableTracksViewportWidth() && panel.canvas.getScrollableTracksViewportHeight(),
            "fit mode restores viewport tracking without rebuilding guide content");
      });
      for (String id : List.of("traffic", "mapper", "payload", "exposure.search", "exposure.cve")) {
        edt(() -> panel.select(id)); await(() -> panel.imageReady() && panel.selectedId().equals(id), id);
        edt(() -> check(cache(panel).size() <= 3, "decoded-image cache remains bounded after " + id));
      }
      String finalId = "auth.accounts";
      edt(() -> { for (String id : List.of("data", "payload.results", "bounty.replay", finalId)) panel.select(id); });
      await(() -> panel.imageReady() && panel.selectedId().equals(finalId), "rapid final selection");
      BufferedImage expectedImage = VisualGuideCatalog.readImage(VisualGuideCatalog.screen(finalId), WorkbenchUi.dark());
      edt(() -> {
        check(panel.canvas.imageKey.startsWith(VisualGuideCatalog.screen(finalId).image() + "|")
                && signature(panel.canvas.image) == signature(expectedImage),
            "late image workers cannot replace the final selected screen with a previous image");
        check(panel.selectedStep() == 1 && panel.notice.getText().equals(VisualGuideCatalog.screen(finalId).notice()),
            "rapid screen choice resets steps and notice together");
        panel.select("traffic"); panel.close();
      });
      edt(() -> {});
      edt(() -> {
        String closedId = panel.selectedId(); panel.select("mapper"); panel.selectStep(4);
        check(!panel.imageReady() && cache(panel).isEmpty() && panel.selectedId().equals(closedId),
            "close clears image memory and rejects queued or later screen changes");
        check(!panel.screens.isEnabled() && !panel.zoom.isEnabled() && !panel.maximize.isEnabled(),
            "closed guide controls cannot start another image read");
      });
    } finally { edt(panel::close); }
    Color previousPanel=UIManager.getColor("Panel.background");
    AtomicReference<VisualGuidePanel> darkReference=new AtomicReference<>();
    try {
      UIManager.put("Panel.background",new Color(18,22,29));
      edt(()->darkReference.set(new VisualGuidePanel()));
      VisualGuidePanel darkPanel=darkReference.get();
      for(String id:List.of("traffic","auth","exposure.cve")){
        edt(()->darkPanel.select(id));await(()->darkPanel.imageReady()&&darkPanel.selectedId().equals(id),"dark dynamic guide");
        edt(()->{for(int number=1;number<=6;number++){
          var body=(JTextArea)SimpleWorkflowTest.named(darkPanel,"wb.visual.guide.step."+number+".text");
          check(body.getForeground().equals(WorkbenchUi.ink()),"new dark explanation remains readable without a later external style pass: "+id+" step"+number);
        }});
      }
    } finally {if(darkReference.get()!=null)edt(darkReference.get()::close);UIManager.put("Panel.background",previousPanel);}
    System.out.println("SimpleVisualGuideTest: " + checks + " checks passed (local packaged screenshots and real numbered Swing UI)");
  }
}
