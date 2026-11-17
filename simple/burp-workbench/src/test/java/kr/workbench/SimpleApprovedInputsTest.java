package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import javax.swing.border.Border;

/** Real input painting under hostile host defaults, with authoritative models and edits retained. */
public final class SimpleApprovedInputsTest extends WorkbenchTest {
  private static BufferedImage paint(JComponent component,int width,int height){
    component.setSize(width,height);for(int i=0;i<4;i++)UiLayoutTest.layout(component);
    BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(WorkbenchUi.background());g.fillRect(0,0,width,height);component.paint(g);g.dispose();return image;
  }
  private static int buttons(Component component){int count=component instanceof AbstractButton?1:0;if(component instanceof Container p)for(Component c:p.getComponents())count+=buttons(c);return count;}
  private static void step(JSpinner spinner,String direction){Action action=spinner.getActionMap().get(direction);check(action!=null,"numeric keyboard action remains installed: "+direction);action.actionPerformed(new ActionEvent(spinner,ActionEvent.ACTION_PERFORMED,direction));}
  private static boolean near(int pixel,Color expected){Color actual=new Color(pixel);return Math.abs(actual.getRed()-expected.getRed())+Math.abs(actual.getGreen()-expected.getGreen())+Math.abs(actual.getBlue()-expected.getBlue())<30;}
  private static Object field(Object owner,String name){try{return get(owner,name);}catch(Exception error){throw new IllegalStateException(error);}}
  private static int firstInk(BufferedImage image,Rectangle region,Color background){for(int y=region.y;y<Math.min(image.getHeight(),region.y+region.height);y++)for(int x=region.x;x<Math.min(image.getWidth(),region.x+region.width);x++)if(!near(image.getRGB(x,y),background))return y;return -1;}
  private static int firstColumn(BufferedImage image,Rectangle region,Color background){for(int x=region.x;x<Math.min(image.getWidth(),region.x+region.width);x++)for(int y=region.y;y<Math.min(image.getHeight(),region.y+region.height);y++)if(!near(image.getRGB(x,y),background))return x;return -1;}
  private static void codecInsets(String laf,int[] anchor)throws Exception{
    edt(()->{
      for(boolean wrapped:new boolean[]{false,true}){
        JTextArea codec=new JTextArea("CODEC_KEEP\nCODEC_KEEP");codec.setLineWrap(wrapped);codec.putClientProperty("wb.approved.codec",true);ApprovedCodeAreaUI.install(codec);codec.setCaretPosition(4);var ui=codec.getUI();var document=codec.getDocument();
        WorkbenchUi.style(codec);BufferedImage image=paint(codec,420,130);
        try{
          java.awt.geom.Rectangle2D first=codec.modelToView2D(0),next=codec.modelToView2D(codec.getText().indexOf('\n')+1);
          int row=firstInk(image,new Rectangle(0,(int)first.getY(),200,23),WorkbenchUi.surface()),column=firstColumn(image,new Rectangle(0,row,200,17),WorkbenchUi.surface());
          int nextRow=firstInk(image,new Rectangle(0,(int)next.getY(),200,23),WorkbenchUi.surface());
          check(codec.getUI()==ui&&codec.getDocument()==document&&codec.getCaretPosition()==4&&codec.getText().equals("CODEC_KEEP\nCODEC_KEEP"),"explicit codec padding retains its owned UI, document, caret and text in "+laf+" / wrap="+wrapped);
          check(row>=18&&column>=15&&nextRow-row==23,"actual codec glyph padding and23px leading survive host insets in "+laf+" / wrap="+wrapped);
          if(anchor[0]<0){anchor[0]=column;anchor[1]=row;}else check(anchor[0]==column&&anchor[1]==row,"actual codec first glyph coordinates align acrossMetal/Nimbus and wrap preferences");
        }catch(javax.swing.text.BadLocationException error){throw new IllegalStateException(error);}
      }
    });
  }
  private static void packetTheme(String laf)throws Exception{
    PacketInspector[] reference={null};byte[] original="GET /same HTTP/1.1\r\nHost: same.example.test\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    edt(()->{reference[0]=new PacketInspector("wb.approved.inputs.packet.theme");reference[0].approvedTrafficPresentation();reference[0].setPacket(original,null);reference[0].tabs.get(1).doClick(0);reference[0].search.setText("same");});
    PacketInspector packet=reference[0];
    try{
      PacketInspectorTest.idle(packet);
      edt(()->{
        packet.next.doClick(0);packet.text.setCaretPosition(3);packet.text.moveCaretPosition(6);
        var document=packet.text.getStyledDocument();var caret=packet.text.getCaret();var highlighter=packet.text.getHighlighter();var matches=packet.matches();int current=packet.currentMatch(),dot=caret.getDot(),mark=caret.getMark();
        check(matches.size()==2,"actual packet fixture retains two raw search matches in "+laf);
        for(boolean dark:new boolean[]{true,false}){
          kr.packetui.VisualTheme.setDarkOverride(dark);packet.applyTheme();BufferedImage image=paint(packet,420,180);
          Point sample=SwingUtilities.convertPoint(packet.text,Math.min(150,packet.text.getWidth()-20),Math.min(100,packet.text.getHeight()-10),packet);
          check(image.getRGB(sample.x,sample.y)==WorkbenchUi.surface().getRGB(),"actual packet inspector composite paints its local surface without a host white island in "+laf+" / "+dark);
          var localUi=packet.text.getUI();packet.applyTheme();check(packet.text.getUI()==localUi,"ordinary theme repaint retains the owned packet text delegate in "+laf+" / "+dark);
          packet.text.setUI(new javax.swing.plaf.basic.BasicTextPaneUI());packet.applyTheme();
          check(packet.text.getUI()!=localUi&&packet.text.getUI().getClass()==localUi.getClass(),"a foreign packet text delegate is replaced with one owned delegate in "+laf+" / "+dark);
          check(packet.text.getStyledDocument()==document&&packet.text.getCaret()==caret&&packet.text.getHighlighter()==highlighter&&caret.getDot()==dot&&caret.getMark()==mark,
              "foreign packet UI restoration retains the styled document, caret selection and highlighter in "+laf+" / "+dark);
          boolean visibleMatches=matches.stream().allMatch(span->java.util.Arrays.stream(packet.text.getHighlighter().getHighlights()).anyMatch(highlight->highlight.getStartOffset()==span.start()&&highlight.getEndOffset()==span.end()));
          check(packet.mode()==PacketInspector.Mode.RAW&&packet.search.getText().equals("same")&&packet.matches().equals(matches)&&packet.currentMatch()==current&&visibleMatches&&java.util.Arrays.equals(original,packet.previewBytes()),
              "foreign packet UI restoration retains raw mode, query, match position and immutable source bytes in "+laf+" / "+dark);
        }
      });
    }finally{edt(packet::close);}
  }

  public static void main(String[] args)throws Exception{
    LookAndFeel previous=UIManager.getLookAndFeel();
    Object previousText=UIManager.get("TextField.background"),previousSpinner=UIManager.get("Spinner.background"),previousBorder=UIManager.get("TextField.border");
    int[] packetAreaInk={-1},inspectorInk={-1},codecAnchor={-1,-1};
    try {
      for(String laf:List.of("javax.swing.plaf.metal.MetalLookAndFeel","javax.swing.plaf.nimbus.NimbusLookAndFeel")){
        UIManager.setLookAndFeel(laf);UIManager.put("TextField.background",Color.MAGENTA);UIManager.put("Spinner.background",Color.MAGENTA);UIManager.put("TextField.border",BorderFactory.createLineBorder(Color.RED,3));
        edt(()->{
          SpinnerNumberModel model=new SpinnerNumberModel(100,1,200,10);JSpinner spinner=new JSpinner(model);
          JSpinner.DefaultEditor originalEditor=(JSpinner.DefaultEditor)spinner.getEditor();JFormattedTextField numeric=originalEditor.getTextField();var numberDocument=numeric.getDocument();
          numeric.setText("173");numeric.setCaretPosition(2);
          JComboBox<String> combo=new JComboBox<>(new String[]{"전체","계정A"});combo.setEditable(true);WorkbenchUi.style(combo);
          JTextField editable=(JTextField)combo.getEditor().getEditorComponent();var comboDocument=editable.getDocument();var renderer=combo.getRenderer();var comboModel=combo.getModel();Object selected=combo.getSelectedItem();
          editable.setText("작성 중인 계정");editable.setCaretPosition(3);
          JTextField ordinary=new JTextField("KEEP_FIELD_DOCUMENT");var ordinaryDocument=ordinary.getDocument();ordinary.setCaretPosition(4);
          Border inputBorder=null;
          for(boolean dark:new boolean[]{true,false}){
            kr.packetui.VisualTheme.setDarkOverride(dark);WorkbenchUi.style(spinner);WorkbenchUi.style(combo);WorkbenchUi.style(ordinary);
            check(spinner.getModel()==model&&spinner.getEditor()==originalEditor&&numeric.getDocument()==numberDocument&&numeric.getText().equals("173")&&numeric.getCaretPosition()==2&&((Number)spinner.getValue()).intValue()==100,
                "styling retains numeric model/editor/document, uncommitted text, caret and value in "+laf+" / "+dark);
            check(combo.getModel()==comboModel&&combo.getRenderer()==renderer&&combo.getEditor().getEditorComponent()==editable&&editable.getDocument()==comboDocument&&editable.getText().equals("작성 중인 계정")&&editable.getCaretPosition()==3&&combo.getSelectedItem()==selected,
                "styling retains the actual editable selector and unfinished selection in "+laf+" / "+dark);
            check(ordinary.getDocument()==ordinaryDocument&&ordinary.getText().equals("KEEP_FIELD_DOCUMENT")&&ordinary.getCaretPosition()==4,
                "ordinary field delegate replacement retains authored text and caret in "+laf+" / "+dark);
            check(buttons(spinner)==0&&numeric.getHorizontalAlignment()==SwingConstants.LEFT,
                "approved numeric input has no visible native steppers and uses left aligned values in "+laf+" / "+dark);
            check(spinner.getPreferredSize().height==31&&ordinary.getPreferredSize().height==31,
                "numeric and text fields retain the approved 31px height in "+laf+" / "+dark);
            BufferedImage numberPaint=paint(spinner,180,31),comboPaint=paint(combo,180,31),fieldPaint=paint(ordinary,180,31);
            check(near(numberPaint.getRGB(1,15),WorkbenchUi.surface())&&near(numberPaint.getRGB(178,15),WorkbenchUi.surface())&&near(numberPaint.getRGB(90,0),WorkbenchUi.line()),
                "numeric field paints one local border and no host gray frame in "+laf+" / "+dark);
            check(near(comboPaint.getRGB(30,2),WorkbenchUi.surface())&&near(comboPaint.getRGB(30,28),WorkbenchUi.surface())&&near(fieldPaint.getRGB(90,0),WorkbenchUi.line()),
                "editable selector has no inset host border and the normal field has one outer border in "+laf+" / "+dark);
            check(UIManager.get("TextField.background")==Color.MAGENTA&&UIManager.get("Spinner.background")==Color.MAGENTA,
                "local input styling leaves hostile host defaults unchanged in "+laf+" / "+dark);
            JSeparator separator=new JSeparator();WorkbenchUi.style(separator);BufferedImage separatorPaint=paint(separator,140,1);
            check(separatorPaint.getRGB(70,0)==WorkbenchUi.line().getRGB(),"the actual section separator paints one approved line without a host bevel in "+laf+" / "+dark);
            JTabbedPane tabs=new JTabbedPane();tabs.addTab("요청",new JPanel());tabs.addTab("응답",new JPanel());var tabModel=tabs.getModel();WorkbenchUi.style(tabs);BufferedImage tabPaint=paint(tabs,220,90);Rectangle selectedTab=tabs.getBoundsAt(0);
            check(tabPaint.getRGB(selectedTab.x+selectedTab.width/2,selectedTab.y+4)==WorkbenchUi.selected().getRGB(),"the actual selected packet tab paints its pill even when the host tab UI is nonopaque in "+laf+" / "+dark);
            Action nextTab=tabs.getActionMap().get("navigateNext");check(nextTab!=null,"tab navigation command remains installed in "+laf+" / "+dark);nextTab.actionPerformed(new ActionEvent(tabs,ActionEvent.ACTION_PERFORMED,"navigateNext"));check(tabs.getModel()==tabModel&&tabs.getSelectedIndex()==1,"owned tab painting retains the original tab model and keyboard navigation in "+laf+" / "+dark);
            JButton compactEdit=new JButton("수정",new ApprovedTrafficIcon("chevron",WorkbenchUi.ink()));compactEdit.setIconTextGap(4);compactEdit.putClientProperty("wb.approved.button.insets",new Insets(4,3,4,3));WorkbenchUi.style(compactEdit);Insets editInsets=compactEdit.getInsets();check(compactEdit.getFontMetrics(compactEdit.getFont()).stringWidth(compactEdit.getText())+compactEdit.getIcon().getIconWidth()+compactEdit.getIconTextGap()+editInsets.left+editInsets.right<=53,"the actual compact edit caption and chevron fit the approved53px action in "+laf+" / "+dark);
            if(inputBorder==null)inputBorder=ordinary.getBorder();else check(ordinary.getBorder()==inputBorder,"theme changes do not churn normal input border objects");
          }
          try{spinner.commitEdit();}catch(java.text.ParseException error){throw new IllegalStateException(error);}
          check(((Number)spinner.getValue()).intValue()==173,"committing an edited numeric field updates its authoritative model");
          step(spinner,"increment");check(((Number)spinner.getValue()).intValue()==183,"keyboard increment retains the model step");
          spinner.setValue(200);step(spinner,"increment");check(((Number)spinner.getValue()).intValue()==200,"keyboard increment preserves the original upper bound");
          spinner.setValue(1);step(spinner,"decrement");check(((Number)spinner.getValue()).intValue()==1,"keyboard decrement preserves the original lower bound");

          JPanel root=new JPanel(new BorderLayout());root.setBackground(new Color(80,90,100));JPanel legacy=new JPanel(new BorderLayout());legacy.setBackground(WorkbenchUi.surface());JPanel header=new JPanel();header.setBackground(Color.RED);header.setPreferredSize(new Dimension(100,39));legacy.add(header,BorderLayout.NORTH);legacy.add(new JPanel());root.add(legacy);
          JComponent rounded=ApprovedUi.roundedLegacy(legacy);BufferedImage card=paint(root,200,120);
          check(rounded!=legacy&&SwingUtilities.isDescendingFrom(header,legacy)&&legacy.getParent()!=root,"rounding retains the exact legacy card and its live header instead of replacing controls");
          check(card.getRGB(0,0)==root.getBackground().getRGB()&&card.getRGB(100,10)==Color.RED.getRGB(),"legacy-card clipping removes square header corners without hiding its content");
          JPanel absolute=new JPanel();absolute.setBackground(new Color(80,90,100));JPanel retained=new JPanel();retained.add(new JLabel("RETAINED_CARD"));absolute.add(retained);ApprovedUi.layout(absolute,p->ApprovedUi.bounds(retained,10,12,180,90));JComponent owner=ApprovedUi.roundedLegacy(retained);paint(absolute,220,130);
          check(owner!=retained&&owner.getBounds().equals(new Rectangle(10,12,180,90))&&retained.getBounds().equals(new Rectangle(0,0,180,90)),"approved absolute layout closures still position the exact retained card through its rounded owner");
          ApprovedUi.bounds(retained,20,22,160,80);check(owner.getBounds().equals(new Rectangle(20,22,160,80))&&retained.getWidth()==160,"later approved resizing updates the clip owner and retained card together");

          JTextArea source=new JTextArea("GET /same HTTP/1.1\nUNCHANGED");source.putClientProperty("wb.approved.packet",true);var sourceDocument=source.getDocument();source.setCaretPosition(3);Object tag;
          try{tag=source.getHighlighter().addHighlight(19,27,new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(Color.ORANGE));}catch(javax.swing.text.BadLocationException error){throw new IllegalStateException(error);}
          var sourceUi=source.getUI();WorkbenchUi.style(source);BufferedImage sourcePaint=paint(source,360,100);int sourceRow=firstInk(sourcePaint,new Rectangle(8,0,100,35),WorkbenchUi.surface());
          check(sourceRow>=14&&source.getDocument()==sourceDocument&&source.getUI()==sourceUi&&source.getCaretPosition()==3&&source.getHighlighter().getHighlights()[0]==tag,"packet input uses stable approved padding while retaining its text UI, document, caret and highlight in "+laf);
          if(packetAreaInk[0]<0)packetAreaInk[0]=sourceRow;else check(sourceRow==packetAreaInk[0],"actual packet input glyph rows align in Metal and Nimbus composites");
          PacketInspector inspector=new PacketInspector("wb.approved.inputs.inspector");
          try{
            inspector.approvedTrafficPresentation();byte[] bytes="GET /same HTTP/1.1\r\nHost: same.example.test\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);inspector.setPacket(bytes,null);var packetDocument=inspector.text.getDocument();BufferedImage packetPaint=paint(inspector,420,160);
            java.awt.geom.Rectangle2D character=inspector.text.modelToView2D(0);Point origin=SwingUtilities.convertPoint(inspector.text,(int)character.getX(),(int)character.getY(),inspector);int row=firstInk(packetPaint,new Rectangle(origin.x,origin.y,24,(int)character.getHeight()),WorkbenchUi.surface());
            check(row>=0&&inspector.text.getDocument()==packetDocument&&java.util.Arrays.equals(bytes,inspector.previewBytes()),"actual packet inspector composite renders the first glyph without changing original bytes in "+laf);
            if(inspectorInk[0]<0)inspectorInk[0]=row;else check(row==inspectorInk[0],"actual packet inspector first glyph rows align in Metal and Nimbus composites");
          }catch(javax.swing.text.BadLocationException error){throw new IllegalStateException(error);}finally{inspector.close();}
        });
        packetTheme(laf);
        codecInsets(laf,codecAnchor);
      }
    }finally{kr.packetui.VisualTheme.setDarkOverride(null);UIManager.setLookAndFeel(previous);UIManager.put("TextField.background",previousText);UIManager.put("Spinner.background",previousSpinner);UIManager.put("TextField.border",previousBorder);}

    BountyFeaturesTest.factory();BurpExtender entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
    WorkbenchExtension extension=(WorkbenchExtension)get(entry,"workbench");JPanel root=(JPanel)get(extension,"root");
    try{
      seedTraffic(extension,request("https://approved.example.test/search?q=1","GET",true,List.of()),response(200,"APPROVED_ORIGINAL"));
      edt(()->{
        extension.openFeature("payload");JSpinner budget=(JSpinner)SimpleWorkflowTest.named(root,"wb.payload.condition.budget");JSpinner interval=(JSpinner)SimpleWorkflowTest.named(root,"wb.payload.condition.interval");
        Object budgetModel=budget.getModel(),intervalModel=interval.getModel(),selected=field(extension,"current");Object engine=field(entry,"payloadRunner");JTextArea request=(JTextArea)SimpleWorkflowTest.named(root,"wb.simple.payload.request");var document=request.getDocument();String bytes=request.getText();int sent=PayloadIntegrationTest.sent.get();
        WorkbenchUi.style(root);
        check(budget.getModel()==budgetModel&&interval.getModel()==intervalModel&&buttons(budget)==0&&buttons(interval)==0,"assembled payload conditions retain their real bounded numeric models with approved plain controls");
        check(field(entry,"payloadRunner")==engine&&field(extension,"current")==selected&&request.getDocument()==document&&request.getText().equals(bytes),"shared input styling preserves the actual payload engine, selected original and authored request document");
        check(PayloadIntegrationTest.sent.get()==sent,"restyling assembled conditions does not run a scan or send target HTTP");
      });
    }finally{SimpleWorkflowTest.unload();}
    System.out.println("SimpleApprovedInputsTest: "+checks+" checks passed (real controls, hostile host LAF paint, preserved documents/models)");
  }
}
