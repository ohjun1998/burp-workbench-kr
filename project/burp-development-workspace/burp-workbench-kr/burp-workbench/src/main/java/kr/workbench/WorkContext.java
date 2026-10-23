package kr.workbench;

import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/**
 * Small, per-extension selection context. It owns no packet, credential, response, or growing
 * history. A comparison stamp means "started with these inputs", never success or completion.
 */
final class WorkContext {
  record Snapshot(String requestId, long sequence, String method, String target, String origin,
                  int selectedCount, long selectionRevision, long configurationRevision) {
    boolean selected() { return !requestId.isEmpty(); }
    String description() {
      if (!selected()) return "트래픽에서 작업할 요청을 선택하세요";
      return "원본 #" + (sequence + 1) + " · " + method + " " + target
          + (selectedCount > 1 ? " · " + selectedCount + "개 선택" : "");
    }
  }

  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final Set<Component> observed = Collections.newSetFromMap(new IdentityHashMap<>());
  private String requestId = "", method = "", target = "", origin = "", selectionKey = "";
  private String configurationFingerprint = "";
  private long sequence, selectionRevision, configurationRevision;
  private int selectedCount;
  private boolean unreviewedEdits;
  private Snapshot lastComparison;

  void onChange(Runnable listener) { listeners.add(Objects.requireNonNull(listener)); }
  private void changed() { for (Runnable listener : listeners) listener.run(); }

  void select(DiskTrafficStore.PacketRef packet) { select(packet, new int[0]); }

  /** Model row indices identify the whole batch; no packet reconstruction or network is needed. */
  void select(DiskTrafficStore.PacketRef packet, int[] selectedModelRows) {
    Objects.requireNonNull(packet);
    int[] rows = selectedModelRows.clone();
    Arrays.sort(rows);
    Fingerprint fingerprint = new Fingerprint();
    fingerprint.add(packet.id());
    for (int row : rows) fingerprint.add(row);
    String nextKey = fingerprint.finish();
    synchronized (this) {
      if (selectionKey.equals(nextKey)) return;
      requestId = packet.id();
      sequence = packet.sequence();
      method = packet.method();
      target = displayUrl(packet.url());
      origin = displayOrigin(packet.url());
      selectedCount = Math.max(1, rows.length);
      selectionKey = nextKey;
      selectionRevision++;
    }
    changed();
  }

  void clear() {
    synchronized (this) {
      if (requestId.isEmpty()) return;
      requestId = method = target = origin = selectionKey = "";
      sequence = 0;
      selectedCount = 0;
      selectionRevision++;
    }
    changed();
  }

  synchronized Snapshot snapshot() {
    return new Snapshot(requestId, sequence, method, target, origin, selectedCount,
        selectionRevision, configurationRevision);
  }

  void configurationEdited() {
    synchronized (this) {
      // Multiple document events during one edit do not need a growing revision counter.
      if (unreviewedEdits) return;
      unreviewedEdits = true;
      configurationRevision++;
    }
    changed();
  }

  void configure(String fingerprint) {
    Objects.requireNonNull(fingerprint);
    boolean notify;
    synchronized (this) {
      notify = unreviewedEdits || !configurationFingerprint.equals(fingerprint);
      if (!configurationFingerprint.equals(fingerprint) && !unreviewedEdits)
        configurationRevision++;
      configurationFingerprint = fingerprint;
      unreviewedEdits = false;
    }
    if (notify) changed();
  }

  Snapshot comparisonStarted() {
    Snapshot stamp;
    synchronized (this) {
      stamp = snapshot();
      if (!stamp.selected()) return null;
      lastComparison = stamp;
    }
    changed();
    return stamp;
  }

  synchronized boolean comparisonStale() {
    return lastComparison != null && (unreviewedEdits
        || lastComparison.selectionRevision() != selectionRevision
        || lastComparison.configurationRevision() != configurationRevision);
  }

  synchronized String status() {
    if (lastComparison == null) return "선택 요청을 도구 간 공유 · 비교 실행 이력 없음";
    if (comparisonStale()) return "선택·계정·설정 변경 · 이전 비교 결과를 현재 조건의 완료로 사용하지 마세요";
    return "현재 조건으로 비교 시작 기록됨 · 완료·성공 여부는 판정 근거에서 확인";
  }

  /** Observe configuration controls only, not result viewers or capture streams. EDT only. */
  void observeSettings(Component... components) {
    for (Component component : components) {
      if (component == null || !observed.add(component)) continue;
      if (component instanceof JTextComponent text && text.isEditable()) {
        text.getDocument().addDocumentListener(new DocumentListener() {
          public void insertUpdate(DocumentEvent e) { configurationEdited(); }
          public void removeUpdate(DocumentEvent e) { configurationEdited(); }
          public void changedUpdate(DocumentEvent e) { configurationEdited(); }
        });
      }
      if (component instanceof AbstractButton button)
        button.addItemListener(e -> configurationEdited());
      if (component instanceof JComboBox<?> combo)
        combo.addActionListener(e -> configurationEdited());
      if (component instanceof JTable table)
        table.getModel().addTableModelListener(e -> configurationEdited());
      if (component instanceof JSpinner spinner)
        spinner.addChangeListener(e -> configurationEdited());
      if (component instanceof Container container) observeSettings(container.getComponents());
    }
  }

  static String displayOrigin(String url) {
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null) return "대상 URL 확인 필요";
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
    } catch (Exception ignored) { return "대상 URL 확인 필요"; }
  }

  static String displayUrl(String url) {
    try {
      URI uri = URI.create(url);
      if (uri.getHost() == null) return "대상 URL 확인 필요";
      // Preserve raw path spelling while dropping user-info, query parameters and fragments.
      String safe = displayOrigin(url) + Objects.toString(uri.getRawPath(), "");
      return safe.length() <= 2048 ? safe : safe.substring(0, 2048) + "…";
    } catch (Exception ignored) { return "대상 URL 확인 필요"; }
  }

  /** Hash effective settings, including credential changes, without retaining or exporting values. */
  static String authFingerprint(AuthComparePanel.Context c) {
    Fingerprint f = new Fingerprint();
    f.add(c.origin()).add(c.writes()).add(c.filter()).add(c.largeFiles());
    f.sorted(c.names()).sorted(c.identityCookies());
    for (int i = 0; i < c.profiles().length; i++) {
      var profile = c.profiles()[i];
      f.add(profile != null);
      if (profile != null) {
        f.add(profile.key()).add(profile.origin()).add(profile.headers().size());
        new TreeMap<>(profile.headers()).forEach((key, value) -> f.add(key).add(value));
      }
      f.add(c.labels()[i]).add(c.owners()[i]).add(c.roles()[i]).add(c.tenants()[i]).add(c.accountIds()[i]);
      var session = c.sessions()[i];
      if (session == null) f.add("no-refresh");
      else f.add(session.enabled()).add(session.url()).add(session.tokenPath()).add(session.tokenHeader())
          .add(session.prefix()).add(session.csrfPath()).add(session.csrfHeader()).add(session.identityPath());
      var identity = c.sessionChecks()[i];
      if (identity == null) f.add("no-identity-check");
      else {
        f.add(identity.url()).add(identity.identityPath()).add(identity.expectedIdentity()).add(identity.claims().size());
        for (var claim : identity.claims()) f.add(claim.label()).add(claim.path()).add(claim.expected());
      }
    }
    var rules = c.rules();
    f.sorted(rules.ignored()).sorted(rules.important()).add(rules.ownerPath())
        .add(rules.policy()).add(rules.unorderedArrays());
    f.add(rules.denied().size());
    for (var denial : rules.denied()) f.add(denial.path()).add(denial.value());
    f.add(c.policy().fallback).add(c.policy().rules.size());
    for (var rule : c.policy().rules) f.add(rule.method()).add(rule.path()).add(rule.access());
    f.add(c.matrix().rules.size());
    for (var rule : c.matrix().rules) {
      f.add(rule.method()).add(rule.path()).add(rule.slots().size());
      for (var expectation : rule.slots()) f.add(expectation);
    }
    f.add(c.responseRules().rules.size());
    for (var rule : c.responseRules().rules) f.add(rule.target()).add(rule.value()).add(rule.regex());
    var effect = c.effect();
    f.add(effect.enabled()).add(effect.method()).add(effect.path()).add(effect.url())
        .add(effect.jsonPath()).add(effect.expected()).add(effect.mode()).add(effect.attempts());
    f.add(c.effectRules().size());
    for (var rule : c.effectRules()) {
      var config = rule.config();
      f.add(rule.name()).add(rule.origin()).add(config.enabled()).add(config.method()).add(config.path())
          .add(config.url()).add(config.jsonPath()).add(config.expected()).add(config.mode()).add(config.attempts());
    }
    return f.finish();
  }

  private static final class Fingerprint {
    private final MessageDigest digest;
    Fingerprint() {
      try { digest = MessageDigest.getInstance("SHA-256"); }
      catch (Exception e) { throw new IllegalStateException(e); }
    }
    Fingerprint add(Object value) {
      byte[] bytes = Objects.toString(value, "<null>").getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
      digest.update(bytes);
      return this;
    }
    Fingerprint sorted(Collection<String> values) {
      add(values.size());
      values.stream().sorted().forEach(this::add);
      return this;
    }
    String finish() { return HexFormat.of().formatHex(digest.digest()); }
  }
}
