package kr.workbench;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** A masked, read-only snapshot of the analysis selected when its window was opened. */
final class JsAnalysisDetails extends JPanel implements Scrollable {
  final JTabbedPane sections=new JTabbedPane();
  final JsAnalysis.Result snapshot;
  final String source;
  final JTable apis=new JTable(JsAnalysisPanel.model("메서드","URL / 경로","사용 맥락","쿼리·params 이름","본문 키","헤더 이름","줄"));

  JsAnalysisDetails(String source,JsAnalysis.Result snapshot){
    super(new BorderLayout(10,12));this.source=JsMiner.safeUrl(source);this.snapshot=snapshot;
    setName("wb.js.details");setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
    JPanel header=new JPanel(new BorderLayout(0,8));
    JLabel title=new JLabel("분석 결과 요약");title.putClientProperty("wb.font.size",20);title.putClientProperty("wb.font.heading",true);header.add(title,BorderLayout.NORTH);
    int api=snapshot==null?0:snapshot.endpoints().size(),secret=snapshot==null?0:snapshot.secrets().size();
    JsMiner.Result miner=snapshot==null?new JsMiner.Result(List.of(),List.of(),List.of(),List.of(),List.of(),""):snapshot.miner();
    String message=snapshot==null?"분석 목록에서 응답을 선택하면 결과 요약과 발견 근거를 확인할 수 있습니다.":"URL·API "+api+"개 · 토큰·키 후보 "+secret+"개를 찾았습니다. 후보의 도달 여부와 유효성은 직접 확인하세요.";
    JPanel context=new JPanel(new BorderLayout(0,5));
    JTextArea origin=prose(this.source.isBlank()?"선택한 출처 없음":"출처  "+this.source);origin.setName("wb.js.details.source");origin.putClientProperty("wb.font.size",12);context.add(origin,BorderLayout.NORTH);
    JTextArea summary=prose(message);summary.setName("wb.js.details.summary");context.add(summary);
    header.add(context);
    JPanel metrics=new JPanel(new GridLayout(1,4,8,0));
    metrics.add(metric("URL · API",api,"wb.js.details.count.api"));metrics.add(metric("토큰 · 키 후보",secret,"wb.js.details.count.secret"));
    metrics.add(metric("도메인",miner.domains().size(),"wb.js.details.count.domain"));metrics.add(metric("복원 원본",miner.recovered().size(),"wb.js.details.count.recovered"));
    header.add(metrics,BorderLayout.SOUTH);add(header,BorderLayout.NORTH);
    DefaultTableModel apiModel=(DefaultTableModel)apis.getModel();
    if(snapshot!=null)for(var item:snapshot.endpoints())apiModel.addRow(new Object[]{item.method(),item.url(),item.kind(),item.parameters(),item.bodyKeys(),item.headers(),item.line()});
    apis.setName("wb.js.details.api.table");sections.addTab("URL · API",table(apis,new int[]{75,360,170,170,170,160,50},"발견된 URL·API가 없습니다."));
    JTable tokens=new JTable(JsAnalysisPanel.model("후보 종류","값 · 가림","지문","줄","판단 근거"));
    if(snapshot!=null)for(var item:snapshot.secrets())((DefaultTableModel)tokens.getModel()).addRow(new Object[]{item.rule(),item.masked(),item.fingerprint(),item.line(),item.confidence()});
    tokens.setName("wb.js.details.secret.table");sections.addTab("토큰 · 키 후보",table(tokens,new int[]{210,140,170,50,350},"발견된 토큰·키 후보가 없습니다."));
    JTable hosts=new JTable(JsAnalysisPanel.model("분류","호스트 / URL","출처 관계 / 서비스","줄"));
    for(var item:miner.domains())((DefaultTableModel)hosts.getModel()).addRow(new Object[]{"도메인",item.host(),item.relation(),item.line()});
    for(var item:miner.clouds())((DefaultTableModel)hosts.getModel()).addRow(new Object[]{"클라우드",JsMiner.safeUrl(item.url()),item.provider(),item.line()});
    hosts.setName("wb.js.details.host.table");sections.addTab("호스트 · 클라우드",table(hosts,new int[]{100,400,260,55},"발견된 호스트·클라우드 URL이 없습니다."));
    JTable resources=new JTable(JsAnalysisPanel.model("분류","이름 / 참조","분석 상태 / 근거"));
    for(var item:miner.dependencies())((DefaultTableModel)resources.getModel()).addRow(new Object[]{"의존성",item.name(),item.origin()+" · "+item.status()});
    for(var item:miner.maps())((DefaultTableModel)resources.getModel()).addRow(new Object[]{"소스맵",JsMiner.safeUrl(item.url()),item.state()});
    for(var item:miner.recovered())((DefaultTableModel)resources.getModel()).addRow(new Object[]{"복원 원본",item.name(),item.content().length()+"자 · "+JsMiner.safeUrl(item.mapUrl())});
    resources.setName("wb.js.details.resource.table");sections.addTab("의존성 · 소스맵",table(resources,new int[]{100,390,330},"발견된 의존성·소스맵이 없습니다."));
    sections.setName("wb.js.details.sections");add(sections);
    JTextArea state=prose(snapshot==null?"분석 전 · 추가 요청 없음":snapshot.status()+"\n이 창을 열었을 때 선택한 분석입니다. 현재 선택을 다시 보려면 분석 상세를 누르세요.");
    state.setName("wb.js.details.state");state.putClientProperty("wb.font.size",11);add(state,BorderLayout.SOUTH);
    WorkbenchUi.style(this);
  }
  private static JTextArea prose(String text){JTextArea area=new JTextArea(text);area.putClientProperty("wb.prose",true);area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setBorder(null);return area;}
  private static JPanel metric(String title,int count,String name){
    JLabel number=new JLabel(Integer.toString(count));number.setName(name);number.putClientProperty("wb.font.size",26);number.putClientProperty("wb.font.heading",true);number.setBorder(BorderFactory.createEmptyBorder(2,10,8,10));
    JPanel body=new JPanel(new BorderLayout());body.add(number);JPanel card=WorkbenchUi.card(title,body);card.setMinimumSize(new Dimension(100,76));return card;
  }
  private static Component table(JTable table,int[] widths,String empty){
    table.setAutoCreateRowSorter(true);table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setRowHeight(29);
    for(int i=0;i<widths.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    if(table.getRowCount()==0){JPanel blank=new JPanel(new GridBagLayout());JLabel label=new JLabel(empty);label.putClientProperty("wb.font.size",13);blank.add(label);return blank;}
    return new JScrollPane(table);
  }
  static JDialog window(Component owner,String title,Component content,boolean modal,Runnable closed){
    if(GraphicsEnvironment.isHeadless())return null;
    JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setContentPane(WorkbenchUi.settingsBody(content,dialog::dispose));
    dialog.setMinimumSize(new Dimension(620,400));dialog.setSize(980,640);dialog.setLocationRelativeTo(owner);
    dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosed(java.awt.event.WindowEvent event){closed.run();}});dialog.setVisible(true);return dialog;
  }
  public Dimension getPreferredScrollableViewportSize(){return new Dimension(900,540);}
  @Override public Dimension getPreferredSize(){return new Dimension(900,520);}
  public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 29;}
  public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(29,visible.height-29);}
  public boolean getScrollableTracksViewportWidth(){return true;}
  public boolean getScrollableTracksViewportHeight(){return getParent()!=null&&getParent().getHeight()>=430;}
}
