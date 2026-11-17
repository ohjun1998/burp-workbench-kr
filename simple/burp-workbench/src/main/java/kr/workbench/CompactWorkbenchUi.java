package kr.workbench;

import java.awt.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.TableRowSorter;

/** Compact, title-free workspace controls. Never performs network work. */
public final class CompactWorkbenchUi {
  private CompactWorkbenchUi() {}

  public static JTabbedPane pages(){return new JTabbedPane(){public void updateUI(){setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI(){protected int calculateTabAreaHeight(int p,int r,int m){return 0;}protected Insets getContentBorderInsets(int p){return new Insets(0,0,0,0);}protected void paintTabArea(Graphics g,int p,int s){}protected void paintContentBorder(Graphics g,int p,int s){}});} };}

  public static JPanel toolbar(Component context, Component actions) {
    // Keep adjacent controls together, without the extra padding of nested rows.
    for (Component part : new Component[]{context, actions})
      if (part instanceof JPanel panel && panel.getLayout() instanceof FlowLayout flow) {
        flow.setHgap(6); flow.setVgap(2);
      }
    JPanel bar=new JPanel(new BorderLayout(8,4)){
      boolean stacked;
      private void adapt(){boolean next=getWidth()>0&&getWidth()<context.getMinimumSize().width+actions.getPreferredSize().width+16;if(next==stacked)return;stacked=next;remove(actions);add(actions,next?BorderLayout.SOUTH:BorderLayout.EAST);}
      public void doLayout(){adapt();super.doLayout();}
      public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
      public Dimension getPreferredSize(){if(context!=null)adapt();return super.getPreferredSize();}
    };
    bar.add(context);bar.add(actions,BorderLayout.EAST);return bar;
  }

  public static JPanel disclosure(String name, String title, Component content, boolean expanded) {
    JPanel panel = new JPanel(new BorderLayout(0, 4));
    panel.setName(name);
    JToggleButton toggle = new JToggleButton(title, expanded);
    toggle.setName(name + ".toggle");
    toggle.setHorizontalAlignment(SwingConstants.LEFT);
    toggle.getAccessibleContext().setAccessibleName(title);
    content.setVisible(expanded);
    toggle.addActionListener(e -> {
      content.setVisible(toggle.isSelected());
      panel.revalidate(); panel.repaint();
    });
    panel.add(toggle, BorderLayout.NORTH); panel.add(content);
    return panel;
  }

  /** Literal search, including metacharacters, over the existing model and sorter. */
  public static JTextField search(JTable table, String name, String hint) {
    JTextField field = new WorkbenchSkin.SearchField(18,hint);
    field.setName(name); field.setToolTipText(hint);
    field.getAccessibleContext().setAccessibleName(hint);
    if (!(table.getRowSorter() instanceof TableRowSorter<?>)) table.setAutoCreateRowSorter(true);
    field.getDocument().addDocumentListener(new DocumentListener() {
      void changed() {
        String query = field.getText().strip().toLowerCase(java.util.Locale.ROOT);
        table.putClientProperty("wb.search.query",query);
        if(table.getClientProperty("wb.search.apply") instanceof Runnable apply){apply.run();return;}
        @SuppressWarnings("unchecked")
        TableRowSorter<javax.swing.table.TableModel> sorter =
            (TableRowSorter<javax.swing.table.TableModel>) table.getRowSorter();
        sorter.setRowFilter(query.isEmpty() ? null : new RowFilter<>() {
          public boolean include(Entry<? extends javax.swing.table.TableModel, ? extends Integer> row) {
            for (int i = 0; i < row.getValueCount(); i++)
              if (row.getStringValue(i).toLowerCase(java.util.Locale.ROOT).contains(query)) return true;
            return false;
          }
        });
      }
      public void insertUpdate(DocumentEvent e) { changed(); }
      public void removeUpdate(DocumentEvent e) { changed(); }
      public void changedUpdate(DocumentEvent e) { changed(); }
    });
    return field;
  }

  /** A table fills wide workspaces but can still scroll at its readable minimum. */
  public static void columns(JTable table, int... widths) {
    table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
      var column = table.getColumnModel().getColumn(i);
      column.setPreferredWidth(widths[i]); column.setWidth(widths[i]);
      column.setMinWidth(Math.min(widths[i], 55));
    }
  }
}
