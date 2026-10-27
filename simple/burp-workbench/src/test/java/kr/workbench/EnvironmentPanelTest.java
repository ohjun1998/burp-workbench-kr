package kr.workbench;
import javax.swing.*;
public final class EnvironmentPanelTest {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 public static void main(String[] args)throws Exception{SwingUtilities.invokeAndWait(()->{
  EnvironmentPanel p=new EnvironmentPanel(null);var site=p.catalog.observe("https://ui.test/","Content-Type: application/vnd.spring-boot.actuator.v3+json","<script src='/wp-content/plugins/woocommerce/main.js'></script>");p.origins.addItem(site.origin);
  check(p.paths.getRowCount()>30,"UI lists multiple ranked candidates");check(p.paths.getColumnCount()==4&&p.paths.getColumnName(0).equals("URL")&&p.paths.getColumnName(3).equals("상태"),"UI removes score condition and priority columns");
  p.limit.setSelectedItem(30);check(p.candidates.size()==30&&p.paths.getRowCount()==30,"UI top 30 selector");p.limit.setSelectedItem(300);check(p.candidates.size()>30,"UI expanded limit refresh");
  p.common.setSelected(false);p.refresh();check(p.candidates.stream().noneMatch(c->c.source().equals("공통 탐색")),"UI common toggle");p.prefix.setText("//evil.test");p.refresh();check(p.candidates.isEmpty()&&p.paths.getRowCount()==0,"invalid prefix leaves no stale Repeater candidates");
 });System.out.println("EnvironmentPanelTest: "+checks+" checks passed");}
}
