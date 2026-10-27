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
  final List<Window> windows=new ArrayList<>();
  interface WindowOpener {Window open(Component owner,String title,Component body,boolean modal,Runnable closed);}
  WindowOpener opener=JsRepeater::window;
  private boolean closed;

  JsRepeater(Component owner,MontoyaApi api) { this.owner=owner;this.api=api; }

  void open(List<JsRequestDraft.Draft> drafts) {
    openPrepared(drafts.stream().map(draft->JsMethodSelection.choose(draft,java.util.Set.of())).toList());
  }

  void openPrepared(List<JsMethodSelection.Choice> choices) {
    List<JsRequestDraft.Draft> drafts=choices.stream().map(JsMethodSelection.Choice::draft).toList();
    if(closed || drafts.isEmpty())return;
    Session session=new Session(api,drafts,choices.stream().map(JsMethodSelection.Choice::reason).toList());
    sessions.add(session);
    String title="Repeater · "+drafts.get(0).method()+" "+URI.create(drafts.get(0).url()).getRawPath();
    Window[] handle={null};
    try {
      handle[0]=opener.open(owner,title,session,false,()->{
        session.close();sessions.remove(session);windows.remove(handle[0]);
      });
      if(handle[0]!=null)windows.add(handle[0]);
    } catch(RuntimeException error) { session.close();sessions.remove(session);throw error; }
  }

  /** Open exact captured bytes in a separate editable window without sending them. */
  void openPacket(HttpRequest request,burp.api.montoya.http.message.responses.HttpResponse response,String account) {
    if(closed)return;
    if(sessions.size()>=16)throw new IllegalArgumentException("Repeater 창 16개 제한 · 사용하지 않는 창을 닫으세요.");
    var draft=new JsRequestDraft.Draft(request.url(),request.method(),"","");
    Session session=new Session(api,List.of(draft),List.of("관측한 실제 요청 · "+account));
    byte[] bytes=response==null?new byte[0]:PacketInspector.prefix(response.toByteArray());
    String decoded=response==null?null:PacketFormat.decode(bytes);
    session.entries.clear();
    session.entries.add(new Session.Entry(AuthComparePanel.origin(request),request.toByteArray().getBytes(),bytes,response==null?0:response.toByteArray().length(),decoded,response==null?"전송 전":"원본 응답 · HTTP "+response.statusCode(),false,"관측한 실제 요청 · "+account));
    session.load(0);session.shortcuts.suppressUntilRelease();
    sessions.add(session);Window[] handle={null};
    try {
      handle[0]=opener.open(owner,"Repeater · "+account+" · "+request.method()+" "+URI.create(request.url()).getRawPath(),session,false,()->{session.close();sessions.remove(session);windows.remove(handle[0]);});
      if(handle[0]!=null)windows.add(handle[0]);
    } catch(RuntimeException error){session.close();sessions.remove(session);throw error;}
  }

  static JFrame window(Component owner,String title,Component body,boolean modal,Runnable closed) {
    if(GraphicsEnvironment.isHeadless())return null;
    JFrame frame=new JFrame(title);
    frame.setUndecorated(false);frame.getRootPane().setWindowDecorationStyle(JRootPane.NONE);
    Window parent=owner==null?null:SwingUtilities.getWindowAncestor(owner);
    if(parent!=null)frame.setIconImages(parent.getIconImages());
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);frame.setResizable(true);
    frame.setContentPane((Container)body);frame.setMinimumSize(new Dimension(780,440));
    frame.setSize(1120,720);frame.setLocationRelativeTo(owner);
    frame.addWindowListener(new WindowAdapter() { public void windowClosed(WindowEvent event) { closed.run(); } });
    frame.setVisible(true);return frame;
  }

  @Override public void close() {
    closed=true;
    for(Session session:List.copyOf(sessions))session.close();sessions.clear();
    for(Window window:List.copyOf(windows))window.dispose();windows.clear();
  }

  static final class Session extends JPanel implements AutoCloseable {
    final MontoyaApi api;
    final PacketInspector request=new PacketInspector("wb.js.repeater.request",true);
    final PacketInspector response=new PacketInspector("wb.js.repeater.response");
    final JTextField target=new JTextField(24);
    final JButton send=new JButton("Send"),previous=new JButton("←"),next=new JButton("→");
    final JComboBox<String> method=new JComboBox<>(new String[]{"선택 필요","GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS"});
    final JButton applyMethod=new JButton("메서드 적용");
    final JLabel methodHint=new JLabel(),result=new JLabel("전송 전");
    final JTextArea note=new JTextArea("요청은 Raw에서 편집 · 메서드·값과 인증을 확인하세요.",2,0);
    final JsRepeaterShortcuts shortcuts=new JsRepeaterShortcuts(this,send);
    final List<Entry> entries=new ArrayList<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"Workbench-JS-Repeater");t.setDaemon(true);return t;});
    volatile Future<?> pending;
    volatile boolean closed;
    boolean busy,loading;
    int index;

    record Entry(String target,byte[] request,byte[] response,int responseLength,String decoded,String status,boolean sent,String methodEvidence) {
      Entry { request=request.clone();response=response.clone(); }
    }

    Session(MontoyaApi api,List<JsRequestDraft.Draft> drafts) {
      this(api,drafts,drafts.stream().map(draft->"분석·확인된 초안").toList());
    }

    Session(MontoyaApi api,List<JsRequestDraft.Draft> drafts,List<String> reasons) {
      super(new BorderLayout(6,6));this.api=api;setName("wb.js.repeater.window");
      setBorder(BorderFactory.createEmptyBorder(10,10,8,10));
      send.setName("wb.js.repeater.send");target.setName("wb.js.repeater.target");
      previous.setName("wb.js.repeater.previous");next.setName("wb.js.repeater.next");
      method.setName("wb.js.repeater.method");applyMethod.setName("wb.js.repeater.method.apply");
      method.setEditable(true);method.setToolTipText("JS 분석 → 단일 관찰 메서드 → 기본 GET 순으로 선택 · 직접 변경 가능 · 선택만으로 전송하지 않습니다.");
      previous.setToolTipText("이전 요청·응답");next.setToolTipText("다음 요청·응답");
      target.setToolTipText("전송 대상 Origin · 프로토콜, 호스트, 포트");
      JPanel action=new JPanel(new BorderLayout(8,0));
      action.add(WorkbenchUi.row(send,previous,next),BorderLayout.WEST);
      action.add(target);action.add(result,BorderLayout.EAST);
      JPanel header=new JPanel(new BorderLayout(0,6));header.add(action,BorderLayout.NORTH);
      header.add(WorkbenchUi.row(new JLabel("메서드"),method,applyMethod,methodHint));add(header,BorderLayout.NORTH);
      JSplitPane messages=WorkbenchUi.split(JSplitPane.HORIZONTAL_SPLIT,
          WorkbenchUi.card("요청 REQUEST",request),WorkbenchUi.card("응답 RESPONSE",response),.5);
      messages.setName("wb.js.repeater.messages");add(messages);
      note.putClientProperty("wb.font.size",12);note.putClientProperty("wb.prose",true);
      note.setEditable(false);note.setLineWrap(true);note.setWrapStyleWord(true);note.setBorder(null);
      note.setName("wb.js.repeater.note");add(note,BorderLayout.SOUTH);
      methodHint.putClientProperty("wb.font.size",12);methodHint.setName("wb.js.repeater.method.hint");
      result.putClientProperty("wb.font.size",12);result.setName("wb.js.repeater.result");
      WorkbenchUi.style(this);WorkbenchUi.primary(send);
      for(int row=0;row<drafts.size();row++) {var draft=drafts.get(row);
        URI uri=URI.create(draft.url());
        entries.add(new Entry(uri.getScheme()+"://"+uri.getRawAuthority(),draft.wire().getBytes(StandardCharsets.UTF_8),new byte[0],0,null,"전송 전",false,reasons.get(row)));
      }
      request.setNativeAction(this::exportNative);
      request.more.setToolTipText("편집한 요청을 Burp Repeater에 복사 · 전송 없음");
      send.addActionListener(event->send());previous.addActionListener(event->navigate(-1));next.addActionListener(event->navigate(1));
      applyMethod.addActionListener(event->applyMethod());
      KeyStroke shortcut=KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,InputEvent.CTRL_DOWN_MASK);
      Action submit=new AbstractAction(){public void actionPerformed(ActionEvent event){send();}};
      getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcut,"wb.js.repeater.send");
      getInputMap(WHEN_IN_FOCUSED_WINDOW).put(shortcut,"wb.js.repeater.send");
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
      loading=false;updateMethod();updateButtons();
    }

    private void draftChanged(){
      if(closed||loading||busy)return;
      response.setPacket(new byte[0],null);result.setText("편집됨 · 미전송");
      note.setText("요청이 변경되었습니다. Send 또는 Ctrl+Space로 응답을 확인하세요.");
      updateMethod();
    }

    private void updateMethod(){
      String first=request.text.getText().split("\\s+",2)[0];
      // Pretty/Hex are views; method controls always read the authoring bytes.
      if(request.mode()!=PacketInspector.Mode.RAW)try{first=new String(request.editedBytes(),StandardCharsets.UTF_8).split("\\s+",2)[0];}catch(IllegalStateException ignored){}
      boolean known=JsMethodSelection.known(first);
      method.setSelectedItem(known?first:"선택 필요");
      String reason=methodEvidence(first);
      methodHint.setText(known?reason:"전송 전 메서드를 선택하고 적용하세요.");
      methodHint.setToolTipText(reason+" · 메서드가 선택되어도 경로·파라미터·인증 확인 필요");
      if(!known)note.setText("전송하지 않음: HTTP 메서드가 미확정입니다. 위에서 실제 메서드를 선택하고 ‘메서드 적용’을 누르세요.");
    }

    private String methodEvidence(String method){
      Entry entry=entries.get(index);String old=methodOf(entry.request());
      return method.equals(old)&&entry.target().equals(target.getText().strip())?entry.methodEvidence():"직접 편집·선택한 메서드";
    }

    private static String methodOf(byte[] wire){
      int length=0;while(length<wire.length&&length<33&&wire[length]!=' '&&wire[length]!='\r'&&wire[length]!='\n')length++;
      return new String(wire,0,length,StandardCharsets.US_ASCII);
    }

    private void applyMethod(){
      if(closed||busy)return;
      String selected=String.valueOf(method.getEditor().getItem()).strip().toUpperCase(java.util.Locale.ROOT);
      if(!selected.matches("[A-Z][A-Z0-9_-]{0,31}")||selected.startsWith("REPLACE_")){note.setText("원본 요청을 확인한 뒤 GET·POST 등 실제 메서드를 선택하세요.");return;}
      try{
        String raw=new String(request.editedBytes(),StandardCharsets.UTF_8);
        int space=raw.indexOf(' '),line=raw.indexOf('\n');
        if(space<=0||line>=0&&space>line)throw new IllegalArgumentException("Raw 첫 줄을 ‘메서드 /경로 HTTP/1.1’ 형식으로 확인하세요.");
        request.setPacket((selected+raw.substring(space)).getBytes(StandardCharsets.UTF_8),null);
        request.tabs.get(1).doClick(0);
        draftChanged();note.setText("메서드 "+selected+" 적용 · 경로·파라미터를 확인한 뒤 Send 또는 Ctrl+Space로 전송하세요.");
      }catch(Exception error){showError(error);}
    }

    @Override public void addNotify(){super.addNotify();if(!closed)shortcuts.install();}
    @Override public void removeNotify(){shortcuts.close();super.removeNotify();}

    private void updateButtons() {
      send.setEnabled(!closed&&!busy&&api!=null);
      previous.setEnabled(!busy&&index>0);next.setEnabled(!busy&&index+1<entries.size());
      target.setEnabled(!busy);request.text.setEnabled(!busy);
      request.more.setEnabled(!busy&&api!=null);
      method.setEnabled(!closed&&!busy);applyMethod.setEnabled(!closed&&!busy);
    }

    private void saveEdits(boolean sending) {
      Entry old=entries.get(index);byte[] wire=request.editedBytes();String origin=target.getText().strip();
      boolean changed=!origin.equals(old.target())||!Arrays.equals(wire,old.request());
      if(old.sent()&&(changed||sending)) {
        entries.add(new Entry(origin,wire,new byte[0],0,null,"전송 전",false,methodEvidence(methodOf(wire))));index=entries.size()-1;
      } else if(changed)entries.set(index,new Entry(origin,wire,new byte[0],0,null,"전송 전",false,methodEvidence(methodOf(wire))));
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
      if(wire.startsWith("REPLACE_METHOD "))throw new IllegalArgumentException("HTTP 메서드가 미확정입니다. 위에서 메서드를 선택하고 ‘메서드 적용’을 누르세요.");
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
            Entry complete=new Entry(original.target(),sentRequest,preview,length,decoded,display,true,original.methodEvidence());
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
      shortcuts.close();
      if(pending!=null)pending.cancel(true);worker.shutdownNow();entries.clear();
      request.close();response.close();target.setText("");updateButtons();
    }
  }
}

