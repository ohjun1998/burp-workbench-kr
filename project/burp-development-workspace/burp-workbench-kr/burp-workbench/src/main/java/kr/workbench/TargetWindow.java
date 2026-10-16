package kr.workbench;
import burp.api.montoya.MontoyaApi;import javax.swing.*;import java.awt.*;import java.util.function.*;

/** Modeless target settings keep editable URLs outside the main workspace. */
final class TargetWindow implements AutoCloseable {
 final Component owner;final String title;final Supplier<Component> content;private JDialog window;private boolean closed;private long generation;
 WorkbenchExtension.DialogOpener opener=WorkbenchUi::settingsWindow;
 TargetWindow(Component owner,String title,Supplier<Component> content){this.owner=owner;this.title=title;this.content=content;}
 void open(){if(closed)return;if(window!=null&&window.isDisplayable()){window.toFront();window.requestFocus();return;}long token=++generation;window=opener.open(owner,title,content.get(),false,()->{if(token==generation)window=null;});}
 JButton button(){JButton b=new JButton("대상 설정");b.setName("wb.target.settings");b.addActionListener(e->open());return b;}
 static JLabel bind(JTextField field,String empty){JLabel label=new JLabel();Runnable update=()->show(label,field.getText(),empty);field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){update.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){update.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){update.run();}});update.run();return label;}
 static void show(JLabel label,String value,String empty){String safe=value==null?"":value.strip().split("[?#]",2)[0];label.setText(safe.isBlank()?empty:"대상 · "+(safe.length()>64?safe.substring(0,61)+"…":safe));label.setToolTipText(safe.isBlank()?empty:safe);}
 static JPanel options(Component... rows){JPanel panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));for(Component row:rows)panel.add(row);WorkbenchUi.style(panel);return panel;}
 static JPanel scopes(MontoyaApi api,Consumer<String> choose){JComboBox<String> choices=new JComboBox<>();choices.setName("wb.target.scope.choices");choices.addItem("SCOPE에서 대상 선택");for(var rule:WorkbenchScope.registeredRules(api))choices.addItem(rule.url());choices.addActionListener(e->{if(choices.getSelectedIndex()>0)choose.accept((String)choices.getSelectedItem());});choices.setPreferredSize(new Dimension(450,30));return WorkbenchUi.row(new JLabel("등록된 SCOPE"),choices);}
 void closeWindow(){generation++;if(window!=null){window.dispose();window=null;}}
 @Override public void close(){closed=true;closeWindow();}
}
