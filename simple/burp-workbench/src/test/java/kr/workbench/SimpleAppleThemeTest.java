package kr.workbench;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import kr.packetui.VisualTheme;

/** Readability and local-theme isolation of real painted components in both modes. */
public final class SimpleAppleThemeTest extends WorkbenchTest {
  private static double channel(int value) {
    double v=value/255.0; return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);
  }
  private static double luminance(Color c) {
    return .2126*channel(c.getRed())+.7152*channel(c.getGreen())+.0722*channel(c.getBlue());
  }
  private static double contrast(Color a,Color b) {
    double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);
  }
  private static Color mapper(String method) throws Exception {
    var m=Class.forName("kr.featuremapper.MapperUi").getDeclaredMethod(method);m.setAccessible(true);return (Color)m.invoke(null);
  }
  public static void main(String[] args) throws Exception {
    Color previous=UIManager.getColor("Panel.background");
    try {
      for(boolean dark:new boolean[]{false,true}) {
        edt(()->{
          Color host=dark?new Color(30,30,30):new Color(248,248,248);
          UIManager.put("Panel.background",host);
          JPanel panel=new JPanel();JButton primary=new JButton("비교 준비"),secondary=new JButton("증거 추가");
          JTextField field=new JTextField("UNSAVED 한글 입력",20);
          JTable table=new JTable(new Object[][]{{"original-17","GET"}},new Object[]{"ID","메서드"});
          var model=table.getModel();table.setRowSelectionInterval(0,0);
          panel.add(primary);panel.add(secondary);panel.add(field);panel.add(table);WorkbenchUi.primary(primary);
          for(int pass=0;pass<3;pass++)WorkbenchUi.style(panel);
          check(UIManager.getColor("Panel.background").equals(host),"local style leaves host palette unchanged in "+dark);
          check(field.getText().equals("UNSAVED 한글 입력")&&table.getModel()==model&&table.getSelectedRow()==0,"theme retains authored text, model identity and selection in "+dark);
          for(Color surface:new Color[]{VisualTheme.surface(),VisualTheme.background(),VisualTheme.raised(),VisualTheme.selected(),VisualTheme.sidebar()}) {
            check(contrast(VisualTheme.ink(),surface)>=4.5,"body text contrast on every neutral/selected surface in "+dark);
            check(contrast(VisualTheme.muted(),surface)>=4.5,"secondary labels remain readable in "+dark);
          }
          check(contrast(Color.WHITE,primary.getBackground())>=4.5,"primary white caption contrast in "+dark);
          check(contrast(VisualTheme.accent(),VisualTheme.surface())>=3,"focus and selection accent contrast in "+dark);
          check(!table.getShowVerticalLines()&&table.getShowHorizontalLines(),"table uses horizontal separators in "+dark);
          check(table.getSelectionBackground().equals(VisualTheme.selected()),"actual table uses blue selection in "+dark);
          primary.setSize(150,34);BufferedImage image=new BufferedImage(150,34,BufferedImage.TYPE_INT_RGB);
          Graphics2D g=image.createGraphics();primary.paint(g);g.dispose();
          check(image.getRGB(12,8)==VisualTheme.primary().getRGB()&&image.getRGB(12,25)==VisualTheme.primary().getRGB(),"actual primary button paints the flat blue fill in "+dark);
          primary.setEnabled(false);
          check(!primary.getBackground().equals(VisualTheme.primary()),"disabled primary has neutral styling in "+dark);
        });
        for(String token:new String[]{"background","surface","raised","ink","line","selected","accent"})
          check(mapper(token).equals(VisualTheme.class.getMethod(token).invoke(null)),"embedded Mapper shares "+token+" in "+dark);
      }
    } finally {edt(()->UIManager.put("Panel.background",previous));}
    System.out.println("SimpleAppleThemeTest: "+checks+" checks passed (actual Swing paint/contrast; no host defaults changed by styling)");
  }
}
