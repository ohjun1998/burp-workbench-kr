import java.net.*;
import java.io.*;

/** Demonstrate why connect success does not identify a previously closed listener. */
public final class LoopbackCloseProbe {
  public static void main(String[] args) throws Exception {
    int destination;
    try (ServerSocket listener=new ServerSocket()) {
      listener.bind(new InetSocketAddress("127.0.0.1",0));
      destination=listener.getLocalPort();
    }
    try (Socket self=new Socket()) {
      self.bind(new InetSocketAddress("127.0.0.1",destination));
      self.connect(new InetSocketAddress("127.0.0.1",destination),1000);
      self.setSoTimeout(1000);
      self.getOutputStream().write(73);self.getOutputStream().flush();
      System.out.println("closed-listener self-connect: connected="+self.isConnected()
          +", local="+self.getLocalSocketAddress()+", remote="+self.getRemoteSocketAddress()
          +", selfEcho="+(self.getInputStream().read()==73));
    }
    try(Socket control=new Socket()) {
      control.bind(new InetSocketAddress("127.0.0.1",0));
      if(control.getLocalPort()==destination)throw new AssertionError("Control selected same source port");
      try {
        control.connect(new InetSocketAddress("127.0.0.1",destination),1000);
        throw new AssertionError("An unrelated listener reused the destination port");
      } catch (ConnectException expected) {
        System.out.println("distinct-source closed-listener control: "+expected.getClass().getSimpleName());
      }
    }
  }
}
