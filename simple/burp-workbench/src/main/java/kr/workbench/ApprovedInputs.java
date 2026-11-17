package kr.workbench;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.*;
import javax.swing.text.Caret;
import javax.swing.text.JTextComponent;

/** The approved input surface belongs to the field, not the host look and feel. */
final class ApprovedInputs {
  private ApprovedInputs() {}
  static final javax.swing.border.Border INNER = BorderFactory.createEmptyBorder();
  static final javax.swing.border.Border BORDER = new AbstractBorder() {
    @Override public Insets getBorderInsets(Component c) { return new Insets(3,c instanceof WorkbenchSkin.SearchField?30:9,3,9); }
    @Override public Insets getBorderInsets(Component c,Insets insets) { insets.set(3,c instanceof WorkbenchSkin.SearchField?30:9,3,9);return insets; }
    @Override public void paintBorder(Component c,Graphics graphics,int x,int y,int width,int height) {
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
      g.setColor(c.hasFocus()?WorkbenchUi.accent():WorkbenchUi.line());
      g.drawRoundRect(x,y,width-1,height-1,14,14);g.dispose();
    }
  };

  static boolean ownedEditor(Component c) {
    for(Container parent=c.getParent();parent!=null;parent=parent.getParent()) {
      if(parent instanceof JComboBox<?> || parent instanceof JSpinner)return true;
      if(parent instanceof JScrollPane || parent instanceof JTabbedPane || parent instanceof JTable)return false;
    }
    return false;
  }

  static void text(JTextField field) {
    if(Boolean.TRUE.equals(field.getClientProperty("wb.nativeEditor")))return;
    boolean formatted=field instanceof JFormattedTextField;
    if(formatted?!(field.getUI() instanceof FormattedUi):!(field.getUI() instanceof FieldUi)) {
      // Installing a local delegate must not discard an unfinished edit or its selection.
      Caret caret=field.getCaret();int dot=caret==null?0:caret.getDot(),mark=caret==null?0:caret.getMark();
      if(formatted)field.setUI(new FormattedUi());else field.setUI(new FieldUi());
      if(caret!=null&&field.getCaret()!=caret)field.setCaret(caret);
      if(field.getCaret()!=null){int length=field.getDocument().getLength();field.getCaret().setDot(Math.min(mark,length));field.getCaret().moveDot(Math.min(dot,length));}
    }
    boolean nested=ownedEditor(field);
    field.setOpaque(false);field.setBorder(nested?INNER:BORDER);field.setMargin(new Insets(0,0,0,0));
    field.setBackground(WorkbenchUi.surface());field.setForeground(field.isEnabled()?WorkbenchUi.ink():kr.packetui.VisualTheme.muted());
    if(!(field.getClientProperty("wb.font.size") instanceof Number))field.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(nested||field instanceof WorkbenchSkin.SearchField?12f:13f)));
    field.setCaretColor(WorkbenchUi.ink());field.setSelectionColor(WorkbenchUi.selected());field.setSelectedTextColor(WorkbenchUi.ink());
  }

  private static void background(Graphics graphics,JComponent component) {
    if(ownedEditor(component))return;
    Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
    g.setColor(component.getBackground());g.fillRoundRect(0,0,component.getWidth(),component.getHeight(),14,14);g.dispose();
  }
  private static Dimension size(Dimension preferred,JComponent component) {
    return ownedEditor(component)?preferred:new Dimension(preferred.width,Math.max(31,preferred.height));
  }
  private static final class FieldUi extends BasicTextFieldUI {
    @Override public void update(Graphics graphics,JComponent component){background(graphics,component);super.paint(graphics,component);}
    @Override protected void paintBackground(Graphics graphics){}
    @Override public Dimension getPreferredSize(JComponent component){return size(super.getPreferredSize(component),component);}
  }
  private static final class FormattedUi extends BasicFormattedTextFieldUI {
    @Override public void update(Graphics graphics,JComponent component){background(graphics,component);super.paint(graphics,component);}
    @Override protected void paintBackground(Graphics graphics){}
    @Override public Dimension getPreferredSize(JComponent component){return size(super.getPreferredSize(component),component);}
  }

  static void spinner(JSpinner spinner) {
    if(!(spinner.getUI() instanceof SpinnerUi))spinner.setUI(new SpinnerUi());
    spinner.setOpaque(false);spinner.setBackground(WorkbenchUi.surface());spinner.setForeground(WorkbenchUi.ink());spinner.setBorder(BORDER);
    if(!(spinner.getClientProperty("wb.font.size") instanceof Number))spinner.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12f)));
    editor(spinner);
  }
  private static void editor(JSpinner spinner) {
    if(spinner.getEditor() instanceof JSpinner.DefaultEditor editor){
      editor.setOpaque(false);editor.setBorder(INNER);JFormattedTextField field=editor.getTextField();
      text(field);field.setHorizontalAlignment(SwingConstants.LEFT);
    }
  }
  private static final class SpinnerUi extends BasicSpinnerUI {
    @Override protected Component createNextButton(){return null;}
    @Override protected Component createPreviousButton(){return null;}
    @Override protected JComponent createEditor(){JComponent editor=super.createEditor();editor.setOpaque(false);return editor;}
    @Override public void update(Graphics graphics,JComponent component){
      background(graphics,component);paint(graphics,component);
    }
    @Override public void paint(Graphics graphics,JComponent component){
      editor(spinner);
      Object unit=spinner.getClientProperty("wb.approved.suffix");
      if(unit instanceof String suffix&&!suffix.isEmpty()&&spinner.getEditor() instanceof JSpinner.DefaultEditor editor){
        JFormattedTextField field=editor.getTextField();
        if(field.getWidth()>0&&!field.getText().isEmpty()){
          Point at=SwingUtilities.convertPoint(field,0,0,spinner);FontMetrics metrics=field.getFontMetrics(field.getFont());
          Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.clipRect(3,1,Math.max(0,spinner.getWidth()-12),Math.max(0,spinner.getHeight()-2));g.setColor(field.getForeground());g.setFont(field.getFont());
          int baseline=field.getBaseline(field.getWidth(),field.getHeight());if(baseline<0)baseline=(field.getHeight()-metrics.getHeight())/2+metrics.getAscent();
          g.drawString(suffix,at.x+metrics.stringWidth(field.getText()),at.y+baseline);g.dispose();
        }
      }
    }
    @Override public Dimension getPreferredSize(JComponent component){Dimension preferred=spinner.getEditor().getPreferredSize();Insets insets=spinner.getInsets();return new Dimension(preferred.width+insets.left+insets.right,Math.max(31,preferred.height+insets.top+insets.bottom));}
  }
}
