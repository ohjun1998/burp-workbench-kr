package kr.workbench;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Read-only packet views. Formatting and search never modify retained HTTP bytes. */
final class PacketInspector extends JPanel implements AutoCloseable {
  enum Mode { PRETTY, RAW, HEX }
  static final int PREVIEW_BYTES = 65536, MAX_MATCHES = 2000;
  final JTextPane text;
  final JTextField search = new JTextField();
  final JToggleButton caseSensitive = new JToggleButton("Aa"), regex = new JToggleButton(".*"), wrap = new ToolToggle("↵", "wrap");
  final JButton previous = new ToolButton("↑", "up"), next = new ToolButton("↓", "down"), clear = new JButton("×"), more = new ToolButton("⋯", "more");
  final JLabel count = new JLabel("0 / 0", SwingConstants.CENTER) {
    @Override public Dimension getPreferredSize(){return new Dimension(Math.max(45,super.getPreferredSize().width),28);}
  };
  final List<JToggleButton> tabs = new ArrayList<>();
  final javax.swing.Timer debounce;
  final javax.swing.Timer syntaxDebounce;
  private final JPanel toolbar = new JPanel(new BorderLayout()), searchBar = new JPanel(new BorderLayout(4, 0));
  private final JScrollPane scroll;
  private final Lines lines;
  private final AtomicLong generation = new AtomicLong();
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(), task -> { Thread t = new Thread(task, "workbench-packet-search"); t.setDaemon(true); return t; });
  volatile Future<?> pending;
  private volatile boolean closed;
  private byte[] rawBytes = new byte[0];
  private String raw = "", pretty = "";
  private Mode mode = Mode.PRETTY;
  private List<Span> matches = List.of();
  private int current = -1;
  private Object currentHighlight;
  private Runnable nativeAction;
  Runnable editedAction;
  private boolean truncated;
  private final boolean editable;
  private boolean rendering;
  record Span(int start, int end) {}
  record SearchResult(List<Span> spans, boolean capped, String error) {}

  PacketInspector(String name) {
    this(name, false);
  }

  PacketInspector(String name, boolean editable) {
    super(new BorderLayout());
    this.editable=editable;
    if(editable)mode=Mode.RAW;
    setName(name);
    text = new JTextPane() {
      @Override public void setText(String value) {
        Document document=getDocument();
        if(document==null){super.setText(value);return;}
        try{document.remove(0,document.getLength());document.insertString(0,value==null?"":value,null);}
        catch(BadLocationException ex){throw new IllegalStateException(ex);}
      }
      @Override public String getText() {
        Document document=getDocument();
        if(document==null)return "";
        try{return document.getText(0,document.getLength());}
        catch(BadLocationException ex){throw new IllegalStateException(ex);}
      }
      @Override public boolean getScrollableTracksViewportWidth() {
        return mode != Mode.HEX && wrap.isSelected() || getParent() != null && getUI().getPreferredSize(this).width <= getParent().getWidth();
      }
    };
    text.setName(name + ".text");text.setEditable(false);
    text.getDocument().putProperty(DefaultEditorKit.EndOfLineStringProperty,"\n");
    text.getAccessibleContext().setAccessibleName("읽기 전용 요청·응답 패킷");
    JPanel choices = new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT, 0, 0));
    ButtonGroup group = new ButtonGroup();
    for (Mode value : Mode.values()) {
      String title = switch (value) { case PRETTY -> "Pretty"; case RAW -> "Raw"; case HEX -> "Hex"; };
      JToggleButton tab = new Tab(title);tab.setName(name + "." + title.toLowerCase());
      group.add(tab);choices.add(tab);tabs.add(tab);tab.setSelected(value == mode);
      tab.addActionListener(e -> { commitEdits();mode = value; render(); });
    }
    toolbar.add(choices, BorderLayout.WEST);
    JPanel actions = new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.RIGHT, 4, 2));actions.add(wrap);actions.add(more);
    toolbar.add(actions, BorderLayout.EAST);add(toolbar, BorderLayout.NORTH);
    scroll = new JScrollPane(text);lines = new Lines();scroll.setRowHeaderView(lines);add(scroll);
    wrap.setToolTipText("긴 줄 자동 줄바꿈");wrap.addActionListener(e -> { text.revalidate(); lines.revalidate(); text.repaint(); });
    more.setToolTipText("Burp 기본 편집기 열기");more.setEnabled(false);
    more.addActionListener(e -> { if(nativeAction != null) nativeAction.run(); });
    search.setName(name + ".search");search.setColumns(6);search.setMinimumSize(new Dimension(35, 28));
    search.setToolTipText("Search · Enter/F3 다음 · Shift+Enter/Shift+F3 이전 · Esc 비우기");
    JPanel input = new JPanel(new BorderLayout(2, 0));input.add(search);input.add(clear, BorderLayout.EAST);
    JLabel label = new JLabel("Search");searchBar.add(label, BorderLayout.WEST);searchBar.add(input);
    JPanel controls = new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.RIGHT, 3, 0));
    for(Component c : new Component[]{previous, next, caseSensitive, regex, count})controls.add(c);
    searchBar.add(controls, BorderLayout.EAST);add(searchBar, BorderLayout.SOUTH);
    count.setName(name + ".matches");
    previous.setName(name + ".previous");next.setName(name + ".next");clear.setName(name + ".clear");
    caseSensitive.setName(name + ".case");regex.setName(name + ".regex");
    previous.setToolTipText("이전 검색 결과 · 처음에서 마지막으로 이동");next.setToolTipText("다음 검색 결과 · 마지막에서 처음으로 이동");
    clear.setToolTipText("검색 비우기");caseSensitive.setToolTipText("대소문자 구분");regex.setToolTipText("정규식 검색");
    debounce = new javax.swing.Timer(120, e -> beginSearch());debounce.setRepeats(false);
    syntaxDebounce=new javax.swing.Timer(100,e->{if(!closed)colorize();});syntaxDebounce.setRepeats(false);
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { scheduleSearch(); }
      public void removeUpdate(DocumentEvent e) { scheduleSearch(); }
      public void changedUpdate(DocumentEvent e) { scheduleSearch(); }
    });
    caseSensitive.addActionListener(e -> scheduleSearch());regex.addActionListener(e -> scheduleSearch());
    previous.addActionListener(e -> navigate(-1));next.addActionListener(e -> navigate(1));clear.addActionListener(e -> search.setText(""));
    bind(search, "ENTER", "next", () -> navigate(1));bind(search, "shift ENTER", "previous", () -> navigate(-1));
    bind(search, "ESCAPE", "clear", () -> search.setText(""));
    bind(this, "ctrl F", "find", () -> { search.requestFocusInWindow();search.selectAll(); });
    bind(this, "F3", "next", () -> navigate(1));bind(this, "shift F3", "previous", () -> navigate(-1));
    text.addComponentListener(new ComponentAdapter() { public void componentResized(ComponentEvent e) { lines.revalidate();lines.repaint(); } });
    if(editable)text.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { changed(); }
      public void removeUpdate(DocumentEvent e) { changed(); }
      public void changedUpdate(DocumentEvent e) {}
      private void changed() { if(!rendering){scheduleSearch();syntaxDebounce.restart();if(editedAction!=null)editedAction.run();} }
    });
    applyTheme();
  }

  private static void bind(JComponent owner, String key, String name, Runnable action) {
    owner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
    owner.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
    owner.getActionMap().put(name, new AbstractAction() { public void actionPerformed(ActionEvent e) { action.run(); } });
  }

  void setNativeAction(Runnable action) { nativeAction = action;more.setEnabled(action != null); }
  Mode mode() { return mode; }
  List<Span> matches() { return matches; }
  int currentMatch() { return current; }
  boolean closed() { return closed; }
  byte[] previewBytes() { return rawBytes.clone(); }
  /** Authoring uses Raw bytes; Pretty and Hex remain display-only and cannot overwrite edits. */
  byte[] editedBytes() {
    if(!editable)throw new IllegalStateException("읽기 전용 패킷입니다.");
    if(truncated)throw new IllegalStateException("잘린 미리보기는 전송할 수 없습니다. Burp Repeater에서 전체 요청을 여세요.");
    commitEdits();return rawBytes.clone();
  }

  private void commitEdits() {
    if(!editable || mode!=Mode.RAW || truncated)return;
    raw=text.getText();rawBytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // Raw is authoritative. Formatting is a view and is never a transmission source.
    pretty=raw;
    if(raw.length()<=PREVIEW_BYTES)try { pretty=PacketFormat.pretty(raw); } catch(StackOverflowError ignored) {}
  }

  void setPacket(byte[] original, String decoded) {
    setPacket(original, decoded, original == null ? 0 : original.length);
  }

  static byte[] prefix(burp.api.montoya.core.ByteArray wire) {
    if(wire==null)return new byte[0];
    return wire.length()<=PREVIEW_BYTES?wire.getBytes():wire.subArray(0,PREVIEW_BYTES).getBytes();
  }

  void setPacket(byte[] original, String decoded, int originalLength) {
    if(closed)return;
    rawBytes = original == null ? new byte[0] : Arrays.copyOf(original, Math.min(original.length, PREVIEW_BYTES));
    truncated = originalLength > PREVIEW_BYTES;
    raw = PacketFormat.decode(rawBytes);
    String display = decoded == null ? raw : decoded.substring(0, Math.min(decoded.length(), PREVIEW_BYTES));
    if(decoded != null && decoded.length() > PREVIEW_BYTES)truncated = true;
    // Bound nesting before invoking the existing display-only JSON/XML formatter.
    pretty = display;
    int depth = 0;boolean quoted = false, escaped = false, safe = true;
    for(int i = 0; i < display.length(); i++) {
      char c = display.charAt(i);
      if(quoted) { if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false; }
      else if(c=='"')quoted=true;else if(c=='{' || c=='[' || c=='<') { if(++depth>128){safe=false;break;} }
      else if(c=='}' || c==']')depth=Math.max(0,depth-1);
    }
    if(safe)try { pretty=PacketFormat.pretty(display); }catch(StackOverflowError ignored) { pretty=display; }
    if(pretty.length()>PREVIEW_BYTES*2)pretty=pretty.substring(0,PREVIEW_BYTES*2);
    setToolTipText(truncated ? "미리보기는 앞 64 KiB 기준 · 원본 패킷은 전체 보관 · 전체 보기는 ⋯ → Burp 기본 편집기" : null);
    render();
  }

  static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 5);char[] digits = "0123456789ABCDEF".toCharArray();
    for(int offset=0; offset<bytes.length; offset+=16) {
      if(offset>0)out.append('\n');out.append(String.format("%08X  ", offset));
      for(int j=0;j<16;j++) {
        if(offset+j<bytes.length) { int value=bytes[offset+j]&255;out.append(digits[value>>>4]).append(digits[value&15]).append(' '); }
        else out.append("   ");
        if(j==7)out.append(' ');
      }
      out.append(" | ");
      for(int j=0;j<16&&offset+j<bytes.length;j++) { int value=bytes[offset+j]&255;out.append(value>=32&&value<=126?(char)value:'.'); }
    }
    return out.toString();
  }

  private void render() {
    wrap.setEnabled(mode != Mode.HEX);
    wrap.setToolTipText(mode == Mode.HEX ? "Hex는 바이트 열을 고정하고 가로 스크롤로 표시합니다." : "긴 줄 자동 줄바꿈");
    String value = switch(mode) { case PRETTY -> pretty;case RAW -> raw;case HEX -> hex(rawBytes); };
    rendering=true;
    try { text.getDocument().remove(0, text.getDocument().getLength());text.getDocument().insertString(0, value, null); }
    catch(BadLocationException ex) { throw new IllegalStateException(ex); }
    finally { rendering=false; }
    text.setEditable(editable && mode==Mode.RAW && !truncated);
    if(editable)text.getAccessibleContext().setAccessibleName(mode==Mode.RAW?"요청 Raw 편집":"요청 표시 전용 · Raw에서 편집");
    if(editable)text.setToolTipText("요청은 Raw에서 편집 · Pretty와 Hex는 표시 전용");
    text.setCaretPosition(0);colorize();lines.revalidate();lines.repaint();scheduleSearch();
  }

  void applyTheme() {
    setBackground(WorkbenchUi.surface());
    WorkbenchUi.style(toolbar);WorkbenchUi.style(searchBar);WorkbenchUi.style(scroll);
    for(AbstractButton b : new AbstractButton[]{previous,next,clear,caseSensitive,regex,wrap,more}) {
      b.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));b.setMargin(new Insets(0,0,0,0));
      b.setPreferredSize(new Dimension(b==caseSensitive?32:28,28));
    }
    searchBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(5,6,5,6)));
    search.setFont(WorkbenchUi.font().deriveFont(12f));search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkbenchUi.line()),BorderFactory.createEmptyBorder(2,5,2,5)));
    count.setFont(WorkbenchUi.font().deriveFont(11f));scroll.setBorder(null);
    text.setFont(WorkbenchUi.textFont(text.getText()));text.setMargin(new Insets(8,8,8,8));text.setBorder(null);
    text.setBackground(WorkbenchUi.surface());text.setForeground(WorkbenchUi.ink());text.setCaretColor(WorkbenchUi.ink());
    text.setSelectionColor(WorkbenchUi.selected());text.setSelectedTextColor(WorkbenchUi.ink());
    colorize();lines.repaint();toolbar.repaint();
  }

  private void colorize() {
    StyledDocument doc=text.getStyledDocument();String value=text.getText();
    SimpleAttributeSet normal=new SimpleAttributeSet();StyleConstants.setForeground(normal,WorkbenchUi.ink());
    StyleConstants.setFontFamily(normal,WorkbenchUi.textFont(value).getFamily());StyleConstants.setFontSize(normal,14);
    doc.setCharacterAttributes(0,doc.getLength(),normal,true);
    if(mode==Mode.HEX)return;
    Color blue=WorkbenchUi.dark()?new Color(161,188,239):new Color(47,80,149);
    Color green=WorkbenchUi.dark()?new Color(155,209,113):new Color(36,119,47);
    Color orange=WorkbenchUi.dark()?new Color(245,159,79):new Color(164,80,16);
    int head=value.indexOf("\n\n");if(head<0)head=value.indexOf("\r\n\r\n");if(head<0)head=value.length();
    int line=0;
    while(line<head) { int end=value.indexOf('\n',line);if(end<0||end>head)end=head;int colon=value.indexOf(':',line);paint(line,line==0?end:colon>=line&&colon<end?colon+1:line,blue);line=end+1; }
    int tokens=0;
    for(int i=head;i<value.length()&&tokens<4000;) {
      char c=value.charAt(i);int start=i;
      if(c=='"'||c=='\'') { char quote=c;boolean escape=false;i++;while(i<value.length()){char next=value.charAt(i++);if(escape)escape=false;else if(next=='\\')escape=true;else if(next==quote)break;}int after=i;while(after<value.length()&&Character.isWhitespace(value.charAt(after)))after++;paint(start,i,after<value.length()&&value.charAt(after)==':'?blue:green);tokens++; }
      else if(Character.isDigit(c)||c=='-'&&i+1<value.length()&&Character.isDigit(value.charAt(i+1))) { i++;while(i<value.length()&&"0123456789.eE+-".indexOf(value.charAt(i))>=0)i++;paint(start,i,orange);tokens++; }
      else if(Character.isJavaIdentifierStart(c)) { i++;while(i<value.length()&&Character.isJavaIdentifierPart(value.charAt(i)))i++;if(List.of("true","false","null","var","let","const","function","return").contains(value.substring(start,i)))paint(start,i,orange);tokens++; }
      else i++;
    }
  }

  private void paint(int start,int end,Color color) {
    if(end<=start)return;SimpleAttributeSet attr=new SimpleAttributeSet();StyleConstants.setForeground(attr,color);text.getStyledDocument().setCharacterAttributes(start,end-start,attr,false);
  }

  private void scheduleSearch() {
    if(closed)return;
    generation.incrementAndGet();debounce.stop();if(pending!=null)pending.cancel(true);worker.getQueue().clear();
    matches=List.of();current=-1;currentHighlight=null;text.getHighlighter().removeAllHighlights();
    count.setText("0 / 0");count.setForeground(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));count.setToolTipText(null);previous.setEnabled(false);next.setEnabled(false);
    if(!search.getText().isEmpty()){count.setText("…");debounce.restart();}
  }

  private void beginSearch() {
    if(closed)return;
    long ticket=generation.get();String value=text.getText(),query=search.getText();boolean expression=regex.isSelected(),sensitive=caseSensitive.isSelected();
    pending=worker.submit(()->{
      SearchResult result=find(value,query,expression,sensitive,()->closed||generation.get()!=ticket||Thread.currentThread().isInterrupted());
      SwingUtilities.invokeLater(()->{if(!closed&&generation.get()==ticket)showMatches(result);});
    });
  }

  static SearchResult find(String value,String query,boolean expression,boolean sensitive,java.util.function.BooleanSupplier cancelled) {
    if(query.isEmpty())return new SearchResult(List.of(),false,null);
    if(query.length()>512)return new SearchResult(List.of(),false,"검색어 512자 제한");
    try {
      Pattern pattern=Pattern.compile(expression?query:Pattern.quote(query),Pattern.MULTILINE|(sensitive?0:Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE));
      long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200);
      Matcher matcher=pattern.matcher(new GuardedText(value,deadline,cancelled));List<Span> found=new ArrayList<>();
      while(matcher.find()) {
        if(cancelled.getAsBoolean()||System.nanoTime()>deadline)throw new SearchBudget();
        if(matcher.start()==matcher.end())continue;
        if(found.size()==MAX_MATCHES)return new SearchResult(List.copyOf(found),true,null);
        found.add(new Span(matcher.start(),matcher.end()));
      }
      return new SearchResult(List.copyOf(found),false,null);
    }catch(PatternSyntaxException ex){return new SearchResult(List.of(),false,"정규식 오류");}
    catch(SearchBudget|StackOverflowError ex){return new SearchResult(List.of(),false,"검색 제한");}
  }

  private static final class SearchBudget extends RuntimeException {}
  private static final class GuardedText implements CharSequence {
    private final String value;private final long deadline;private final java.util.function.BooleanSupplier cancelled;private int reads;
    GuardedText(String value,long deadline,java.util.function.BooleanSupplier cancelled){this.value=value;this.deadline=deadline;this.cancelled=cancelled;}
    private void guard(){if((++reads&255)==0&&(cancelled.getAsBoolean()||System.nanoTime()>deadline))throw new SearchBudget();}
    public int length(){guard();return value.length();}
    public char charAt(int index){guard();return value.charAt(index);}
    public CharSequence subSequence(int start,int end){guard();return new GuardedText(value.substring(start,end),deadline,cancelled);}
    public String toString(){guard();return value;}
  }

  private void showMatches(SearchResult result) {
    matches=result.spans();current=-1;
    if(result.error()!=null){count.setText(result.error().contains("오류")?"오류":"제한");count.setToolTipText(result.error()+" · 검색식 또는 검색어를 줄여 주세요.");count.setForeground(WorkbenchUi.toneInk(WorkbenchUi.Tone.ERROR));return;}
    Highlighter.HighlightPainter painter=new DefaultHighlighter.DefaultHighlightPainter(WorkbenchUi.dark()?new Color(112,94,30):new Color(255,233,144));
    try{for(Span span:matches)text.getHighlighter().addHighlight(span.start(),span.end(),painter);}catch(BadLocationException ex){throw new IllegalStateException(ex);}
    count.setToolTipText(result.capped()?"앞 "+MAX_MATCHES+"개 일치 결과만 강조·이동합니다.":null);
    previous.setEnabled(!matches.isEmpty());next.setEnabled(!matches.isEmpty());
    if(matches.isEmpty())count.setText("0 / 0");else {navigate(1);if(result.capped())count.setText("1 / "+MAX_MATCHES+"+");}
  }

  private void navigate(int delta) {
    if(matches.isEmpty())return;
    current=current<0?(delta>0?0:matches.size()-1):Math.floorMod(current+delta,matches.size());
    Span span=matches.get(current);Highlighter h=text.getHighlighter();if(currentHighlight!=null)h.removeHighlight(currentHighlight);
    try{currentHighlight=h.addHighlight(span.start(),span.end(),new DefaultHighlighter.DefaultHighlightPainter(WorkbenchUi.dark()?new Color(154,100,34):new Color(255,190,92)));text.setCaretPosition(span.start());Rectangle box=text.modelToView2D(span.start())==null?null:text.modelToView2D(span.start()).getBounds();if(box!=null)text.scrollRectToVisible(box);}catch(BadLocationException ex){throw new IllegalStateException(ex);}
    count.setText((current+1)+" / "+matches.size()+(count.getToolTipText()!=null?"+":""));
  }

  @Override public void close() {
    if(closed)return;closed=true;generation.incrementAndGet();debounce.stop();syntaxDebounce.stop();editedAction=null;if(pending!=null)pending.cancel(true);worker.shutdownNow();
    rawBytes=new byte[0];raw="";pretty="";matches=List.of();current=-1;nativeAction=null;
    text.setText("");search.setText("");text.getHighlighter().removeAllHighlights();count.setText("0 / 0");
  }

  private final class Lines extends JComponent {
    Lines(){setPreferredSize(new Dimension(42,1));}
    @Override public Dimension getPreferredSize(){return new Dimension(42,Math.max(1,text.getPreferredSize().height));}
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.surface());g.fillRect(0,0,getWidth(),getHeight());g.setFont(text.getFont());g.setColor(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));
      Rectangle clip=g.getClipBounds();Element root=text.getDocument().getDefaultRootElement();int start=root.getElementIndex(text.viewToModel2D(new Point(0,clip.y)));
      try { for(int i=start;i<root.getElementCount();i++){var box=text.modelToView2D(root.getElement(i).getStartOffset());if(box==null)continue;if(box.getY()>clip.getMaxY())break;String number=Integer.toString(i+1);g.drawString(number,getWidth()-8-g.getFontMetrics().stringWidth(number),(int)box.getY()+g.getFontMetrics().getAscent());} }catch(BadLocationException ignored){}g.dispose();
    }
  }

  private static final class Tab extends JToggleButton {
    Tab(String title){super(title);}
    @Override public Dimension getPreferredSize(){return new Dimension(76,34);}
    @Override protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);g.setColor(WorkbenchUi.surface());g.fillRect(0,0,getWidth(),getHeight());g.setFont(WorkbenchUi.font().deriveFont(isSelected()?Font.BOLD:Font.PLAIN));g.setColor(isSelected()?WorkbenchUi.ink():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));g.drawString(getText(),(getWidth()-g.getFontMetrics().stringWidth(getText()))/2,(getHeight()-g.getFontMetrics().getHeight())/2+g.getFontMetrics().getAscent());g.setColor(isSelected()?WorkbenchUi.accent():WorkbenchUi.line());g.fillRect(0,getHeight()-(isSelected()?3:1),getWidth(),isSelected()?3:1);if(hasFocus()){g.setColor(WorkbenchUi.accent());g.drawRect(2,2,getWidth()-5,getHeight()-5);}g.dispose();}
  }

  private static void paintTool(AbstractButton button,Graphics graphics,String kind) {
    Graphics2D g=(Graphics2D)graphics.create();WorkbenchSkin.antialias(g);
    g.setColor(button.isSelected()&&button.isEnabled()?WorkbenchUi.selected():WorkbenchUi.surface());g.fillRoundRect(0,0,button.getWidth()-1,button.getHeight()-1,6,6);
    g.setColor(button.hasFocus()?WorkbenchUi.accent():WorkbenchUi.line());g.drawRoundRect(0,0,button.getWidth()-1,button.getHeight()-1,6,6);
    g.setColor(button.isEnabled()?WorkbenchUi.ink():WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));g.setStroke(new BasicStroke(1.5f));int x=button.getWidth()/2,y=button.getHeight()/2;
    if(kind.equals("more")){for(int i=-1;i<=1;i++)g.fillOval(x+i*5-1,y-1,3,3);}
    else if(kind.equals("wrap")){g.drawLine(x-6,y-4,x+4,y-4);g.drawLine(x+4,y-4,x+4,y+2);g.drawLine(x+4,y+2,x-4,y+2);g.drawLine(x-4,y+2,x-1,y-1);g.drawLine(x-4,y+2,x-1,y+5);}
    else {int d=kind.equals("up")?-1:1;g.drawLine(x,y-5*d,x,y+5*d);g.drawLine(x,y+5*d,x-4,y+d);g.drawLine(x,y+5*d,x+4,y+d);}
    g.dispose();
  }
  private static final class ToolButton extends JButton {
    private final String kind;ToolButton(String title,String kind){super(title);this.kind=kind;}
    @Override protected void paintComponent(Graphics g){paintTool(this,g,kind);}
  }
  private static final class ToolToggle extends JToggleButton {
    private final String kind;ToolToggle(String title,String kind){super(title,true);this.kind=kind;}
    @Override protected void paintComponent(Graphics g){paintTool(this,g,kind);}
  }
}
