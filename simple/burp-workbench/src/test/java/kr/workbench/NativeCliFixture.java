package kr.workbench;
import java.nio.file.*;import java.util.*;
/** Cross-platform real subprocess fixtures, without shell invocation in the extension. */
final class NativeCliFixture {
 static Path create(Path requested,String mode)throws Exception {
  if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
   String script=mode.equals("failed")?"#!/bin/sh\nread domain\necho fixture-error >&2\nexit 7\n":mode.equals("single")?"#!/bin/sh\nread domain\nprintf 'https://%s/collected\\n' \"$domain\"\n":"#!/bin/sh\nread domain\nprintf 'https://%s/a\\nhttps://%s/a#fragment\\nhttps://%s/b?q=1\\nhttps://other.test/reject\\n' \"$domain\" \"$domain\" \"$domain\"\n";
   Files.writeString(requested,script);requested.toFile().setExecutable(true);return requested;
  }
  Path exe=requested.resolveSibling(requested.getFileName()+".exe"),source=requested.resolveSibling(requested.getFileName()+".cs");String code="using System; public class Fixture { public static int Main(string[] args){ string domain=Console.ReadLine(); "+(mode.equals("failed")?"Console.Error.WriteLine(\"fixture-error\");return 7;":mode.equals("single")?"Console.WriteLine(\"https://\"+domain+\"/collected\");return 0;":"Console.WriteLine(\"https://\"+domain+\"/a\");Console.WriteLine(\"https://\"+domain+\"/a#fragment\");Console.WriteLine(\"https://\"+domain+\"/b?q=1\");Console.WriteLine(\"https://other.test/reject\");return 0;")+" }}";Files.writeString(source,code);
  Path csc=Path.of(System.getenv("WINDIR"),"Microsoft.NET","Framework64","v4.0.30319","csc.exe");if(!Files.exists(csc))csc=Path.of(System.getenv("WINDIR"),"Microsoft.NET","Framework","v4.0.30319","csc.exe");Process compiler=new ProcessBuilder(csc.toString(),"/nologo","/target:exe","/out:"+exe,source.toString()).redirectErrorStream(true).start();String output=new String(compiler.getInputStream().readAllBytes());if(compiler.waitFor()!=0)throw new IllegalStateException(output);Files.delete(source);return exe;
 }
}
