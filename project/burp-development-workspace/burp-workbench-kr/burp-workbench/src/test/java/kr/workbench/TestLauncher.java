package kr.workbench;
/** Ensures failed Swing assertions terminate promptly instead of waiting on UI timers. */
public final class TestLauncher {
 public static void simulateUserConsent(){
  // Synthetic regression hosts explicitly simulate a user's consent. Never shipped in the extension.
  // Approval-specific tests override and restore this decision to exercise denial and default UI policy.
  ActiveRequestApproval.prompt=(owner,plan)->true;
 }
 public static void main(String[] args){try{
  simulateUserConsent();
  Class.forName(args[0]).getMethod("main",String[].class).invoke(null,(Object)new String[0]);System.exit(0);
 }catch(Throwable e){Throwable cause=e instanceof java.lang.reflect.InvocationTargetException&&e.getCause()!=null?e.getCause():e;cause.printStackTrace();System.exit(1);}}
}
