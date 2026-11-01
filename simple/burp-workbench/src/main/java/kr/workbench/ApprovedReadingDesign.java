package kr.workbench;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.table.TableColumn;

/** Reading-page details from the approved reference, retaining the original controllers. */
final class ApprovedReadingDesign {
  private ApprovedReadingDesign() {}
  private static Component named(Component root,String name){return ApprovedUi.find(root,name);}
  private static Component region(Container parent,String side){return parent.getLayout() instanceof BorderLayout layout?layout.getLayoutComponent(side):null;}
  private static List<Component> all(Component root){List<Component> result=new ArrayList<>();result.add(root);if(root instanceof Container parent)for(Component child:parent.getComponents())result.addAll(all(child));return result;}
  static Color searchFill(){return WorkbenchUi.dark()?new Color(25,28,34):new Color(241,242,244);}
  private static Color searchLine(){Color ink=WorkbenchUi.ink(),background=WorkbenchUi.background();return new Color(Math.round(ink.getRed()*.14f+background.getRed()*.86f),Math.round(ink.getGreen()*.14f+background.getGreen()*.86f),Math.round(ink.getBlue()*.14f+background.getBlue()*.86f));}
  private static final AbstractBorder SEARCH_BORDER=new AbstractBorder(){
    public Insets getBorderInsets(Component c){return new Insets(5,28,5,8);}
    public Insets getBorderInsets(Component c,Insets insets){insets.set(5,28,5,8);return insets;}
    public void paintBorder(Component c,Graphics graphics,int x,int y,int width,int height){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(c.hasFocus()?WorkbenchUi.accent():searchLine());g.draw(new RoundRectangle2D.Double(x+.5,y+.5,width-1,height-1,16,16));g.dispose();}
  };
  private static void search(JTextField field,String placeholder){
    if(field==null)return;
    field.putClientProperty("wb.search.placeholder",placeholder);
    if(!Boolean.TRUE.equals(field.getClientProperty("wb.reading.search"))){
      field.putClientProperty("wb.reading.search",true);
      PropertyChangeListener refresh=event->{String key=event.getPropertyName();if("background".equals(key)&&!searchFill().equals(field.getBackground()))field.setBackground(searchFill());if("border".equals(key)&&field.getBorder()!=SEARCH_BORDER)field.setBorder(SEARCH_BORDER);};
      field.addPropertyChangeListener(refresh);
    }
    field.setBackground(searchFill());field.setBorder(SEARCH_BORDER);
  }

  static void traffic(JPanel page){
    if(page instanceof ApprovedTrafficPanel traffic){
      search(traffic.search,"URL · 메서드 · 상태로 검색");
      if(!Boolean.TRUE.equals(traffic.table.getClientProperty("wb.approved.traffic.columns"))){
        traffic.table.putClientProperty("wb.approved.traffic.columns",true);traffic.table.putClientProperty("wb.approved.columns.pending",true);
        traffic.table.putClientProperty("wb.approved.columns.reset",(Runnable)traffic::styleReference);
      }
      // Establish the approved baseline before the appearance model discovers this table.
      traffic.styleReference();
    }
  }

  static void js(JPanel page){
    search((JTextField)named(page,"wb.js.findings.search"),"경로 · 메서드 · 입력 이름 검색");
    if(page instanceof JsAnalysisPanel controller&&controller.resultTabs.getLayout() instanceof BorderLayout layout)layout.setVgap(10);
  }

  private static void pill(AbstractButton button){
    button.putClientProperty("wb.filter.segment",true);button.putClientProperty("wb.segment.button",true);
    if(!Boolean.TRUE.equals(button.getClientProperty("wb.reading.pill"))){
      button.putClientProperty("wb.reading.pill",true);
      Runnable refresh=()->{
        Font font=WorkbenchUi.font().deriveFont(button.isSelected()?Font.BOLD:Font.PLAIN,WorkbenchUi.displaySize(12f));
        if(!font.equals(button.getFont()))button.setFont(font);
        button.setForeground(button.isSelected()?WorkbenchUi.accent():kr.packetui.VisualTheme.muted());
        button.setPreferredSize(new Dimension(button.getFontMetrics(font).stringWidth(button.getText())+20,30));
      };
      button.addItemListener(event->refresh.run());
      button.addPropertyChangeListener(event->{if("font".equals(event.getPropertyName())||"text".equals(event.getPropertyName()))refresh.run();});
      refresh.run();
    }
  }

  static void response(JPanel page){
    if(!(page instanceof ResponseReconPanel controller)||Boolean.TRUE.equals(page.getClientProperty("wb.reading.response")))return;
    page.putClientProperty("wb.reading.response",true);
    responseMetadata(controller);
    JTabbedPane pages=(JTabbedPane)named(page,"wb.response.finding.pages");
    JPanel modes=(JPanel)named(page,"wb.flow.response.modes");
    if(modes!=null){
      ApprovedUi.row(modes,2);
      for(Component c:modes.getComponents())if(c instanceof AbstractButton button)pill(button);
      Runnable counts=()->{
        int[] values={controller.findings.getRowCount(),controller.documents.getRowCount(),controller.flowModel.getRowCount()};String[] captions={"응답 관찰","API 문서","저장·조회 연결"};
        for(int i=0;i<3;i++){Component c=named(page,"wb.response.page."+i);if(c instanceof AbstractButton button)button.setText(captions[i]+" "+values[i]);}
        modes.revalidate();
      };
      for(var model:List.of(controller.findings,controller.documents,controller.flowModel))model.addTableModelListener(event->{if(SwingUtilities.isEventDispatchThread())counts.run();else SwingUtilities.invokeLater(counts);});
      counts.run();
    }
    JSplitPane split=(JSplitPane)named(page,"wb.response.finding.split");
    if(split!=null&&split.getLeftComponent() instanceof JPanel list){
      Component originalSearch=region(list,BorderLayout.NORTH);
      if(originalSearch instanceof JTextField field){
        search(field,"분류 · URL · 관찰 내용 검색");
        int searchSlot=list.getComponentZOrder(field);
        list.remove(field);JPanel header=new JPanel();header.setPreferredSize(new Dimension(0,60));
        JLabel count=new JLabel(),origin=new JLabel();count.setName("wb.approved.response.count");origin.setName("wb.approved.response.origin");
        for(JLabel label:new JLabel[]{count,origin}){label.putClientProperty("html.disable",true);label.putClientProperty("wb.font.size",11);label.putClientProperty("wb.secondary",true);}
        JPanel summary=new JPanel(new BorderLayout());summary.setBorder(BorderFactory.createEmptyBorder(0,4,0,4));summary.add(count);summary.add(origin,BorderLayout.EAST);
        header.add(field);header.add(summary);ApprovedUi.layout(header,p->{ApprovedUi.bounds(field,0,0,p.getWidth(),34);ApprovedUi.bounds(summary,0,44,p.getWidth(),16);});list.add(header,BorderLayout.NORTH);if(searchSlot>=0)list.setComponentZOrder(header,searchSlot);((BorderLayout)list.getLayout()).setVgap(10);
        Runnable context=()->{
          count.setText("관찰 "+controller.findingTable.getRowCount()+"개 · 원본 응답 연결");String host="";
          int row=controller.findingTable.getSelectedRow();if(row>=0&&row<controller.findingTable.getRowCount())try{int model=controller.findingTable.convertRowIndexToModel(row);if(model>=0&&model<controller.findings.getRowCount()){String url=String.valueOf(controller.findings.getValueAt(model,1));String parsed=java.net.URI.create(url).getHost();if(parsed!=null)host=parsed;}}catch(IllegalArgumentException|IndexOutOfBoundsException ignored){}
          origin.setText(host);summary.setToolTipText(controller.status.getText());
        };
        controller.findings.addTableModelListener(event->SwingUtilities.invokeLater(context));if(controller.findingTable.getRowSorter()!=null)controller.findingTable.getRowSorter().addRowSorterListener(event->SwingUtilities.invokeLater(context));controller.findingTable.getSelectionModel().addListSelectionListener(event->context.run());controller.status.addPropertyChangeListener("text",event->context.run());context.run();
      }
      JTable table=controller.findingTable;table.putClientProperty("wb.table.floating",true);table.putClientProperty("wb.approved.rowHeight",32);
      if(table.getParent() instanceof JViewport viewport&&viewport.getParent() instanceof JScrollPane scroll)scroll.putClientProperty("wb.approved.scroll.flat",true);
      String[] labels={"관찰","영향 경로","횟수"};int[] widths={234,220,66};for(int i=0;i<Math.min(3,table.getColumnCount());i++){TableColumn column=table.getColumnModel().getColumn(i);column.setHeaderValue(labels[i]);if(!Boolean.TRUE.equals(table.getClientProperty("wb.appearance.columns.restored"))){column.setPreferredWidth(widths[i]);column.setWidth(widths[i]);}}
      Component footer=region(list,BorderLayout.SOUTH);
      if(footer!=null){list.remove(footer);JPanel notes=new JPanel(new GridLayout(2,1));notes.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));notes.setPreferredSize(new Dimension(0,46));notes.add(footer);JLabel hint=new JLabel("검토한 항목만 원본과 함께 증거로 연결합니다.");hint.putClientProperty("wb.font.size",11);hint.putClientProperty("wb.secondary",true);notes.add(hint);if(footer instanceof JComponent c)c.putClientProperty("wb.font.size",11);list.add(notes,BorderLayout.SOUTH);}
    }
    JPanel reader=(JPanel)named(page,"wb.card.pdf.response.reader");
    if(reader!=null&&region(reader,BorderLayout.NORTH) instanceof JPanel header&&header.getLayout() instanceof BorderLayout){
      Component title=region(header,BorderLayout.CENTER);if(title instanceof JLabel label){label.setBorder(BorderFactory.createEmptyBorder(0,12,0,0));label.putClientProperty("wb.font.size",13);label.putClientProperty("wb.font.heading",true);}
      Component oldTools=region(header,BorderLayout.EAST);if(oldTools!=null)header.remove(oldTools);
      JPanel tools=new JPanel(new CardLayout());tools.setPreferredSize(new Dimension(118,44));
      tools.add(readingMenu(controller.findingResponse,"response"),"response");tools.add(readingMenu(controller.findingRequest,"request"),"request");tools.add(new JPanel(),"other");
      Runnable select=()->((CardLayout)tools.getLayout()).show(tools,pages.getSelectedIndex()==0?"response":pages.getSelectedIndex()==1?"request":"other");pages.addChangeListener(event->select.run());select.run();header.add(tools,BorderLayout.EAST);
    }
  }

  private static void responseMetadata(ResponseReconPanel controller){
    if(!(controller.findingContext.getParent() instanceof JPanel context))return;
    JPanel metadata=new JPanel();metadata.setName("wb.approved.response.metadata");ApprovedUi.row(metadata,14);
    JLabel request=new JLabel(),location=new JLabel(),candidate=new JLabel("관찰 후보");request.setName("wb.approved.response.request.caption");location.setName("wb.approved.response.location.caption");candidate.setName("wb.approved.response.candidate");
    for(JLabel label:new JLabel[]{request,location,candidate}){label.putClientProperty("html.disable",true);label.putClientProperty("wb.font.size",12);}
    request.putClientProperty("wb.secondary",true);location.putClientProperty("wb.secondary",true);ApprovedUi.chip(candidate);
    metadata.add(request);metadata.add(location);metadata.add(candidate);
    context.remove(controller.findingContext);JPanel retained=new JPanel();retained.add(controller.findingContext);retained.setVisible(false);context.add(metadata);context.add(retained);
    context.setMinimumSize(new Dimension(100,43));context.setPreferredSize(new Dimension(100,48));
    ApprovedUi.layout(context,p->{ApprovedUi.bounds(controller.findingTitle,0,0,p.getWidth(),18);ApprovedUi.bounds(metadata,0,23,p.getWidth(),22);ApprovedUi.bounds(retained,0,0,0,0);});
    JPanel handoff=(JPanel)named(controller,"wb.placement.response.finding");
    if(handoff!=null){Component actions=region(handoff,BorderLayout.EAST);handoff.setPreferredSize(new Dimension(0,48));ApprovedUi.layout(handoff,p->{int width=actions==null?0:actions.getPreferredSize().width,height=actions==null?0:Math.min(48,actions.getPreferredSize().height);ApprovedUi.bounds(context,0,0,Math.max(80,p.getWidth()-width-12),48);ApprovedUi.bounds(actions,p.getWidth()-width,(48-height)/2,width,height);});}
    Runnable refresh=()->{
      String method="",path="",where="";boolean selected=false;int view=controller.findingTable.getSelectedRow();
      if(view>=0&&view<controller.findingTable.getRowCount())try{
        int row=controller.findingTable.convertRowIndexToModel(view);
        if(row>=0&&row<controller.findings.getRowCount()){
          selected=true;String url=String.valueOf(controller.findings.getValueAt(row,1));where=String.valueOf(controller.findings.getValueAt(row,2));
          try{java.net.URI uri=java.net.URI.create(url);path=uri.getRawPath();if(path==null||path.isEmpty())path="/";}catch(IllegalArgumentException invalid){path=url;}
          if(row<controller.displayedFindingKeys.size()){var packet=controller.displayedFindingPackets.get(controller.displayedFindingKeys.get(row));if(packet!=null&&packet.request()!=null)method=java.util.Objects.toString(packet.request().method(),"");}
        }
      }catch(IllegalArgumentException|IndexOutOfBoundsException ignored){}
      request.setText(method.isBlank()?path:method+" "+path);location.setText(where);candidate.setVisible(selected);
      metadata.setToolTipText(controller.findingContext.getText());metadata.getAccessibleContext().setAccessibleDescription(controller.findingContext.getText());metadata.revalidate();
    };
    controller.findingContext.addPropertyChangeListener("text",event->refresh.run());controller.findingTable.getSelectionModel().addListSelectionListener(event->SwingUtilities.invokeLater(refresh));controller.findings.addTableModelListener(event->SwingUtilities.invokeLater(refresh));refresh.run();
  }

  private static JPanel readingMenu(PacketInspector packet,String kind){
    Component actions=packet.titleActions();AbstractButton find=(AbstractButton)named(actions,packet.getName()+".search.open");
    List<AbstractButton> sources=new ArrayList<>(packet.tabs);sources.add(packet.copy);sources.add(packet.wrap);if(find!=null)sources.add(find);sources.add(packet.more);
    JPanel menu=WorkflowUi.menu("wb.approved.response.reading."+kind,"원문 보기",sources.toArray(AbstractButton[]::new));
    JPanel frame=new JPanel(new BorderLayout());frame.setBorder(BorderFactory.createEmptyBorder(6,13,6,14));frame.add(menu);return frame;
  }

  static void data(JPanel page){
    if(Boolean.TRUE.equals(page.getClientProperty("wb.reading.data")))return;page.putClientProperty("wb.reading.data",true);
    Component convert=named(page,"wb.data.codec.convert");if(convert instanceof JButton button){button.setIcon(new ReferenceIcon(WorkspaceIcons.of("arrow"),15));button.setIconTextGap(6);}
    for(String id:new String[]{"wb.codec.input","wb.codec.output"}){
      Component found=named(page,id);if(!(found instanceof JPanel body))continue;
      for(Component component:all(body)){
        if(component instanceof JScrollPane scroll){scroll.putClientProperty("wb.approved.scroll.flat",true);scroll.getVerticalScrollBar().setUnitIncrement(23);}
        if(component instanceof JTextArea area)ApprovedCodeAreaUI.install(area);
      }
      // The existing editing document and wrap preference remain authoritative.
    }
    for(Component component:all(page))if(component instanceof JLabel label&&label.getText()!=null&&label.getText().startsWith("입력과 출력은 각각 보존")){label.setIcon(INFO);label.setIconTextGap(7);}
  }

  private static final Icon INFO=new Icon(){
    public int getIconWidth(){return 14;}public int getIconHeight(){return 14;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(kr.packetui.VisualTheme.muted());g.setStroke(new BasicStroke(1f));g.drawOval(x+1,y+1,11,11);g.drawLine(x+7,y+6,x+7,y+9);g.fillOval(x+6,y+3,2,2);g.dispose();}
  };
  private record ReferenceIcon(Icon delegate,int size) implements Icon{
    public int getIconWidth(){return size;}public int getIconHeight(){return size;}
    public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.translate(x,y);g.scale((double)size/delegate.getIconWidth(),(double)size/delegate.getIconHeight());delegate.paintIcon(c,g,0,0);g.dispose();}
  }
}
