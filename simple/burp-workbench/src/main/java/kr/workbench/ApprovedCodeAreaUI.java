package kr.workbench;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import javax.swing.*;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.*;

/** Reference codec leading without changing its document, wrapping or glyph proportions. */
final class ApprovedCodeAreaUI extends BasicTextAreaUI {
  private static final String RESTORE="wb.approved.codec.restore";
  private record Restore(Caret caret,int dot,int mark){}
  static void install(JTextArea area){
    if(!(area.getUI() instanceof ApprovedCodeAreaUI)){
      Restore saved=area.getClientProperty(RESTORE) instanceof Restore value?value:null;area.putClientProperty(RESTORE,null);
      Caret caret=saved==null?area.getCaret():saved.caret();int dot=saved==null?(caret==null?0:caret.getDot()):saved.dot(),mark=saved==null?(caret==null?0:caret.getMark()):saved.mark();
      area.setUI(new ApprovedCodeAreaUI());
      if(caret!=null&&area.getCaret()!=caret)area.setCaret(caret);
      if(area.getCaret()!=null){int length=area.getDocument().getLength();area.getCaret().setDot(Math.min(mark,length));area.getCaret().moveDot(Math.min(dot,length));}
    }
    if(!Boolean.TRUE.equals(area.getClientProperty("wb.approved.codec.leading"))){
      area.putClientProperty("wb.approved.codec.leading",true);
      area.addPropertyChangeListener(event->{if("UI".equals(event.getPropertyName())&&!(area.getUI() instanceof ApprovedCodeAreaUI))install(area);});
    }
  }
  @Override public void uninstallUI(JComponent component){
    if(component instanceof JTextArea area&&area.getCaret()!=null){Caret caret=area.getCaret();area.putClientProperty(RESTORE,new Restore(caret,caret.getDot(),caret.getMark()));}
    super.uninstallUI(component);
  }
  private int leading(){return Math.round(WorkbenchUi.displaySize(23f));}
  private double factor(){JTextComponent owner=getComponent();return (double)leading()/Math.max(1,owner.getFontMetrics(owner.getFont()).getHeight());}
  @Override public View create(Element element){
    JTextArea area=(JTextArea)getComponent();
    // Keep Swing's bidi/complex-script shaping and caret semantics authoritative.
    if(Boolean.TRUE.equals(area.getDocument().getProperty("i18n")))return super.create(element);
    return area.getLineWrap()?new SpacedWrap(element,area.getWrapStyleWord()):new SpacedPlain(element);
  }
  private final class SpacedPlain extends PlainView {
    SpacedPlain(Element element){super(element);}
    @Override protected void updateMetrics(){super.updateMetrics();if(!(metrics instanceof LeadingMetrics)||metrics.getHeight()!=leading())metrics=new LeadingMetrics(metrics instanceof LeadingMetrics previous?previous.original:metrics,leading());}
  }
  private final class SpacedWrap extends WrappedPlainView {
    private boolean painting,paintingGlyph;
    private double paintFactor;
    private float paintOrigin;
    SpacedWrap(Element element,boolean word){super(element,word);}
    private float scaled(float value){return (float)Math.min(Float.MAX_VALUE,value*factor());}
    @Override public float getPreferredSpan(int axis){float value=super.getPreferredSpan(axis);return axis==View.Y_AXIS?scaled(value):value;}
    @Override public float getMinimumSpan(int axis){float value=super.getMinimumSpan(axis);return axis==View.Y_AXIS?scaled(value):value;}
    @Override public float getMaximumSpan(int axis){float value=super.getMaximumSpan(axis);return axis==View.Y_AXIS?scaled(value):value;}
    @Override public void setSize(float width,float height){super.setSize(width,(float)(height/factor()));}
    private Rectangle virtual(Shape allocation){Rectangle bounds=allocation.getBounds();return new Rectangle(bounds.x,bounds.y,bounds.width,(int)Math.ceil(bounds.height/factor()));}
    @Override public void paint(Graphics graphics,Shape allocation){
      Rectangle2D bounds=allocation.getBounds2D();Graphics2D g=(Graphics2D)graphics.create();
      boolean previousPainting=painting;double previousFactor=paintFactor;float previousOrigin=paintOrigin;
      painting=true;paintFactor=factor();paintOrigin=(float)bounds.getY();
      g.translate(0,paintOrigin);g.scale(1,paintFactor);g.translate(0,-paintOrigin);
      try{super.paint(g,virtual(allocation));}finally{painting=previousPainting;paintFactor=previousFactor;paintOrigin=previousOrigin;g.dispose();}
    }
    @Override protected void drawLine(int start,int end,Graphics graphics,int x,int y){
      if(graphics instanceof Graphics2D g)drawLine(start,end,g,(float)x,(float)y);else super.drawLine(start,end,graphics,x,y);
    }
    @Override protected void drawLine(int start,int end,Graphics2D graphics,float x,float y){
      if(!painting||paintingGlyph){super.drawLine(start,end,graphics,x,y);return;}
      Graphics2D glyph=(Graphics2D)graphics.create();paintingGlyph=true;
      AffineTransform inverse=new AffineTransform();inverse.translate(0,paintOrigin);inverse.scale(1,1/paintFactor);inverse.translate(0,-paintOrigin);glyph.transform(inverse);
      float ascent=getComponent().getFontMetrics(getComponent().getFont()).getAscent();
      float baseline=(float)(paintOrigin+(y-paintOrigin)*paintFactor+ascent*(1-paintFactor));
      try{super.drawLine(start,end,glyph,x,baseline);}finally{paintingGlyph=false;glyph.dispose();}
    }
    @Override public Shape modelToView(int position,Shape allocation,Position.Bias bias)throws BadLocationException {
      Shape result=super.modelToView(position,virtual(allocation),bias);Rectangle2D value=result.getBounds2D();double origin=allocation.getBounds2D().getY(),scale=factor();
      return new Rectangle2D.Double(value.getX(),origin+(value.getY()-origin)*scale,value.getWidth(),value.getHeight()*scale);
    }
    @Override public int viewToModel(float x,float y,Shape allocation,Position.Bias[] bias){double origin=allocation.getBounds2D().getY();return super.viewToModel(x,(float)(origin+(y-origin)/factor()),virtual(allocation),bias);}
  }
  private static final class LeadingMetrics extends FontMetrics {
    private final FontMetrics original;private final int height;
    LeadingMetrics(FontMetrics original,int height){super(original.getFont());this.original=original;this.height=height;}
    @Override public int getHeight(){return height;}
    @Override public int getLeading(){return Math.max(0,height-getAscent()-getDescent());}
    @Override public int getAscent(){return original.getAscent();}
    @Override public int getDescent(){return original.getDescent();}
    @Override public int getMaxAscent(){return original.getMaxAscent();}
    @Override public int getMaxDescent(){return original.getMaxDescent();}
    @Override public int getMaxAdvance(){return original.getMaxAdvance();}
    @Override public int charWidth(char value){return original.charWidth(value);}
    @Override public int charWidth(int value){return original.charWidth(value);}
    @Override public int stringWidth(String value){return original.stringWidth(value);}
    @Override public int charsWidth(char[] value,int offset,int length){return original.charsWidth(value,offset,length);}
    @Override public int bytesWidth(byte[] value,int offset,int length){return original.bytesWidth(value,offset,length);}
    @Override public int[] getWidths(){return original.getWidths();}
  }
}
