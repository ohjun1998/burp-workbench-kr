package kr.workbench;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Review-only Swing overlays on synthetic BEFORE screenshots; never changes a target or source PNG. */
public final class CleanupReviewPreview {
  static Map<?,?> map(Object value){return value instanceof Map<?,?> row?row:Map.of();}
  static String text(Map<?,?> row,String name){return Objects.toString(row.get(name),"");}
  static boolean done(Map<?,?> row){return text(row,"status").equals("implemented")||text(row,"status").startsWith("approved");}
  static double number(Map<?,?> row,String name){return ((Number)row.get(name)).doubleValue();}
  static final Color RED=new Color(231,53,67);
  static void layout(Component c){if(c instanceof Container p){p.doLayout();for(Component child:p.getComponents())layout(child);}}
  static JTextArea paragraph(String value,int rows){JTextArea area=new JTextArea(value,rows,25);area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setFont(WorkbenchUi.font().deriveFont(14f));area.setBackground(WorkbenchUi.surface());area.setForeground(WorkbenchUi.ink());area.setBorder(null);return area;}
  static final class ReviewImage extends JComponent {
    final BufferedImage image;final List<Map<?,?>> marks;
    ReviewImage(BufferedImage image,List<Map<?,?>> marks){this.image=image;this.marks=marks;setPreferredSize(new Dimension(1600,1000));}
    protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.drawImage(image,0,0,getWidth(),getHeight(),null);
      for(int i=0;i<marks.size();i++){Map<?,?> mark=marks.get(i);int x=(int)Math.round(number(mark,"x")*getWidth()),y=(int)Math.round(number(mark,"y")*getHeight()),w=(int)Math.round(number(mark,"width")*getWidth()),h=(int)Math.round(number(mark,"height")*getHeight());
        g.setColor(new Color(231,53,67,28));g.fillRoundRect(x,y,w,h,6,6);g.setColor(RED);g.setStroke(done(mark)?new BasicStroke(3):new BasicStroke(3,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,10,new float[]{9,6},0));g.drawRoundRect(x,y,w,h,6,6);
        int bx=Math.max(0,Math.min(getWidth()-27,x-12)),by=Math.max(0,y-12);g.fillRoundRect(bx,by,27,27,8,8);g.setColor(Color.WHITE);g.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,17f));String label=String.valueOf((char)('A'+i));g.drawString(label,bx+(27-g.getFontMetrics().stringWidth(label))/2,by+20);
      }g.dispose();
    }
  }
  public static void main(String[] args)throws Exception {
    Path repo=Path.of(args[0]),json=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);
    Map<?,?> doc=map(MinerJson.parse(Files.readString(json)));List<?> common=(List<?>)doc.get("commonCandidates");List<?> screens=(List<?>)doc.get("screens");
    for(Object item:screens){Map<?,?> screen=map(item);List<Map<?,?>> marks=new ArrayList<>();for(Object c:common)marks.add(map(c));for(Object c:(List<?>)screen.get("candidates"))marks.add(map(c));
      BufferedImage source=ImageIO.read(repo.resolve(text(screen,"sourceImage")).toFile());JPanel root=new JPanel(new BorderLayout());root.setBackground(WorkbenchUi.background());
      JLabel heading=new JLabel("  화면 정리 검토 · "+text(screen,"title")+"  |  적용 전 예시");heading.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,21f));heading.setForeground(WorkbenchUi.ink());heading.setPreferredSize(new Dimension(2000,48));root.add(heading,BorderLayout.NORTH);
      JPanel main=new JPanel(new BorderLayout(12,0));main.add(new ReviewImage(source,marks));JPanel legend=new JPanel();legend.setLayout(new BoxLayout(legend,BoxLayout.Y_AXIS));legend.setBackground(WorkbenchUi.background());legend.setPreferredSize(new Dimension(390,1000));legend.setBorder(BorderFactory.createEmptyBorder(10,6,10,12));
      for(int i=0;i<marks.size();i++){Map<?,?> mark=marks.get(i);JPanel card=new JPanel(new BorderLayout(0,7));card.setBackground(WorkbenchUi.surface());card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkbenchUi.line()),BorderFactory.createEmptyBorder(10,12,10,12)));
        JLabel label=new JLabel((char)('A'+i)+" · "+(done(mark)?"삭제 반영":"검토 후보"));label.setFont(WorkbenchUi.font().deriveFont(Font.BOLD,16f));label.setForeground(RED);card.add(label,BorderLayout.NORTH);
        String body=text(mark,"label")+"\n\n"+text(mark,"reason");JTextArea detail=paragraph(body,7);card.add(detail);card.setMaximumSize(new Dimension(Integer.MAX_VALUE,240));legend.add(card);legend.add(Box.createVerticalStrut(10));
      }main.add(legend,BorderLayout.EAST);root.add(main);JLabel footer=new JLabel("  실선: 이번 버전 삭제 반영   ·   점선: 추가 정리 후보   ·   합성 데이터의 적용 전 화면을 사용했습니다.");footer.setFont(WorkbenchUi.font().deriveFont(14f));footer.setForeground(WorkbenchUi.ink());footer.setPreferredSize(new Dimension(2000,34));root.add(footer,BorderLayout.SOUTH);
      SwingUtilities.invokeAndWait(()->{root.setSize(2000,1082);for(int i=0;i<6;i++)layout(root);});BufferedImage picture=new BufferedImage(2000,1082,BufferedImage.TYPE_INT_RGB);SwingUtilities.invokeAndWait(()->{Graphics2D g=picture.createGraphics();root.paint(g);g.dispose();});ImageIO.write(picture,"png",output.resolve(text(screen,"id").replace('.','-')+".png").toFile());
    }
    System.out.println("CleanupReviewPreview: "+screens.size()+" BEFORE-screen review overlays; solid=implemented, dashed=optional; no target HTTP");
  }
}
