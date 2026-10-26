package kr.workbench;

import burp.api.montoya.http.message.requests.HttpRequest;
import java.awt.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/** Local configuration coverage only. Building this preview never sends a request. */
final class AuthReadiness {
  record Row(String request, String account, String policy, String effect,
             String limitation, String action, boolean incomplete, String method, String url, int slot, int requestIndex) {
    Row(String request, String account, String policy, String effect, String limitation, String action, boolean incomplete) {
      this(request, account, policy, effect, limitation, action, incomplete, "", "", -1, -1);
    }
  }
  record Report(List<Row> rows, boolean automatic) {
    Report { rows = List.copyOf(rows); }
    long incomplete() { return rows.stream().filter(Row::incomplete).count(); }
    String summary() {
      if (automatic) return "자동 비교 준비 · 앞으로 들어올 요청의 설정 적용 범위는 요청마다 다릅니다.";
      return "선택 요청의 지정 계정 행 " + rows.size() + "개 · 설정 보완 필요 " + incomplete() + "개 · 실행 증거 미생성";
    }
    String text() {
      StringBuilder out = new StringBuilder(summary());
      for (Row row : rows) out.append("\n\n").append(row.request()).append(" · ").append(row.account())
          .append("\n권한: ").append(row.policy()).append("\n처리 결과: ").append(row.effect())
          .append("\n판정 한계: ").append(row.limitation()).append("\n다음 설정: ").append(row.action());
      return out.toString();
    }
  }

  static Report selected(AuthComparePanel.Context c, List<HttpRequest> requests) {
    List<Row> rows = new ArrayList<>();
    for (int requestIndex = 0; requestIndex < requests.size(); requestIndex++)
      for (int slot = 0; slot < AuthComparePanel.SLOTS; slot++)
        if (c.profiles()[slot] != null) rows.add(row(c, requests.get(requestIndex), slot, requestIndex));
    return new Report(rows, false);
  }

  private static Row row(AuthComparePanel.Context c, HttpRequest request, int slot, int requestIndex) {
    String target = request.method() + " " + URI.create(request.url()).getPath();
    BountyMatrix.Expected expected = c.matrix().expected(request.method(), request.url(), slot);
    AuthPolicy.Access access = c.policy().forRequest(request.method(), request.url());
    boolean policyMissing = false;
    List<String> limitations = new ArrayList<>(), actions = new ArrayList<>();
    String policy;
    if (expected != BountyMatrix.Expected.UNKNOWN) {
      policy = "권한표 · " + (expected == BountyMatrix.Expected.ALLOW ? "허용" : "차단");
    } else if (access == AuthPolicy.Access.PUBLIC) {
      policy = "경로 정책 · 공개/공유 허용";
      limitations.add("공개/공유 정책의 타당성은 업무 기준으로 확인");
    } else if (access == AuthPolicy.Access.OWNER_ONLY) {
      List<String> missing = new ArrayList<>();
      if (c.rules().ownerPath().isBlank()) missing.add("소유 JSON 경로");
      if (c.owners()[slot].isBlank()) missing.add("계정 소유값");
      policyMissing = !missing.isEmpty();
      policy = "소유자 전용 · " + (policyMissing ? String.join("·", missing) + " 없음" : "필드·계정값 설정됨");
      limitations.add(policyMissing ? "소유 관계에 따른 접근 위반을 확정하기 어려움" : "응답에 소유 필드가 있는지와 실제 소유 관계는 실행 후 확인");
      if (policyMissing) actions.add("비교 설정에서 소유 JSON 경로·계정 소유값 지정 또는 권한표 설정");
    } else {
      String path = URI.create(request.url()).getPath();
      boolean matching = c.matrix().rules.stream().anyMatch(rule ->
          (rule.method().equals("*") || rule.method().equals(request.method())) && rule.match().matcher(path).matches());
      policy = matching ? "권한표 · 이 계정의 기대 권한 미지정" : "적용되는 권한표·경로 정책 없음";
      policyMissing = true;
      limitations.add("같은 응답이 공개/공유 허용인지 권한 위반인지 확정하기 어려움");
      actions.add("세션·권한 매트릭스에서 이 메서드·경로·계정의 허용/차단 지정");
    }

    boolean read = GraphqlSemantics.read(request), effectMissing = false;
    String effect;
    AuthEffectCheck.Config effectConfig = c.effectFor(request);
    if (effectConfig.enabled()) {
      try {
        AuthEffectCheck.observer(effectConfig, request, c.names());
        effect = "전후 GET 조회 적용 · " + mode(effectConfig.mode()) + " · 최대 " + effectConfig.attempts() + "회";
        limitations.add("지정 조회 항목만 관측 · 조회 성공·지연 처리·동시 변경은 실행 후 확인");
      } catch (IllegalArgumentException ex) {
        effect = "조회 설정 확인 필요 · 대상과 조회 주소의 Origin/인증값 확인";
        effectMissing = true;
        limitations.add("전 상태 조회가 성립하지 않으면 해당 쓰기 요청은 전송되지 않음");
        actions.add("비교 설정 → 처리 결과 확인에서 같은 Origin/SCOPE의 GET 조회 지정");
      }
    } else if (read) {
      effect = "읽기 요청 · 쓰기 조회 대상 아님";
    } else {
      effectMissing = true;
      effect = (c.effect().enabled() || c.effectRules().stream().anyMatch(rule -> rule.config().enabled())) ? "조회 규칙 미적용 · 대상 메서드/경로 불일치" : "쓰기 전후 조회 꺼짐";
      limitations.add("2xx·403 등 응답만으로 실제 저장·삭제·권한 변경 성공 여부를 확정할 수 없음");
      actions.add("비교 설정 → 처리 결과 확인에서 이 요청 연결 및 결과 GET·관측 필드 지정");
    }
    if (limitations.isEmpty()) limitations.add("설정된 기대 권한을 기준으로 비교 · 실제 신원·응답·업무 결과는 실행 후 확인");
    if (actions.isEmpty()) actions.add("설정한 권한과 관측 범위가 실제 업무 기준에 맞는지 확인");
    return new Row(target, c.labels()[slot], policy, effect, String.join(" / ", limitations),
        String.join(" / ", actions), policyMissing || effectMissing, request.method(), request.url(), slot, requestIndex);
  }

  static Report automatic(AuthComparePanel.Context c) {
    List<Row> rows = new ArrayList<>();
    for (int slot = 0; slot < AuthComparePanel.SLOTS; slot++) {
      if (c.profiles()[slot] == null) continue;
      final int index = slot;
      long assigned = c.matrix().rules.stream().filter(rule -> rule.slots().get(index) != BountyMatrix.Expected.UNKNOWN).count();
      String policy = "계정 권한표 " + assigned + "개 · 경로 정책 " + c.policy().rules.size()
          + "개 · 기본 " + switch (c.policy().fallback) {
            case PUBLIC -> "공개/공유 허용"; case OWNER_ONLY -> "소유자 전용"; case UNSPECIFIED -> "미설정";
          };
      String effect = !c.writes() ? "쓰기 요청 전송 꺼짐" : (c.effect().enabled() || c.effectRules().stream().anyMatch(rule -> rule.config().enabled()))
          ? "쓰기 조회 " + Math.max(1, c.effectRules().size()) + "개 규칙 설정 · 일치하는 Origin/메서드/경로만 적용" : "쓰기 전송 허용 · 전후 조회 꺼짐";
      rows.add(new Row("앞으로 수신할 SCOPE 요청", c.labels()[slot], policy, effect,
          "규칙 개수는 전체 URL·계정 적용을 보장하지 않음. 미지정 권한의 위반 여부와 미적용 쓰기의 처리 결과는 확정하기 어려움.",
          "정확한 적용 범위는 선택 요청 비교의 준비 화면에서 확인. 실행별 설정은 비교 기록의 ‘비교 전 설정’에서 확인.", true, "", "", slot, -1));
    }
    return new Report(rows, true);
  }

  static String mode(AuthEffectCheck.Mode mode) {
    return switch (mode) {
      case EQUALS -> "기대값 변경"; case CHANGED -> "값 변경 관측"; case ABSENT -> "대상/필드 삭제";
      case DELTA_ABOVE -> "숫자 증가 경계"; case DELTA_BELOW -> "숫자 감소 경계";
    };
  }

  static JPanel content(AuthComparePanel.Context c, Report report, List<String> blocked) {
    return content(c, report, blocked, null);
  }
  static JPanel content(AuthComparePanel.Context c, Report report, List<String> blocked,
      java.util.function.BiConsumer<Row, String> edit) {
    return content(c, report, blocked, edit, -1, -1);
  }
  static JPanel content(AuthComparePanel.Context c, Report report, List<String> blocked,
      java.util.function.BiConsumer<Row, String> edit, int focusedRequest, int focusedSlot) {
    JPanel panel = new JPanel(new BorderLayout(8, 8));
    panel.setName("wb.auth.readiness");
    String introduction = report.summary() + "\n① 설정됨: 입력값 보관 → ② 적용됨: 선택 요청에 규칙 일치 → ③ 실행 확인: 비교 후 근거에서 확인\n이 화면은 설정 유무와 적용 범위를 확인합니다. 실제 신원·접근 결과·조회 성공을 검증한 상태는 아닙니다. 원본과 동일 인증인 계정은 실행 시 기준 계정으로 구분합니다.";
    if (!blocked.isEmpty()) introduction += "\n비교를 시작할 수 없는 조건:\n" + String.join("\n", blocked);
    panel.add(note(introduction, 4), BorderLayout.NORTH);
    DefaultTableModel model = new DefaultTableModel(new String[]{"요청", "계정", "적용 권한", "쓰기 결과 조회", "판정 한계", "다음 설정"}, 0) {
      public boolean isCellEditable(int row, int column) { return false; }
    };
    for (Row row : report.rows()) model.addRow(new Object[]{row.request(), row.account(), row.policy(), row.effect(), row.limitation(), row.action()});
    JTable table = new AuthLayout.Table(model);
    table.setName("wb.auth.readiness.table");
    table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    // Long explanations remain in the selected-row inspector, not six wide columns.
    table.removeColumn(table.getColumnModel().getColumn(5));
    table.removeColumn(table.getColumnModel().getColumn(4));
    int[] widths = {260, 110, 220, 250};
    for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    table.setRowSorter(sorter);
    JComboBox<String> filter = new JComboBox<>(new String[]{"전체", "보완 필요", "쓰기 요청"});
    filter.setName("wb.auth.readiness.filter");
    JTextField search = new JTextField(18); search.setName("wb.auth.readiness.search");
    JLabel visible = new JLabel(); visible.setName("wb.auth.readiness.visible");
    JTextArea selected = note("표에서 행을 선택하면 판정 한계와 다음 설정을 아래에서 읽을 수 있습니다.", 5);
    table.getSelectionModel().addListSelectionListener(event -> {
      int at = table.getSelectedRow();
      if (at >= 0) {
        Row row = report.rows().get(table.convertRowIndexToModel(at));
        selected.setText(row.request() + " · " + row.account() + "\n권한: " + row.policy() + "\n조회: " + row.effect()
            + "\n판정 한계: " + row.limitation() + "\n다음 설정: " + row.action());
        selected.setCaretPosition(0);
      } else selected.setText("조건에 맞는 행을 선택하세요. 판정 한계와 다음 설정을 여기서 확인합니다.");
    });
    Runnable refilter = () -> {
      int previous = table.getSelectedRow() < 0 ? -1 : table.convertRowIndexToModel(table.getSelectedRow());
      String query = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
      int kind = filter.getSelectedIndex();
      sorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
        public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
          Row row = report.rows().get(entry.getIdentifier());
          return matches(row, kind, query);
        }
      });
      visible.setText(table.getRowCount() + " / " + report.rows().size() + "행");
      int next = previous < 0 ? -1 : table.convertRowIndexToView(previous);
      if (next < 0 && table.getRowCount() > 0) next = 0;
      if (next >= 0) table.setRowSelectionInterval(next, next);
    };
    filter.addActionListener(event -> refilter.run());
    search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
      public void insertUpdate(javax.swing.event.DocumentEvent event) { refilter.run(); }
      public void removeUpdate(javax.swing.event.DocumentEvent event) { refilter.run(); }
      public void changedUpdate(javax.swing.event.DocumentEvent event) { refilter.run(); }
    });
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(selected));
    split.setResizeWeight(.65); split.setBorder(null);
    JPanel center = new JPanel(new BorderLayout(4, 4));
    center.add(WorkbenchUi.row(new JLabel("표시"), filter, new JLabel("요청·계정 찾기"), search, visible), BorderLayout.NORTH);
    center.add(split); panel.add(center);
    String rules = "JSON 차단 규칙 " + c.rules().denied().size() + "개 · 응답 차단 규칙 " + c.responseRules().rules.size()
        + "개 · 비교 제외 필드 " + c.rules().ignored().size() + "개\n규칙 유무만으로 해당 서비스의 차단 응답을 모두 식별할 수는 없습니다.";
    if (report.automatic()) rules += "\n자동 비교 ON 이후 조건에 맞는 신규 요청을 지정 계정으로 실제 재전송합니다. 설정은 OFF 후 수정합니다.";
    JPanel bottom = new JPanel(new BorderLayout(4, 4));
    bottom.add(note(rules, 3), BorderLayout.CENTER);
    if (edit != null) {
      JPanel actions = WorkbenchUi.row();
      String[] titles = {"이 계정 설정", "이 요청 권한표 수정", "이 요청 쓰기 조회 수정"};
      String[] kinds = {"account", "policy", "effect"};
      for (int i = 0; i < titles.length; i++) {
        String kind = kinds[i]; JButton button = new JButton(titles[i]); button.setName("wb.auth.readiness.fix." + kind);
        button.addActionListener(event -> { int at = table.getSelectedRow(); if (at >= 0) edit.accept(report.rows().get(table.convertRowIndexToModel(at)), kind); });
        table.getSelectionModel().addListSelectionListener(event -> {
          int at = table.getSelectedRow(); Row row = at < 0 ? null : report.rows().get(table.convertRowIndexToModel(at));
          button.setEnabled(row != null && (!kind.equals("effect") || java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(row.method())));
        });
        actions.add(button);
      }
      bottom.add(actions, BorderLayout.NORTH);
    }
    panel.add(bottom, BorderLayout.SOUTH);
    panel.setPreferredSize(new Dimension(880, 500));
    WorkbenchUi.style(panel);
    refilter.run();
    if (!report.rows().isEmpty()) {
      int selectedRow = 0;
      for (int i = 0; i < report.rows().size(); i++) {
        Row row = report.rows().get(i);
        if (row.requestIndex() == focusedRequest && row.slot() == focusedSlot) { selectedRow = i; break; }
      }
      int view = table.convertRowIndexToView(selectedRow);
      if (view >= 0) { table.setRowSelectionInterval(view, view); table.scrollRectToVisible(table.getCellRect(view, 0, true)); }
    }
    return panel;
  }

  static boolean matches(Row row, int filter, String query) {
    if (filter == 1 && !row.incomplete()) return false;
    if (filter == 2 && !java.util.Set.of("POST", "PUT", "PATCH", "DELETE").contains(row.method())) return false;
    String text = (row.request() + " " + row.account() + " " + row.policy() + " " + row.effect())
        .toLowerCase(java.util.Locale.ROOT);
    return text.contains(query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT));
  }

  static JTextArea note(String text, int rows) {
    JTextArea area = new JTextArea(text, rows, 40);
    area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
    area.setFont(WorkbenchUi.font()); area.setOpaque(false);
    return area;
  }
}

