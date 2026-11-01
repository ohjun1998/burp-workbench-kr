package kr.workbench;

import java.awt.*;
import javax.swing.*;
import javax.swing.event.*;

/** Compact projections of the dashboard's retained status and selected-result documents. */
final class ApprovedDashboard {
  private ApprovedDashboard() {}
  static void watch(JTextArea source,Runnable update){source.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){update.run();}public void removeUpdate(DocumentEvent e){update.run();}public void changedUpdate(DocumentEvent e){update.run();}});update.run();}
  static void apply(ScopeDashboardPanel page){
    JScrollPane scroll=(JScrollPane)ApprovedLayouts.n(page,"wb.scope.dashboard.scroll");scroll.putClientProperty("wb.approved.scroll.flat",true);
    JPanel context=(JPanel)ApprovedLayouts.n(page,"wb.actions.dashboard.context");
    JPanel retained=new JPanel();retained.setVisible(false);retained.add(page.run);retained.add(page.target);retained.add(page.traffic);context.add(retained,BorderLayout.SOUTH);
    JLabel state=new JLabel(),target=new JLabel(),traffic=new JLabel();ApprovedUi.chip(state);target.putClientProperty("html.disable",true);traffic.putClientProperty("wb.secondary",true);traffic.putClientProperty("wb.font.size",11);
    JPanel current=new JPanel();ApprovedUi.row(current,10);current.add(state);current.add(target);current.add(traffic);context.add(current);
    Runnable header=()->{
      state.setText(page.current.id().isEmpty()?"실행 기록 없음":switch(page.current.state()){case COMPLETED->"최근 실행 완료";case RUNNING->"점검 중";case PAUSED->"일시정지";case STOPPED->"중지·미확인";case FAILED->"실패·미확인";case CLOSED->"종료";case READY->"준비";});state.setToolTipText(page.run.getText());
      String origin=page.target.getText().replaceFirst("^SCOPE: ","");try{var uri=java.net.URI.create(origin);target.setText(uri.getHost()==null?origin:uri.getHost());}catch(IllegalArgumentException invalid){target.setText(origin);}target.setToolTipText(page.target.getText());traffic.setText(page.traffic.getText().replaceFirst("^최신 요청: ",""));traffic.setToolTipText(page.traffic.getText());current.revalidate();
    };
    for(JLabel source:new JLabel[]{page.run,page.target,page.traffic})source.addPropertyChangeListener("text",e->header.run());header.run();
    JPanel filters=(JPanel)ApprovedLayouts.n(page,"wb.actions.dashboard.filters");JLabel accountCaption=null;for(Component child:filters.getComponents())if(child instanceof JLabel label&&label.getText().equals("계정"))accountCaption=label;final JLabel accountLabel=accountCaption;
    JLabel importanceLabel=new JLabel("중요도"),count=new JLabel();for(JLabel label:new JLabel[]{accountLabel,importanceLabel})label.putClientProperty("wb.font.size",11);ApprovedUi.chip(count);filters.add(importanceLabel);filters.add(count);
    JPanel filterSource=new JPanel();filterSource.setVisible(false);filterSource.add(page.filterSummary);filters.add(filterSource);
    Runnable counted=()->{count.setText(page.filterSummary.getText().contains("집계")?"결과 집계 중":ScopeInspectionDashboard.label(page.group)+" "+page.table.getRowCount());count.setToolTipText(page.filterSummary.getText());};page.filterSummary.addPropertyChangeListener("text",e->counted.run());page.model.addTableModelListener(e->SwingUtilities.invokeLater(counted));counted.run();
    ApprovedUi.layout(filters,p->{ApprovedUi.bounds(accountLabel,0,0,29,34);ApprovedUi.bounds(page.account,29,2,180,31);ApprovedUi.bounds(importanceLabel,217,0,39,34);ApprovedUi.bounds(page.importance,257,2,180,31);ApprovedUi.bounds(count,445,6,Math.min(250,count.getPreferredSize().width),22);ApprovedUi.bounds(page.reset,p.getWidth()-70,2,70,31);});
    for(var metric:page.cards.values())metric.putClientProperty("wb.approved.metric",true);
    JSplitPane split=(JSplitPane)ApprovedLayouts.n(page,"wb.scope.dashboard.results.split");JPanel detailCard=(JPanel)split.getRightComponent();JPanel details=(JPanel)ApprovedLayouts.region(detailCard,BorderLayout.CENTER);JPanel facts=(JPanel)ApprovedLayouts.region(details,BorderLayout.NORTH);
    page.detailTitle.setBorder(null);page.detailTitle.putClientProperty("wb.font.size",13);JLabel caption=new JLabel("선택 결과");caption.putClientProperty("wb.font.size",11);caption.putClientProperty("wb.secondary",true);
    JPanel heading=new JPanel(new BorderLayout(0,6));heading.setBorder(BorderFactory.createEmptyBorder(20,14,12,14));heading.add(caption,BorderLayout.NORTH);heading.add(page.detailTitle);heading.setPreferredSize(new Dimension(0,76));
    JPanel factsView=new JPanel(new GridLayout(4,1));factsView.setBorder(BorderFactory.createEmptyBorder(0,14,24,14));JLabel[] values=new JLabel[4];String[] captions={"요청","관측 계정","상태","확실성"};
    for(int i=0;i<4;i++){JPanel row=new JPanel(new BorderLayout());JLabel key=new JLabel(captions[i]);key.putClientProperty("wb.font.size",12);key.putClientProperty("wb.secondary",true);key.setPreferredSize(new Dimension(110,31));values[i]=new JLabel();values[i].putClientProperty("html.disable",true);values[i].putClientProperty("wb.font.size",12);row.add(key,BorderLayout.WEST);row.add(values[i]);factsView.add(row);}
    factsView.setPreferredSize(new Dimension(0,148));watch(page.detailFacts,()->{String[] lines=page.detailFacts.getText().split("\n",-1);factsView.setVisible(!page.detailFacts.getText().isBlank());for(int i=0;i<4;i++){String value=i<lines.length?lines[i].replaceFirst("^[^:]+: ",""):"";if(i==0){int space=value.indexOf(' ');if(space>=0)value=value.substring(0,space)+" "+ApprovedUi.path(value.substring(space+1));}values[i].setText(value);values[i].setToolTipText(i<lines.length?lines[i]:"");}});
    JPanel selected=new JPanel(new BorderLayout());selected.add(heading,BorderLayout.NORTH);selected.add(factsView);details.remove(facts);retained.add(page.detailFacts);details.add(selected,BorderLayout.NORTH);
    JPanel summary=(JPanel)ApprovedLayouts.region(details,BorderLayout.CENTER);JPanel provenance=(JPanel)ApprovedLayouts.region(details,BorderLayout.SOUTH);details.remove(summary);details.remove(provenance);page.detailSummary.putClientProperty("wb.approved.fact",true);page.detailSummary.setRows(0);
    JPanel column=new JPanel(new BorderLayout());JPanel content=new JPanel(new BorderLayout(0,12));content.add(summary);content.add(provenance,BorderLayout.SOUTH);column.add(content,BorderLayout.NORTH);details.add(column);details.setBorder(null);((BorderLayout)details.getLayout()).setVgap(0);
    Runnable summaryHeight=()->{int width=Math.max(240,detailCard.getWidth()-32),characters=Math.max(25,width/7),lines=0;for(String line:page.detailSummary.getText().split("\n",-1))lines+=Math.max(1,(line.length()+characters-1)/characters);summary.setPreferredSize(new Dimension(0,Math.min(270,Math.max(105,lines*20+52))));content.revalidate();};watch(page.detailSummary,summaryHeight);detailCard.addComponentListener(new java.awt.event.ComponentAdapter(){public void componentResized(java.awt.event.ComponentEvent e){summaryHeight.run();}});
  }
}
