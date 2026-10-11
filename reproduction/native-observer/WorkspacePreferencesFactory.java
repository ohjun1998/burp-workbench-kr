package check;
import java.util.*;import java.util.prefs.*;import java.nio.file.*;import java.io.*;import java.nio.charset.StandardCharsets;
/** Test-process preferences only; does not open Windows registry or the user's Burp preferences. */
public final class WorkspacePreferencesFactory implements PreferencesFactory{
 private final Preferences user=new Store(null,"","user"),system=new Store(null,"","system");
 public Preferences userRoot(){return user;}public Preferences systemRoot(){return system;}
 static final class Store extends AbstractPreferences{
  final String kind;final Path path;final Properties values=new Properties();
  Store(Store parent,String name,String kind){super(parent,name);this.kind=kind;String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(absolutePath().getBytes(StandardCharsets.UTF_8));path=Path.of(System.getProperty("test.preferences.root")).resolve(kind+"-"+encoded+".properties");try{if(Files.exists(path))try(var in=Files.newInputStream(path)){values.load(in);}}catch(IOException e){throw new IllegalStateException(e);}}
  protected void putSpi(String key,String value){values.setProperty(key,value);}
  protected String getSpi(String key){return values.getProperty(key);}
  protected void removeSpi(String key){values.remove(key);}
  protected String[] keysSpi(){return values.stringPropertyNames().toArray(String[]::new);}
  protected String[] childrenNamesSpi(){return new String[0];}
  protected AbstractPreferences childSpi(String name){return new Store(this,name,kind);}
  protected void removeNodeSpi()throws BackingStoreException{try{Files.deleteIfExists(path);}catch(IOException e){throw new BackingStoreException(e);}}
  protected void syncSpi()throws BackingStoreException{flushSpi();}
  protected void flushSpi()throws BackingStoreException{try{Files.createDirectories(path.getParent());try(var out=Files.newOutputStream(path)){values.store(out,"Isolated Burp GUI test");}}catch(IOException e){throw new BackingStoreException(e);}}
 }
}
