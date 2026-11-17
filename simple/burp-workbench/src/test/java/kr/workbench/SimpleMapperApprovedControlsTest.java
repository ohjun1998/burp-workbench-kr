package kr.workbench;

import burp.BurpExtender;
import burp.IBurpExtenderCallbacks;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.swing.*;

/** Real embedded controls retain their models while rendering the approved input, tree and menu treatment. */
public final class SimpleMapperApprovedControlsTest extends WorkbenchTest {
  private static void mapperStyle(Component component){
    try{var style=Class.forName("kr.featuremapper.MapperUi").getDeclaredMethod("style",Component.class);style.setAccessible(true);style.invoke(null,component);}
    catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
  }
  private static Object value(Object owner,String name){try{return get(owner,name);}catch(Exception failure){throw new IllegalStateException(failure);}}
  private static void layout(Container root){for(int i=0;i<6;i++)UiLayoutTest.layout(root);}
  private static BufferedImage paint(JComponent component,int width,int height){component.setSize(width,height);component.doLayout();BufferedImage picture=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=picture.createGraphics();g.setColor(WorkbenchUi.background());g.fillRect(0,0,width,height);component.paint(g);g.dispose();return picture;}
  private static final class PopupBoundary extends RuntimeException {}
  private static final class PopupRequests extends JComboBox<String>{
    int opened;
    PopupRequests(){super(new String[]{"전체","실행1"});addPopupMenuListener(new javax.swing.event.PopupMenuListener(){public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event){opened++;throw new PopupBoundary();}public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event){}public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event){}});}
    public boolean isShowing(){return true;}
  }
  private static void hostPainters(){
    LookAndFeel previous=UIManager.getLookAndFeel();Object textBorder=UIManager.get("TextField.border"),comboBorder=UIManager.get("ComboBox.border");
    try{
      for(UIManager.LookAndFeelInfo candidate:UIManager.getInstalledLookAndFeels())if(candidate.getName().equals("Metal")||candidate.getName().equals("Nimbus")){
        UIManager.setLookAndFeel(candidate.getClassName());UIManager.put("TextField.border",BorderFactory.createLineBorder(Color.MAGENTA,4));UIManager.put("ComboBox.border",BorderFactory.createLineBorder(Color.MAGENTA,4));
        var combo=new JComboBox<>(new String[]{"계정 선택"});combo.setEditable(true);mapperStyle(combo);BufferedImage picture=paint(combo,180,31);int nativeBorder=0;
        for(int y=0;y<picture.getHeight();y++)for(int x=0;x<picture.getWidth();x++)if(picture.getRGB(x,y)==Color.MAGENTA.getRGB())nativeBorder++;
        check(nativeBorder==0&&picture.getRGB(75,4)==WorkbenchUi.surface().getRGB(),
            "the approved Mapper input suppresses inherited inner-frame painters under "+candidate.getName());
        JTable grid=new JTable(new Object[][]{{"GET","/api"}},new String[]{"메서드","URL"});mapperStyle(grid);
        check(grid.getShowHorizontalLines()&&!grid.getShowVerticalLines(),"Mapper row separators retain the approved horizontal-only treatment under "+candidate.getName());
        JTabbedPane tabs=new JTabbedPane();tabs.addTab("요청·응답",new JPanel());tabs.addTab("매핑 근거",new JPanel());mapperStyle(tabs);BufferedImage strip=paint(tabs,250,120);Rectangle selected=tabs.getBoundsAt(0);
        check(strip.getRGB(selected.x+8,selected.y+5)==WorkbenchUi.selected().getRGB(),"the actual selected Mapper tab keeps its filled pill under "+candidate.getName());
        PopupRequests picker=new PopupRequests();picker.putClientProperty("fm.approved.filter.field",true);mapperStyle(picker);picker.setSize(180,31);picker.doLayout();
        // Stop at the real visibility callback before a headless test would create a native popup peer.
        try{picker.dispatchEvent(new java.awt.event.MouseEvent(picker,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,90,15,1,false,java.awt.event.MouseEvent.BUTTON1));}catch(PopupBoundary expected){}
        check(picker.opened==1&&(picker.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("alt DOWN"))!=null||picker.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("DOWN"))!=null),
            "the field-style filter retains whole-field popup clicks and original keyboard selection under "+candidate.getName()+" (opens="+picker.opened+", altDown="+picker.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("alt DOWN"))+", down="+picker.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke("DOWN"))+")");
      }
    }catch(Exception failure){throw new IllegalStateException(failure);}
    finally{try{UIManager.setLookAndFeel(previous);}catch(UnsupportedLookAndFeelException failure){throw new IllegalStateException(failure);}UIManager.put("TextField.border",textBorder);UIManager.put("ComboBox.border",comboBorder);}
  }
  public static void main(String[] args)throws Exception{
    BountyFeaturesTest.factory();var entry=new BurpExtender();entry.registerExtenderCallbacks((IBurpExtenderCallbacks)PayloadIntegrationTest.host(IBurpExtenderCallbacks.class));
    edt(()->entry.initialize(SimpleWorkflowTest.previewApi()));edt(()->{});
    var extension=(WorkbenchExtension)get(entry,"workbench");var root=(JPanel)get(extension,"root");var mapper=(kr.featuremapper.FeatureMapperWorkspace)get(extension,"featureMapper");
    int sent=PayloadIntegrationTest.sent.get();
    try{
      mapper.observeCaptured(System.currentTimeMillis(),"approved UI fixture",request("https://approved.example.test/api/documents/101","GET",true,java.util.List.of()),response(200,"{\"id\":101}"));
      edt(()->{
        try{var refresh=mapper.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(mapper);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        extension.openFeature("mapper");root.setSize(1600,1000);ApprovedMapperDesign.install(mapper.component());WorkbenchUi.style(root);layout(root);
        var tree=(JTree)SimpleWorkflowTest.named(root,"fm.feature.tree");var table=(JTable)SimpleWorkflowTest.named(root,"fm.history");var treeModel=tree.getModel();var tableModel=table.getModel();
        check(tree.getRowCount()>=2,"the approved tree fixture uses a real captured feature group and leaf");tree.setSelectionRow(1);
        var orphan=(AbstractButton)SimpleWorkflowTest.named(root,"fm.unclassified");
        check(SimpleWorkflowTest.named(root,"fm.approved.tree.card")!=null&&SwingUtilities.getAncestorOfClass(JScrollPane.class,orphan)!=null,
            "the original tree and orphan-filter controller share one rounded scrollable card");
        for(String id:new String[]{"wb.actions.mapper.project.open","wb.actions.mapper.feature.open","fm.workflow.request.options.open"}){
          var button=(JButton)SimpleWorkflowTest.named(root,id);
          check(button.getIcon()!=null&&button.getComponentPopupMenu()!=null&&button.getActionListeners().length>0,
              "the approved dropdown glyph retains its real existing popup and action: "+id);
        }
        var host=(kr.featuremapper.FeatureMapperWorkspace.EmbeddedHost)value(mapper,"embeddedHost");
        byte[] q="GET /approved HTTP/1.1\r\nHost: approved.example.test\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] s="HTTP/1.1 200 OK\r\n\r\nAPPROVED_RAW".getBytes(StandardCharsets.ISO_8859_1);host.selectedPacket(q,q.length,s,s.length);
        var request=(PacketInspector)SimpleWorkflowTest.named(root,"wb.mapper.request");var response=(PacketInspector)SimpleWorkflowTest.named(root,"wb.mapper.response");var requestDoc=request.text.getDocument();
        var runs=(JComboBox<?>)value(mapper,"runs");var runModel=runs.getModel();var runRenderer=runs.getRenderer();var runUi=runs.getUI();
        var editable=new JComboBox<>(new String[]{"原選択"});editable.setEditable(true);mapperStyle(editable);
        var editor=(JTextField)editable.getEditor().getEditorComponent();var document=editor.getDocument();editor.setText("작성 중 계정");editor.setCaretPosition(3);
        var editableModel=editable.getModel();var editableRenderer=editable.getRenderer();var editableUi=editable.getUI();
        boolean initial=WorkbenchUi.dark();var moon=(JButton)SimpleWorkflowTest.named(root,"wb.traffic.header.tools");
        for(boolean dark:new boolean[]{!initial,initial}){
          moon.doClick(0);mapperStyle(editable);layout(root);
          check(runs.getUI()==runUi&&runs.getModel()==runModel&&runs.getRenderer()==runRenderer,
              "the real run selector retains its delegate, renderer and model across theme changes in "+dark);
          boolean arrow=false;for(Component child:runs.getComponents())if(child instanceof AbstractButton&&child.isVisible())arrow=true;
          check(!arrow,"the real run selector uses the approved input-field appearance without an extra arrow in "+dark);
          Component filters=SimpleWorkflowTest.named(root,"fm.filters");JTextField search=null;for(Component child:ApprovedLayouts.all(filters))if(child instanceof JTextField field){search=field;break;}
          check(search!=null&&search.getBackground().equals(dark?new Color(25,28,34):new Color(241,242,244)),"the actual Mapper search retains the approved subtle neutral fill in "+dark);
          check(editable.getUI()==editableUi&&editable.getRenderer()==editableRenderer&&editable.getModel()==editableModel
                  &&editable.getEditor().getEditorComponent()==editor&&editor.getDocument()==document
                  &&editor.getText().equals("작성 중 계정")&&editor.getCaretPosition()==3,
              "removing the inner frame preserves the editable selection and unfinished document in "+dark);
          BufferedImage field=paint(editable,180,31);
          check(editor.getBorder().getBorderInsets(editor).equals(new Insets(0,0,0,0))&&field.getRGB(75,4)==WorkbenchUi.surface().getRGB(),
              "editable Mapper input has one outer rounded field and no native inner frame in "+dark);
          check(tree.getModel()==treeModel&&table.getModel()==tableModel&&value(extension,"featureMapper")==mapper,
              "approved tree/menu repainting preserves the original Mapper engine and models in "+dark);
          BufferedImage rows=paint(tree,216,200);Rectangle selected=tree.getRowBounds(1);int textPixels=0;
          for(int y=selected.y;y<Math.min(rows.getHeight(),selected.y+selected.height);y++)for(int x=40;x<200;x++)if(rows.getRGB(x,y)==WorkbenchUi.accent().getRGB())textPixels++;
          check(textPixels>5&&rows.getRGB(0,selected.y+10)==WorkbenchUi.surface().getRGB(),
              "the real tree renderer paints readable selected text inside its inset rounded row in "+dark);
          check(request.text.getDocument()==requestDoc&&Arrays.equals(request.previewBytes(),q)&&Arrays.equals(response.previewBytes(),s),
              "the approved Mapper controls preserve packet documents and exact source bytes in "+dark);
        }
        hostPainters();
        check(PayloadIntegrationTest.sent.get()==sent,"approved Mapper styling, menu models and theme changes send zero target HTTP");
      });
    }finally{SimpleWorkflowTest.unload();}
    System.out.println("SimpleMapperApprovedControlsTest: "+checks+" checks passed (retained real Mapper controls and source bytes)");
  }
}
