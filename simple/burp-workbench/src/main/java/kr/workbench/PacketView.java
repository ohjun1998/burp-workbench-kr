package kr.workbench;
import javax.swing.*;import java.awt.*;
/** Read-only structured packet preview, separate from transmission bytes. */
public final class PacketView extends JPanel {
 public final JTextArea text=new JTextArea();public final JCheckBox formatted=new JCheckBox("데이터 정렬",true);private String raw="",display="";
 public PacketView(){super(new BorderLayout(0,4));setName("wb.packet.preview");text.putClientProperty("wb.packet.syntax",true);text.setEditable(false);text.setLineWrap(false);text.setWrapStyleWord(false);JToggleButton pretty=new JToggleButton("Pretty",true),rawTab=new JToggleButton("Raw");ButtonGroup modes=new ButtonGroup();modes.add(pretty);modes.add(rawTab);pretty.addActionListener(e->{if(!formatted.isSelected())formatted.doClick();});rawTab.addActionListener(e->{if(formatted.isSelected())formatted.doClick();});formatted.addItemListener(e->{pretty.setSelected(formatted.isSelected());rawTab.setSelected(!formatted.isSelected());});for(JToggleButton b:new JToggleButton[]{pretty,rawTab})b.putClientProperty("wb.compact.button",true);add(WorkbenchUi.row(pretty,rawTab),BorderLayout.NORTH);JScrollPane scroll=new JScrollPane(text);add(scroll);formatted.addActionListener(e->refresh());WorkbenchUi.style(this);}
 public void setPacket(String value){setPacket(value,value);}
 public void setPacket(String original,String decoded){raw=bounded(original==null?"패킷 없음":original);display=bounded(decoded==null?raw:decoded);refresh();}
 private String bounded(String s){return s.length()>65536?s.substring(0,65536)+"\n[미리보기 64K자 제한]":s;}
 private void refresh(){text.setText(formatted.isSelected()?PacketFormat.pretty(display):raw);text.setFont(WorkbenchUi.textFont(text.getText()));text.setCaretPosition(0);}
}
