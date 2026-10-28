package kr.workbench;

import java.awt.*;
import java.awt.event.KeyEvent;
import javax.swing.*;

/** Handles Send before editor/combo bindings, only while this Repeater has focus. */
final class JsRepeaterShortcuts implements AutoCloseable {
  private final JComponent root;
  private final JButton send;
  private final KeyboardFocusManager manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();
  private final KeyEventDispatcher dispatcher;
  private boolean installed,pressed,suppressed;

  JsRepeaterShortcuts(JComponent root,JButton send){
    this.root=root;this.send=send;
    dispatcher=event->root.isShowing()&&handle(event,manager.getFocusOwner());
  }
  void suppressUntilRelease(){suppressed=true;}
  void install(){if(!installed){manager.addKeyEventDispatcher(dispatcher);installed=true;}}
  @Override public void close(){if(installed){manager.removeKeyEventDispatcher(dispatcher);installed=false;}pressed=false;suppressed=false;}

  boolean handle(KeyEvent event,Component focused){
    if(focused==null||!(focused==root||SwingUtilities.isDescendingFrom(focused,root))){pressed=false;return false;}
    if(suppressed&&(event.getKeyCode()==KeyEvent.VK_SPACE||event.getID()==KeyEvent.KEY_TYPED&&event.getKeyChar()==' ')) {
      if(event.getID()==KeyEvent.KEY_RELEASED){suppressed=false;pressed=false;}
      event.consume();return true;
    }
    if(event.getID()==KeyEvent.KEY_TYPED&&pressed&&event.getKeyChar()==' '){event.consume();return true;}
    if(event.getKeyCode()!=KeyEvent.VK_SPACE)return false;
    if(event.getID()==KeyEvent.KEY_RELEASED&&pressed){pressed=false;event.consume();return true;}
    if(event.getID()!=KeyEvent.KEY_PRESSED||!event.isControlDown()||event.isAltDown()||event.isMetaDown()||event.isShiftDown())return false;
    event.consume();
    if(!pressed){pressed=true;if(send.isEnabled())send.doClick(0);}
    return true;
  }
}
