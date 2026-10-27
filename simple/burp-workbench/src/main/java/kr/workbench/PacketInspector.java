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
  enum Mode { PRETTY, RAW, HEX, RENDER }
  static final int PREVIEW_BYTES = 65536, MAX_MATCHES = 2000;
  final JTextPane text;
  final JEditorPane rendered = PacketRender.pane();
  final JLabel renderNotice = new JLabel("정적 HTML · 외부 요청 없음");
  final JPanel renderPanel = new JPanel(new BorderLayout());
  final JTextField search = new JTextField();
  final JToggleButton caseSensitive = new JToggleButton("Aa"), regex = new JToggleButton(".*"), wrap = new ToolToggle("↵", "wrap");
  final JButton previous = new ToolButton("↑", "up"), next = new ToolButton("↓", "down"), clear = new JButton("×"), more = new ToolButton("⋯", "more");
  final JLabel count = new JLabel("0 / 0", SwingConstants.CENTER) {
    @Override public Dimension getPreferredSize(){return new Dimension(Math.max(45,super.getPreferredSize().width),28);}
  };
  final List<JToggleButton> tabs = new ArrayList<>();
  final javax.swing.Timer debounce;
  final javax.swing.Timer syntaxDebounce;
  private final JPanel toolbar;
  private final JPanel searchBar = new ResponsiveSearchBar();
  private final JPanel searchControls = new JPanel();
  final JButton searchOptions = new ToolButton("검색 옵션", "more");
  final JComboBox<String> modePicker = new JComboBox<>(new String[]{"Pretty", "Raw", "Hex", "Render"});
  private boolean compactSearch;
  final JPanel trafficContextBar = new JPanel(new BorderLayout(4, 2));
  final JLabel trafficContextNotice = new JLabel();
  final JButton trafficPrevious = new JButton("이전 일치"), trafficNext = new JButton("다음 일치"), trafficReturn = new JButton("앞부분 보기");
  private final AtomicLong packetGeneration = new AtomicLong();
  private volatile Future<?> trafficPending;
  private TrafficMatchContext.Result trafficContext;
  private int trafficPage;
  private boolean displayingTrafficContext,trafficSearchActive;
  private final ButtonGroup modeChoices = new ButtonGroup();
  private final JScrollPane scroll;
  private final Lines lines;
  private final AtomicLong generation = new AtomicLong();
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(), task -> { Thread t = new Thread(task, "workbench-packet-search"); t.setDaemon(true); return t; });
  volatile Future<?> pending;
  private volatile boolean closed;
  private byte[] rawBytes = new byte[0];
  private String raw = "", pretty = "", renderSource = "";
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
    rendered.setName(name+".render");rendered.getAccessibleContext().setAccessibleName("받은 HTML 본문 정적 Render");
    text.setName(name + ".text");text.setEditable(false);
    text.getDocument().putProperty(DefaultEditorKit.EndOfLineStringProperty,"\n");
    text.getAccessibleContext().setAccessibleName("읽기 전용 요청·응답 패킷");
    JPanel choices = new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.LEFT, 0, 0));
    for (Mode value : Mode.values()) {
      String title = switch (value) { case PRETTY -> "Pretty"; case RAW -> "Raw"; case HEX -> "Hex"; case RENDER -> "Render"; };
      JToggleButton tab = new Tab(title);tab.setName(name + "." + title.toLowerCase());
      modeChoices.add(tab);choices.add(tab);tabs.add(tab);tab.setSelected(value == mode);
      tab.addActionListener(e -> { clearTrafficContext();commitEdits();mode = value; render(); });
    }
    JPanel actions = new JPanel(new WorkbenchUi.WrapLayout(FlowLayout.RIGHT, 4, 2));actions.add(wrap);actions.add(more);
    modePicker.setName(name+".mode");modePicker.getAccessibleContext().setAccessibleName("패킷 표시 방식");
    modePicker.addActionListener(event->{int index=modePicker.getSelectedIndex();if(index>=0&&Mode.values()[index]!=mode){clearTrafficContext();commitEdits();mode=Mode.values()[index];render();}});
    toolbar=new ResponsivePacketToolbar(choices,modePicker,actions);
    JPanel header = new JPanel(new BorderLayout(0, 2));header.add(toolbar, BorderLayout.NORTH);
    trafficContextBar.setName(name + ".traffic.context");trafficContextNotice.setName(name + ".traffic.context.notice");
    trafficContextNotice.setMinimumSize(new Dimension(20, 22));
    trafficContextBar.add(trafficContextNotice);
    trafficContextBar.add(WorkbenchUi.row(trafficPrevious, trafficNext, trafficReturn), BorderLayout.SOUTH);
    trafficPrevious.setName(name + ".traffic.previous");trafficNext.setName(name + ".traffic.next");trafficReturn.setName(name + ".traffic.return");
    trafficPrevious.addActionListener(e -> moveTrafficContext(-1));trafficNext.addActionListener(e -> moveTrafficContext(1));
    trafficReturn.addActionListener(e -> { clearTrafficContext();render(); });
    trafficContextBar.setVisible(false);header.add(trafficContextBar, BorderLayout.SOUTH);add(header, BorderLayout.NORTH);
    scroll = new JScrollPane(text);lines = new Lines();scroll.setRowHeaderView(lines);add(scroll);
    renderNotice.setToolTipText("스크립트·외부 이미지·CSS·프레임 제외 · 앞 64 KiB 본문 미리보기");
    renderPanel.add(renderNotice,BorderLayout.NORTH);renderPanel.add(new JScrollPane(rendered));
    wrap.setToolTipText("긴 줄 자동 줄바꿈");wrap.addActionListener(e -> { text.revalidate(); lines.revalidate(); text.repaint(); });
    more.setToolTipText("Burp 기본 편집기 열기");more.setEnabled(false);
    more.addActionListener(e -> { if(nativeAction != null) nativeAction.run(); });
    search.setName(name + ".search");search.setColumns(6);search.setMinimumSize(new Dimension(35, 28));
    search.setToolTipText("Search · Enter/F3 다음 · Shift+Enter/Shift+F3 이전 · Esc 비우기");
    JPanel input = new JPanel(new BorderLayout(2, 0));input.add(search);input.add(clear, BorderLayout.EAST);
    JLabel label = new JLabel("Search");searchBar.add(label, BorderLayout.WEST);searchBar.add(input);
    searchControls.setLayout(new BoxLayout(searchControls,BoxLayout.X_AXIS));
    rebuildSearchControls(false);
    searchBar.add(searchControls, BorderLayout.EAST);add(searchBar, BorderLayout.SOUTH);
    JPopupMenu searchMenu=new JPopupMenu();JCheckBoxMenuItem caseChoice=new JCheckBoxMenuItem("대소문자 구분"),regexChoice=new JCheckBoxMenuItem("정규식 검색");
    caseChoice.addActionListener(event->caseSensitive.doClick());regexChoice.addActionListener(event->regex.doClick());
    searchMenu.add(caseChoice);searchMenu.add(regexChoice);searchOptions.setName(name+".search.options");
    searchOptions.getAccessibleContext().setAccessibleName("검색 옵션: 대소문자 구분·정규식");
    searchOptions.setToolTipText("검색 옵션 · 대소문자 구분 / 정규식");
    searchOptions.addActionListener(event->{WorkbenchUi.style(searchMenu);caseChoice.setSelected(caseSensitive.isSelected());regexChoice.setSelected(regex.isSelected());if(searchOptions.isShowing())searchMenu.show(searchOptions,0,searchOptions.getHeight());});
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
    if(!editable || mode!=Mode.RAW || truncated || text.getText().equals(raw))return;
    raw=text.getText();rawBytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    // Raw is authoritative. Formatting is a view and is never a transmission source.
    pretty=raw;renderSource=raw;
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
    packetGeneration.incrementAndGet();clearTrafficContext();
    rawBytes = original == null ? new byte[0] : Arrays.copyOf(original, Math.min(original.length, PREVIEW_BYTES));
    truncated = originalLength > PREVIEW_BYTES;
    raw = PacketFormat.decode(rawBytes);
    String display = decoded == null ? raw : decoded.substring(0, Math.min(decoded.length(), PREVIEW_BYTES));
    if(decoded != null && decoded.length() > PREVIEW_BYTES)truncated = true;
    renderSource=display;
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

  /** Read only bounded, ephemeral excerpts after a list result is selected. */
  void followTrafficSearch(DiskTrafficStore.PacketRef ref, boolean response, TrafficQuery settings, String query) {
    followTrafficSearch(ref,response,settings,query,()->true);
  }

  void followTrafficSearch(DiskTrafficStore.PacketRef ref, boolean response, TrafficQuery settings, String query,
                           java.util.function.BooleanSupplier currentSearch) {
    if (closed || editable || query.isBlank()) return;
    clearTrafficContext();long ticket = packetGeneration.get();
    generation.incrementAndGet();debounce.stop();if(pending!=null)pending.cancel(true);worker.getQueue().clear();
    trafficSearchActive=true;searchBar.setVisible(false);
    trafficContextNotice.setText("목록 검색 문맥 · " + (response ? "응답" : "요청") + " 확인 중 · 원본 변경 없음");
    trafficPrevious.setEnabled(false);trafficNext.setEnabled(false);trafficContextBar.setVisible(true);
    trafficPending = worker.submit(() -> {
      try {
        TrafficMatchContext.Result found = TrafficMatchContext.locate(ref, response, settings, query,
            () -> closed || packetGeneration.get() != ticket || !currentSearch.getAsBoolean() || Thread.currentThread().isInterrupted());
        SwingUtilities.invokeLater(() -> {
          if (closed || packetGeneration.get() != ticket || !currentSearch.getAsBoolean()) return;
          trafficContext = found;trafficPage = 0;
          if (found.pages().isEmpty()) {
            trafficSearchActive=false;render();
            trafficContextNotice.setText("목록 검색 문맥 · " + found.source() + " · " + (found.note().isEmpty() ? "검사 범위에서 일치 없음" : found.note())
                + (found.partial() ? " · 원문 앞 2 MiB 일부 검사" : ""));
            trafficContextNotice.setToolTipText(trafficContextNotice.getText());
          } else showTrafficContext();
        });
      } catch (CancellationException ignored) {
      } catch (RuntimeException error) {
        SwingUtilities.invokeLater(() -> { if (!closed && packetGeneration.get() == ticket && currentSearch.getAsBoolean()) {
          trafficSearchActive=false;render();trafficContextNotice.setText("검색 원본을 읽을 수 없습니다 · 현재 트래픽을 다시 선택하세요.");
        } });
      }
    });
  }

  private void clearTrafficContext() {
    packetGeneration.incrementAndGet();
    if (trafficPending != null) trafficPending.cancel(true);
    trafficPending = null;trafficContext = null;trafficPage = 0;displayingTrafficContext = false;trafficSearchActive=false;
    trafficContextBar.setVisible(false);
  }

  void invalidateTrafficSearch(){
    if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(this::invalidateTrafficSearch);return;}
    boolean restore=displayingTrafficContext||trafficSearchActive||trafficContextBar.isVisible();
    clearTrafficContext();if(restore&&!closed)render();
  }

  private void moveTrafficContext(int delta) {
    if (trafficContext == null || trafficContext.pages().isEmpty()) return;
    trafficPage = Math.floorMod(trafficPage + delta, trafficContext.pages().size());showTrafficContext();
  }

  private void showTrafficContext() {
    if (trafficContext == null || trafficContext.pages().isEmpty()) return;
    displayingTrafficContext = true;
    trafficSearchActive = true;
    generation.incrementAndGet();debounce.stop();if (pending != null) pending.cancel(true);syntaxDebounce.stop();
    remove(renderPanel);add(scroll, BorderLayout.CENTER);searchBar.setVisible(false);
    modeChoices.clearSelection();
    TrafficMatchContext.Page page = trafficContext.pages().get(trafficPage);
    rendering = true;try { text.setText(page.text());text.setEditable(false); } finally { rendering = false; }
    text.getHighlighter().removeAllHighlights();matches = List.of();current = -1;currentHighlight = null;
    SimpleAttributeSet normal = new SimpleAttributeSet();StyleConstants.setForeground(normal, WorkbenchUi.ink());
    StyleConstants.setFontFamily(normal,WorkbenchUi.textFont(text.getText()).getFamily());StyleConstants.setFontSize(normal,WorkbenchUi.displayPixels(14));
    text.getStyledDocument().setCharacterAttributes(0, text.getDocument().getLength(), normal, true);
    try {
      if (page.end() > page.start()) text.getHighlighter().addHighlight(page.start(), page.end(),
          new DefaultHighlighter.DefaultHighlightPainter(WorkbenchUi.dark() ? new Color(154,100,34) : new Color(255,190,92)));
    } catch (BadLocationException error) { throw new IllegalStateException(error); }
    text.setCaretPosition(Math.min(page.start(), text.getDocument().getLength()));
    String label = "목록 검색 문맥 · " + page.location() + " · 일치 " + (trafficPage + 1) + "/" + trafficContext.pages().size()
        + (trafficContext.capped() ? "+" : "") + " · 문자 위치 " + (page.sourceOffset() + page.start() + 1)
        + (trafficContext.partial() ? " · 원문 앞 2 MiB 일부 검사" : "") + " · 문맥 일부 표시";
    trafficContextNotice.setText(label);trafficContextNotice.setToolTipText(label + " · 바이트 위치가 아닙니다. 전체 원본은 ⋯에서 확인하세요.");
    trafficPrevious.setEnabled(trafficContext.pages().size() > 1);trafficNext.setEnabled(trafficContext.pages().size() > 1);
    trafficContextBar.setVisible(true);text.setToolTipText("목록 검색에서 일치한 원문/디코딩 본문의 주변 문맥 · 원본 패킷은 변경하지 않습니다.");
    revalidate();repaint();
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
    displayingTrafficContext = false;
    boolean html=mode==Mode.RENDER;
    remove(html?scroll:renderPanel);add(html?renderPanel:scroll,BorderLayout.CENTER);
    for(JComponent control:new JComponent[]{search,caseSensitive,regex,clear})control.setEnabled(!html);
    searchBar.setVisible(!html);wrap.setEnabled(mode != Mode.HEX && !html);
    if(html){rendered.setText(PacketRender.document(renderSource,truncated));rendered.setCaretPosition(0);}
    revalidate();repaint();
    wrap.setToolTipText(mode == Mode.HEX ? "Hex는 바이트 열을 고정하고 가로 스크롤로 표시합니다." : "긴 줄 자동 줄바꿈");
    String value = switch(mode) { case PRETTY -> pretty;case RAW -> raw;case HEX -> hex(rawBytes);case RENDER -> ""; };
    for (int i = 0; i < tabs.size(); i++) tabs.get(i).setSelected(Mode.values()[i] == mode);
    if(modePicker.getSelectedIndex()!=mode.ordinal())modePicker.setSelectedIndex(mode.ordinal());
    rendering=true;
    try { text.getDocument().remove(0, text.getDocument().getLength());text.getDocument().insertString(0, value, null); }
    catch(BadLocationException ex) { throw new IllegalStateException(ex); }
    finally { rendering=false; }
    text.setEditable(editable && mode==Mode.RAW && !truncated);
    if(editable)text.getAccessibleContext().setAccessibleName(mode==Mode.RAW?"요청 Raw 편집":"요청 표시 전용 · Raw에서 편집");
    if(editable)text.setToolTipText("요청은 Raw에서 편집 · Pretty·Hex·Render는 표시 전용");
    text.setCaretPosition(0);colorize();lines.revalidate();lines.repaint();scheduleSearch();
  }

  void applyTheme() {
    setBackground(WorkbenchUi.surface());
    WorkbenchUi.style(toolbar);WorkbenchUi.style(modePicker);WorkbenchUi.style(searchBar);WorkbenchUi.style(scroll);
    for(AbstractButton b : new AbstractButton[]{previous,next,clear,caseSensitive,regex,wrap,more,searchOptions}) {
      b.setFont(WorkbenchUi.font());b.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));b.setMargin(new Insets(0,0,0,0));
      b.setPreferredSize(new Dimension(b==caseSensitive?Math.max(32,b.getFontMetrics(b.getFont()).stringWidth("Aa")+10):28,b==caseSensitive||b==regex?Math.max(28,b.getFontMetrics(b.getFont()).getHeight()+6):28));b.setMinimumSize(b.getPreferredSize());b.setMaximumSize(b.getPreferredSize());
    }
    searchBar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,0,0,0,WorkbenchUi.line()),BorderFactory.createEmptyBorder(5,6,5,6)));
    search.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(12f)));search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(WorkbenchUi.line()),BorderFactory.createEmptyBorder(2,5,2,5)));
    count.setFont(WorkbenchUi.font().deriveFont(WorkbenchUi.displaySize(11f)));count.setMinimumSize(count.getPreferredSize());count.setMaximumSize(new Dimension(400,28));scroll.setBorder(null);
    text.setFont(WorkbenchUi.textFont(text.getText()));text.setMargin(new Insets(8,8,8,8));text.setBorder(null);
    text.setBackground(WorkbenchUi.surface());text.setForeground(WorkbenchUi.ink());text.setCaretColor(WorkbenchUi.ink());
    text.setSelectionColor(WorkbenchUi.selected());text.setSelectedTextColor(WorkbenchUi.ink());
    WorkbenchUi.style(renderPanel);rendered.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES,true);
    if(mode==Mode.RENDER){rendered.setText(PacketRender.document(renderSource,truncated));rendered.setCaretPosition(0);}
    colorize();lines.repaint();toolbar.revalidate();toolbar.repaint();searchBar.revalidate();
  }

  private void colorize() {
    StyledDocument doc=text.getStyledDocument();String value=text.getText();
    SimpleAttributeSet normal=new SimpleAttributeSet();StyleConstants.setForeground(normal,WorkbenchUi.ink());
    StyleConstants.setFontFamily(normal,WorkbenchUi.textFont(value).getFamily());StyleConstants.setFontSize(normal,WorkbenchUi.displayPixels(14));
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
    if(displayingTrafficContext||trafficSearchActive)return;
    generation.incrementAndGet();debounce.stop();if(pending!=null)pending.cancel(true);worker.getQueue().clear();
    matches=List.of();current=-1;currentHighlight=null;text.getHighlighter().removeAllHighlights();
    count.setText("0 / 0");count.setForeground(WorkbenchUi.toneInk(WorkbenchUi.Tone.NEUTRAL));count.setToolTipText(null);previous.setEnabled(false);next.setEnabled(false);
    if(mode!=Mode.RENDER&&!search.getText().isEmpty()){count.setText("…");debounce.restart();}
  }

  private void beginSearch() {
    if(closed||trafficSearchActive||displayingTrafficContext)return;
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

  /** Retain already observed contexts if the bounded scan cannot enumerate every later match. */
  static SearchResult findContexts(String value, String query, boolean expression, boolean sensitive,
                                   java.util.function.BooleanSupplier cancelled) {
    if (query.isBlank()) return new SearchResult(List.of(), false, null);
    if (query.length() > 512) return new SearchResult(List.of(), false, "검색어 512자 제한");
    List<Span> found = new ArrayList<>();
    try {
      Pattern pattern = Pattern.compile(expression ? query : Pattern.quote(query),
          Pattern.MULTILINE | (sensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
      Matcher matcher = pattern.matcher(new GuardedText(value, deadline, cancelled));
      while (matcher.find()) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        if (matcher.start() == matcher.end()) continue;
        if (found.size() == TrafficMatchContext.MAX_PAGES) return new SearchResult(List.copyOf(found), true, null);
        found.add(new Span(matcher.start(), matcher.end()));
        if (System.nanoTime() > deadline) throw new SearchBudget();
      }
      return new SearchResult(List.copyOf(found), false, null);
    } catch (PatternSyntaxException error) { return new SearchResult(List.of(), false, "정규식 오류"); }
    catch (SearchBudget | StackOverflowError error) {
      return found.isEmpty() ? new SearchResult(List.of(), false, "검색 제한") : new SearchResult(List.copyOf(found), true, null);
    }
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
    if(closed)return;closed=true;packetGeneration.incrementAndGet();clearTrafficContext();generation.incrementAndGet();debounce.stop();syntaxDebounce.stop();editedAction=null;if(pending!=null)pending.cancel(true);worker.shutdownNow();
    rawBytes=new byte[0];raw="";pretty="";renderSource="";rendered.setText("");matches=List.of();current=-1;nativeAction=null;
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

  /** Every search action remains available; narrow readers move only optional toggles into a labelled menu. */
  private void rebuildSearchControls(boolean compact) {
    compactSearch=compact;searchControls.removeAll();
    Component[] controls=compact?new Component[]{previous,next,count,searchOptions}:new Component[]{previous,next,caseSensitive,regex,count};
    for(Component control:controls){if(searchControls.getComponentCount()>0)searchControls.add(Box.createHorizontalStrut(3));searchControls.add(control);}
    searchControls.revalidate();
  }
  private final class ResponsiveSearchBar extends JPanel {
    ResponsiveSearchBar(){super(new BorderLayout(4,0));}
    private void adapt(){
      if(searchControls==null||getWidth()<=0)return;
      int full=previous.getPreferredSize().width+next.getPreferredSize().width+caseSensitive.getPreferredSize().width+regex.getPreferredSize().width+count.getPreferredSize().width+12;
      boolean compact=getWidth()<full+120;
      if(compact!=compactSearch)rebuildSearchControls(compact);
    }
    @Override public void doLayout(){adapt();super.doLayout();}
    @Override public Dimension getPreferredSize(){adapt();return super.getPreferredSize();}
  }
  private static final class ResponsivePacketToolbar extends JPanel {
    private final JPanel choices,actions;private final JComboBox<String> picker;private boolean compact;
    ResponsivePacketToolbar(JPanel choices,JComboBox<String> picker,JPanel actions){super(new BorderLayout(4,0));this.choices=choices;this.picker=picker;this.actions=actions;add(choices,BorderLayout.WEST);add(actions,BorderLayout.EAST);}
    private void adapt(){
      if(getWidth()<=0)return;int intrinsic=0;for(Component choice:choices.getComponents())if(choice.isVisible())intrinsic+=choice.getPreferredSize().width;
      boolean next=getWidth()<intrinsic+actions.getPreferredSize().width+4;
      if(next==compact)return;compact=next;remove(next?choices:picker);add(next?picker:choices,BorderLayout.WEST);
    }
    @Override public void doLayout(){adapt();super.doLayout();}
    @Override public Dimension getPreferredSize(){adapt();return super.getPreferredSize();}
  }

  private static final class Tab extends JToggleButton {
    Tab(String title){super(title);}
    @Override public Dimension getPreferredSize(){return new Dimension(Math.max(66,getFontMetrics(WorkbenchUi.font()).stringWidth(getText())+16),Math.max(34,getFontMetrics(WorkbenchUi.font()).getHeight()+8));}
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
