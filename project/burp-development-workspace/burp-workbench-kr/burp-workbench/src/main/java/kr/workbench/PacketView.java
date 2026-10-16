package kr.workbench;
import javax.swing.*;import java.awt.*;
/** Read-only structured packet preview, separate from transmission bytes. */
public final class PacketView extends JPanel {
 public final JTextArea text=new JTextArea();public final JCheckBox formatted=new JCheckBox("데이터 정렬",true);private String raw="",display="";
 public PacketView(){super(new BorderLayout(0,4));setName("wb.packet.preview");text.setEditable(false);text.setLineWrap(false);text.setWrapStyleWord(false);add(WorkbenchUi.row(formatted,new JLabel("문자 복원·정렬은 표시 전용 · 해제하면 원문")),BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(text);scroll.setRowHeaderView(new Lines());add(scroll);formatted.addActionListener(e->refresh());WorkbenchUi.style(this);}
 public void setPacket(String value){setPacket(value,value);}
 public void setPacket(String original,String decoded){raw=bounded(original==null?"패킷 없음":original);display=bounded(decoded==null?raw:decoded);refresh();}
 private final class Lines extends JComponent {
  Lines(){setPreferredSize(new Dimension(40,1));text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){void update(){setPreferredSize(new Dimension(40,Math.max(1,text.getPreferredSize().height)));revalidate();repaint();}public void insertUpdate(javax.swing.event.DocumentEvent e){update();}public void removeUpdate(javax.swing.event.DocumentEvent e){update();}public void changedUpdate(javax.swing.event.DocumentEvent e){update();}});}
  @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.surface());g.fillRect(0,0,getWidth(),getHeight());g.setColor(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));g.setFont(text.getFont());FontMetrics fm=g.getFontMetrics();int h=fm.getHeight(),top=text.getInsets().top;Rectangle clip=g.getClipBounds();int first=Math.max(0,(clip.y-top)/h),last=Math.min(text.getLineCount(),(clip.y+clip.height-top)/h+1);for(int line=first;line<last;line++){String number=Integer.toString(line+1);g.drawString(number,getWidth()-10-fm.stringWidth(number),top+line*h+fm.getAscent());}g.dispose();}
 }
 private String bounded(String s){return s.length()>65536?s.substring(0,65536)+"\n[미리보기 64K자 제한]":s;}
 private void refresh(){text.setText(formatted.isSelected()?PacketFormat.pretty(display):raw);text.setFont(WorkbenchUi.textFont(text.getText()));text.setCaretPosition(0);}
}
