package kr.workbench;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.*;
import java.util.List;
/** Display-only source markings. Offsets always refer to unchanged captured text. */
final class JsFindingNavigator {
 record Finding(int start,int length,String type,int tab,int row){}
 static final Color URL=new Color(154,201,255),SECRET=new Color(255,177,174),RECON=new Color(185,224,187),MAP=new Color(222,189,255);
 final JTextArea source;final JLabel counter;final JComboBox<String> filter=new JComboBox<>(new String[]{"전체 감지","URL/API","시크릿","도메인/의존성","소스맵"});
 final JButton previous=new JButton("←"),next=new JButton("→");List<Finding> all=List.of(),visible=List.of();int cursor=-1;String original="";boolean active;
 final java.util.function.Consumer<Finding> selected;
 JsFindingNavigator(JTextArea source,JLabel counter,java.util.function.Consumer<Finding> selected,Runnable prepare){this.source=source;this.counter=counter;this.selected=selected;previous.setToolTipText("이전 감지 위치");next.setToolTipText("다음 감지 위치");previous.addActionListener(e->{prepare.run();move(-1);});next.addActionListener(e->{prepare.run();move(1);});filter.addActionListener(e->{applyFilter();if(active)paint();});reset();}
 static Color color(String type){if(WorkbenchUi.dark())return type.equals("URL/API")?new Color(36,75,125):type.equals("시크릿")?new Color(119,48,53):type.equals("소스맵")?new Color(83,56,119):new Color(40,87,62);return type.equals("URL/API")?URL:type.equals("시크릿")?SECRET:type.equals("소스맵")?MAP:RECON;}
 static void exact(List<Finding> findings,String text,String value,String type,int tab,int row){if(value==null||value.isEmpty())return;int start=text.indexOf(value);if(start>=0)findings.add(new Finding(start,value.length(),type,tab,row));}
 void bind(String text,JsAnalysis.Result result,boolean sourceMatches){original=text;active=sourceMatches;var list=new ArrayList<Finding>();
  for(int i=0;i<result.endpoints().size();i++){var e=result.endpoints().get(i);if(e.start()>=0&&e.length()>0)list.add(new Finding(e.start(),e.length(),"URL/API",0,i));}
  for(int i=0;i<result.secrets().size();i++){var s=result.secrets().get(i);if(s.start()>=0&&s.sourceLength()>0)list.add(new Finding(s.start(),s.sourceLength(),"시크릿",1,i));}
  for(int i=0;i<result.miner().domains().size();i++)exact(list,text,result.miner().domains().get(i).host(),"도메인/의존성",2,i);
  for(int i=0;i<result.miner().clouds().size();i++)exact(list,text,result.miner().clouds().get(i).url(),"도메인/의존성",3,i);
  for(int i=0;i<result.miner().dependencies().size();i++)exact(list,text,result.miner().dependencies().get(i).name(),"도메인/의존성",4,i);
  var maps=java.util.regex.Pattern.compile("(?m)sourceMappingURL\\s*=\\s*([^\\s*]+)").matcher(text);int row=0;while(maps.find()&&row<result.miner().maps().size())list.add(new Finding(maps.start(1),maps.end(1)-maps.start(1),"소스맵",5,row++));
  all=list.stream().filter(f->f.start>=0&&f.start+f.length<=text.length()).sorted(Comparator.comparingInt(Finding::start).thenComparing(Finding::type)).toList();cursor=-1;applyFilter();if(active)paint();else source.getHighlighter().removeAllHighlights();
 }
 void activate(){active=true;paint();}
 void invalidate(){active=false;source.getHighlighter().removeAllHighlights();counter.setText("원본 수정됨 · 다시 분석");}
 void applyFilter(){String type=Objects.toString(filter.getSelectedItem(),"");visible=all.stream().filter(f->type.equals("전체 감지")||f.type.equals(type)).toList();cursor=-1;previous.setEnabled(!visible.isEmpty());next.setEnabled(!visible.isEmpty());counter.setText("0 / "+visible.size());}
 void paint(){source.getHighlighter().removeAllHighlights();for(Finding f:visible)try{source.getHighlighter().addHighlight(f.start,f.start+f.length,new DefaultHighlighter.DefaultHighlightPainter(color(f.type)));}catch(BadLocationException ignored){}}
 void move(int direction){if(visible.isEmpty()||!active)return;cursor=cursor<0?(direction<0?visible.size()-1:0):Math.floorMod(cursor+direction,visible.size());Finding f=visible.get(cursor);source.setCaretPosition(f.start);try{var rect=source.modelToView2D(f.start);if(rect!=null)source.scrollRectToVisible(rect.getBounds());}catch(BadLocationException ignored){}counter.setText((cursor+1)+" / "+visible.size()+" · "+f.type);selected.accept(f);}
 void at(int tab,int row){if(!active)return;for(int i=0;i<visible.size();i++){Finding f=visible.get(i);if(f.tab==tab&&f.row==row){cursor=i;source.setCaretPosition(f.start);try{var rect=source.modelToView2D(f.start);if(rect!=null)source.scrollRectToVisible(rect.getBounds());}catch(BadLocationException ignored){}counter.setText((i+1)+" / "+visible.size()+" · "+f.type);return;}}}
 void reset(){all=List.of();visible=List.of();cursor=-1;active=false;source.getHighlighter().removeAllHighlights();previous.setEnabled(false);next.setEnabled(false);counter.setText("0 / 0");}
}
