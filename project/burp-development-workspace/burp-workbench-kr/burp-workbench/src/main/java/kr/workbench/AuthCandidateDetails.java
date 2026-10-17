package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** One pinned credential's editable account card and masked authentication summary. */
final class AuthCandidateDetails extends JPanel {
  final AuthComparePanel owner;
  final JLabel identity=new JLabel(),origin=new JLabel();
  final JTextField name=new JTextField(18),login=new JTextField(18),userId=new JTextField(18),role=new JTextField(18),email=new JTextField(24);
  final DefaultTableModel headers=AuthComparePanel.model("인증 헤더","값 · 마스킹");
  final JTable table=new AuthLayout.Table(headers);
  final JLabel feedback=new JLabel("직접 매핑한 정보이며 신원 검증 완료를 의미하지 않습니다.");
  AuthComparePanel.Candidate candidate;
  AuthCandidateDetails(AuthComparePanel owner) {
    super(new BorderLayout(8,8));this.owner=owner;setName("wb.auth.candidate.detail");setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
    identity.putClientProperty("wb.font.heading",true);identity.putClientProperty("wb.font.size",20);
    JPanel form=new JPanel(new GridBagLayout());String[] labels={"표시 이름","로그인 ID","사용자 ID","역할","이메일"};
    JTextField[] fields={name,login,userId,role,email};
    int[] rows={0,1,2,0,1},groups={0,0,0,1,1};
    for(int i=0;i<fields.length;i++){
      GridBagConstraints c=new GridBagConstraints();c.gridx=groups[i]*2;c.gridy=rows[i];c.anchor=GridBagConstraints.WEST;c.insets=new Insets(5,8,5,8);form.add(new JLabel(labels[i]),c);
      c.gridx++;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;fields[i].setColumns(12);fields[i].setMinimumSize(new Dimension(110,30));form.add(fields[i],c);
    }
    JPanel upper=TargetWindow.options(WorkbenchUi.row(identity,new JLabel("수동 매핑")),WorkbenchUi.card("사용자 계정",form),WorkbenchUi.row(new JLabel("Origin"),origin));
    add(upper,BorderLayout.NORTH);table.setName("wb.auth.candidate.headers");table.putClientProperty("wb.table.compact",true);
    add(WorkbenchUi.card("인증 정보",new JScrollPane(table)));
    JButton save=new JButton("매핑 저장"),close=new JButton("닫기");
    save.addActionListener(e->{try{owner.mapCandidate(candidate,new AuthAccount(name.getText(),login.getText(),userId.getText(),role.getText(),email.getText()));feedback.setText("매핑 저장 완료 · 다음 비교에 반영");}catch(IllegalArgumentException ex){feedback.setText(ex.getMessage());}});
    close.addActionListener(e->owner.candidateWindow.closeWindow());
    JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));buttons.add(save);buttons.add(close);
    add(TargetWindow.options(feedback,buttons),BorderLayout.SOUTH);WorkbenchUi.style(this);
  }
  void load(AuthComparePanel.Candidate candidate) {
    this.candidate=candidate;AuthAccount account=owner.accounts.getOrDefault(candidate.key(),new AuthAccount("","","","",""));
    identity.setText(account.label());identity.putClientProperty("wb.font.heading",true);identity.putClientProperty("wb.font.size",20);origin.setText(candidate.origin());name.setText(account.name());login.setText(account.login());userId.setText(account.userId());role.setText(account.role());email.setText(account.email());
    headers.setRowCount(0);candidate.headers().forEach((key,value)->headers.addRow(new Object[]{key,AuthComparePanel.maskCredential(key,value)}));
    feedback.setText("직접 매핑한 정보이며 신원 검증 완료를 의미하지 않습니다.");
  }
  void clear(){candidate=null;for(JTextField f:new JTextField[]{name,login,userId,role,email})f.setText("");identity.setText("");origin.setText("");headers.setRowCount(0);}
  static JDialog window(Component owner,String title,Component content,boolean modal,Runnable closed) {
    if(GraphicsEnvironment.isHeadless())return null;
    JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setContentPane((Container)content);dialog.setResizable(true);
    boolean candidate=content instanceof AuthCandidateDetails;
    dialog.setMinimumSize(new Dimension(candidate?700:840,520));dialog.setSize(candidate?820:1180,candidate?600:780);dialog.setLocationRelativeTo(owner);
    dialog.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){closed.run();}});dialog.setVisible(true);return dialog;
  }
}
