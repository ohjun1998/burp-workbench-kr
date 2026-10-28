package kr.workbench;
/** Makes standalone Swing test process termination explicit, preserving all assertion failures. */
public final class TestMain {public static void main(String[] args){try{Class.forName("kr.workbench."+args[0]).getMethod("main",String[].class).invoke(null,(Object)new String[0]);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}}
