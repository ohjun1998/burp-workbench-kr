package kr.workbench;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.RequestOptions;
import burp.api.montoya.http.RedirectionMode;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.awt.event.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Independent request drafts. Opening a window never submits a request. */
final class JsRepeater implements AutoCloseable {
  final Component owner;
  final MontoyaApi api;
  final List<Session> sessions=new ArrayList<>();
  final List<JDialog> windows=new ArrayList<>();
  WorkbenchExtension.DialogOpener opener=JsRepeater::window;
  private boolean closed;

  JsRepeater(Component owner,MontoyaApi api) { this.owner=owner;this.api=api; }

  void open(List<JsRequestDraft.Draft> drafts) {
    if(closed || drafts.isEmpty())return;
    Session session=new Session(api,drafts);
    sessions.add(session);
    String title="Repeater · "+drafts.get(0).method()+" "+URI.create(drafts.get(0).url()).getRawPath();
    JDialog[] handle={null};
    try {
      handle[0]=opener.open(owner,title,session,false,()->{
        session.close();sessions.remove(session);windows.remove(handle[0]);
      });
      if(handle[0]!=null)windows.add(handle[0]);
    } catch(RuntimeException error) { session.close();sessions.remove(session);throw error; }
  }

  private static JDialog window(Component owner,String title,Component body,boolean modal,Runnable closed) {
    if(GraphicsEnvironment.isHeadless())return null;
    JDialog dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.MODELESS);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.setContentPane((Container)body);dialog.setMinimumSize(new Dimension(780,440));
    dialog.setSize(1120,720);dialog.setLocationRelativeTo(owner);
    dialog.addWindowListener(new WindowAdapter() { public void windowClosed(WindowEvent event) { closed.run(); } });
    dialog.setVisible(true);return dialog;
  }

  @Override public void close() {
    closed=true;
    for(Session session:List.copyOf(sessions))session.close();sessions.clear();
    for(JDialog window:List.copyOf(windows))window.dispose();windows.clear();
  }

  static final class Session extends JPanel implements AutoCloseable {
    final MontoyaApi api;
    final PacketInspector request=new PacketInspector("wb.js.repeater.request",true);
    final PacketInspector response=new PacketInspector("wb.js.repeater.response");
    final JTextField target=new JTextField(24);
    final JButton send=new JButton("Send"),previous=new JButton("←"),next=new JButton("→");
    final JLabel result=new JLabel("전송 전"),note=new JLabel("요청은 Raw에서 편집 · REPLACE_ME 값과 인증을 확인하세요.");
    final List<Entry> entries=new ArrayList<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"Workbench-JS-Repeater");t.setDaemon(true);return t;});
    volatile Future<?> pending;
    volatile boolean closed;
    boolean busy,loading;
    int index;

    record Entry(String target,byte[] request,byte[] response,int responseLength,String decoded,String status,boolean sent) {
      Entry { request=request.clone();response=response.clone(); }
    }

    Session(MontoyaApi api,List<JsRequestDraft.Draft> drafts) {
      super(new BorderLayout(6,6));this.api=api;setName("wb.js.repeater.window");
      setBorder(BorderFactory.createEmptyBorder(10,10,8,10));
      send.setName("wb.js.repeater.send");target.setName("wb.js.repeater.target");
      previous.setName("wb.js.repeater.previous");next.setName("wb.js.repeater.next");
      previous.setToolTipText("이전 요청·응답");next.setToolTipText("다음 요청·응답");
      target.setToolTipText("전송 대상 Origin · 프로토콜, 호스트, 포트");
      JPanel action=new JPanel(new BorderLayout(8,0));
      action.add(WorkbenchUi.row(send,previous,next),BorderLayout.WEST);
      action.add(target);action.add(result,BorderLayout.EAST);add(action,BorderLayout.NORTH);
      JSplitPane messages=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
          WorkbenchUi.card("요청 REQUEST",request),WorkbenchUi.card("응답 RESPONSE",response),.5);
      messages.setName("wb.js.repeater.messages");add(messages);
      note.putClientProperty("wb.font.size",11);note.setName("wb.js.repeater.note");add(note,BorderLayout.SOUTH);
      result.putClientProperty("wb.font.size",12);result.setName("wb.js.repeater.result");
      WorkbenchUi.style(this);WorkbenchUi.primary(send);
      for(var draft:drafts) {
        URI uri=URI.create(draft.url());
        entries.add(new Entry(uri.getScheme()+"://"+uri.getRawAuthority(),draft.wire().getBytes(StandardCharsets.UTF_8),new byte[0],0,null,"전송 전",false));
      }
      request.setNativeAction(this::exportNative);
      request.more.setToolTipText("편집한 요청을 Burp Repeater에 복사 · 전송 없음");
      send.addActionListener(event->send());previous.addActionListener(event->navigate(-1));next.addActionListener(event->navigate(1));
      KeyStroke shortcut=KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,InputEvent.CTRL_DOWN_MASK);
      Action submit=new AbstractAction(){public void actionPerformed(ActionEvent event){send();}};
      getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcut,"wb.js.repeater.send");
      getActionMap().put("wb.js.repeater.send",submit);
      for(JComponent editor:new JComponent[]{request.text,response.text,request.search,response.search,target}){
        editor.getInputMap(WHEN_FOCUSED).put(shortcut,"wb.js.repeater.send");editor.getActionMap().put("wb.js.repeater.send",submit);
      }
      send.setToolTipText("요청 전송 · Ctrl+Space");
      request.editedAction=this::draftChanged;
      target.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
        public void insertUpdate(javax.swing.event.DocumentEvent e){draftChanged();}public void removeUpdate(javax.swing.event.DocumentEvent e){draftChanged();}public void changedUpdate(javax.swing.event.DocumentEvent e){}
      });
      load(0);
    }

    void load(int row) {
      loading=true;index=row;Entry entry=entries.get(index);target.setText(entry.target());
      request.setPacket(entry.request(),null);
      response.setPacket(entry.response(),entry.decoded(),entry.responseLength());result.setText(entry.status());
      loading=false;updateButtons();
    }

    private void draftChanged(){
      if(closed||loading||busy)return;
      response.setPacket(new byte[0],null);result.setText("편집됨 · 미전송");
      note.setText("요청이 변경되었습니다. Send 또는 Ctrl+Space로 응답을 확인하세요.");
    }

    private void updateButtons() {
      send.setEnabled(!closed&&!busy&&api!=null);
      previous.setEnabled(!busy&&index>0);next.setEnabled(!busy&&index+1<entries.size());
      target.setEnabled(!busy);request.text.setEnabled(!busy);
      request.more.setEnabled(!busy&&api!=null);
    }

    private void saveEdits(boolean sending) {
      Entry old=entries.get(index);byte[] wire=request.editedBytes();String origin=target.getText().strip();
      boolean changed=!origin.equals(old.target())||!Arrays.equals(wire,old.request());
      if(old.sent()&&(changed||sending)) {
        entries.add(new Entry(origin,wire,new byte[0],0,null,"전송 전",false));index=entries.size()-1;
      } else if(changed)entries.set(index,new Entry(origin,wire,new byte[0],0,null,"전송 전",false));
    }

    private void navigate(int direction) {
      if(closed||busy)return;
      try {saveEdits(false);load(Math.max(0,Math.min(entries.size()-1,index+direction)));}
      catch(Exception error){showError(error);}
    }

    HttpRequest preparedRequest() {
      URI origin;
      try {origin=URI.create(target.getText().strip());}
      catch(Exception error){throw new IllegalArgumentException("전송 대상 URL을 확인하세요.");}
      if(!List.of("http","https").contains(origin.getScheme())||origin.getHost()==null||origin.getUserInfo()!=null
          ||origin.getRawQuery()!=null||origin.getRawFragment()!=null
          ||!(origin.getPath().isEmpty()||origin.getPath().equals("/")))
        throw new IllegalArgumentException("전송 대상에는 HTTP(S) Origin만 입력하세요.");
      if(origin.getHost().equalsIgnoreCase("REPLACE-HOST.invalid"))throw new IllegalArgumentException("전송 대상 호스트를 먼저 확인하세요.");
      String wire=new String(request.editedBytes(),StandardCharsets.UTF_8);
      String checked=wire;
      for(int pass=0;pass<2;pass++)try{String decoded=java.net.URLDecoder.decode(checked,StandardCharsets.UTF_8);if(decoded.equals(checked))break;checked=decoded;}catch(IllegalArgumentException ignored){break;}
      checked=checked.replaceAll("(?i)\\\\u005[fF]","_");
      if(java.util.regex.Pattern.compile("(?i)REPLACE-HOST[.]invalid|REPLACE_(?:ME|METHOD|PARAMETER|PARAM|PATH|BODY)\\b|\\{dynamic\\}").matcher(checked).find())
        throw new IllegalArgumentException("미확정 메서드·경로·파라미터가 남아 있습니다. Raw에서 실제 값을 확인하세요.");
      int boundary=wire.indexOf("\r\n\r\n"),separator=4;
      if(boundary<0){boundary=wire.indexOf("\n\n");separator=2;}
      if(boundary<0)throw new IllegalArgumentException("요청 헤더와 본문 사이에 빈 줄이 필요합니다.");
      String head=wire.substring(0,boundary).replace("\r\n","\n");
      String[] line=head.split("\n",2)[0].split(" ",3);
      if(line.length!=3||!line[0].matches("[A-Z][A-Z0-9_-]{0,31}")||line[0].equals("REPLACE_METHOD")
          ||!line[1].startsWith("/")||line[1].contains("REPLACE_PATH")||line[1].contains("{")
          ||!line[2].matches("HTTP/(?:1\\.[01]|2)"))
        throw new IllegalArgumentException("Raw에서 메서드와 경로의 미해결 값을 확인하세요.");
      String effective=origin.getScheme()+"://"+origin.getRawAuthority()+line[1];
      if(!WorkbenchScope.contains(api,effective))throw new IllegalArgumentException("Scope 밖 전송 대상입니다. · "+WorkbenchScope.reason(api,effective));
      String normalized=head.replace("\n","\r\n")+"\r\n\r\n"+wire.substring(boundary+separator);
      boolean tls=origin.getScheme().equals("https");
      HttpService service=HttpService.httpService(origin.getHost(),origin.getPort()<0?(tls?443:80):origin.getPort(),tls);
      HttpRequest prepared=HttpRequest.httpRequest(service,ByteArray.byteArray(normalized.getBytes(StandardCharsets.UTF_8)));
      if(prepared==null)throw new IllegalArgumentException("요청 패킷을 해석할 수 없습니다.");
      if(prepared.headerValue("Transfer-Encoding")==null)prepared=prepared.withBody(prepared.body());
      if(!WorkbenchScope.contains(api,prepared))throw new IllegalArgumentException("Scope 밖 요청입니다. · "+WorkbenchScope.reason(api,prepared));
      return prepared;
    }

    void send() {
      if(closed||busy||api==null)return;
      try {
        HttpRequest snapshot=preparedRequest();saveEdits(true);
        int destination=index;Entry original=entries.get(destination);
        byte[] sentRequest=snapshot.toByteArray().getBytes();
        String actualOrigin=target.getText().strip().replaceAll("/$","");
        URI requestUrl=URI.create(snapshot.url());
        String effective=actualOrigin+requestUrl.getRawPath()+(requestUrl.getRawQuery()==null?"":"?"+requestUrl.getRawQuery());
        response.setPacket(new byte[0],null);busy=true;result.setText("전송 중…");note.setText("응답 대기 중");updateButtons();
        pending=worker.submit(()->{
          long start=System.nanoTime();
          try {
            if(closed)return;
            if(!WorkbenchScope.contains(api,effective)||!WorkbenchScope.contains(api,snapshot))throw new IllegalArgumentException("전송 전 Scope에서 제외된 요청입니다.");
            var exchange=api.http().sendRequest(snapshot,RequestOptions.requestOptions().withResponseTimeout(20000).withRedirectionMode(RedirectionMode.NEVER));
            if(exchange==null||exchange.response()==null)throw new IllegalStateException("응답이 없습니다.");
            var reply=exchange.response();var bytes=reply.toByteArray();int length=bytes.length();
            byte[] preview=PacketInspector.prefix(bytes);
            String decoded=reply.body().length()<=2*1024*1024?PacketFormat.decodedResponse(reply):null;
            if(decoded!=null&&decoded.length()>PacketInspector.PREVIEW_BYTES)decoded=decoded.substring(0,PacketInspector.PREVIEW_BYTES);
            String display=reply.statusCode()+" · "+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+" ms · "+size(length);
            Entry complete=new Entry(original.target(),sentRequest,preview,length,decoded,display,true);
            SwingUtilities.invokeLater(()->{
              if(closed)return;
              entries.set(destination,complete);busy=false;load(destination);
              note.setText("전송 완료 · 응답은 수신한 요청과 함께 보관합니다.");
            });
          } catch(Exception error) {SwingUtilities.invokeLater(()->{if(!closed){busy=false;updateButtons();showError(error);}});}
        });
      } catch(Exception error) {busy=false;updateButtons();showError(error);}
    }

    private static String size(int bytes) {return bytes<1024?bytes+" B":String.format(java.util.Locale.ROOT,"%.1f KiB",bytes/1024d);}
    private void exportNative() {
      if(closed||busy||api==null)return;
      try {api.repeater().sendToRepeater(preparedRequest(),"JS 편집 초안");note.setText("Burp Repeater에 복사 완료 · 전송 없음");}
      catch(Exception error){showError(error);}
    }
    private void showError(Exception error) {
      response.setPacket(new byte[0],null);
      note.setText(error.getMessage()==null?"요청 처리를 완료하지 못했습니다.":error.getMessage());
      note.setToolTipText(note.getText());result.setText("확인 필요");
      Diagnostics.failure(this,"JS Repeater",error);
    }
    @Override public void close() {
      if(closed)return;closed=true;
      if(pending!=null)pending.cancel(true);worker.shutdownNow();entries.clear();
      request.close();response.close();target.setText("");updateButtons();
    }
  }
}

