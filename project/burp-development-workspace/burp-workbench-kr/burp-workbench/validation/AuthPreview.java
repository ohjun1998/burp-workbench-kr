package kr.workbench;
import burp.api.montoya.*;import burp.api.montoya.http.message.requests.*;import javax.swing.*;import java.awt.*;import java.awt.image.*;import java.nio.file.*;import java.util.*;import javax.imageio.ImageIO;
public class AuthPreview extends WorkbenchTest {
 static void layout(Component c){if(c instanceof Container x){if(x instanceof JScrollPane sp && sp.getViewport().getView() instanceof JTable t)sp.setColumnHeaderView(t.getTableHeader());x.doLayout();for(Component child:x.getComponents())layout(child);}}
 public static void main(String[] args)throws Exception{edt(()->{try{
 Font f=Font.createFonts(new java.io.File("/workspace/scratch/NanumGothic-Regular.ttf"))[0].deriveFont(13f);GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);for(Object key:new ArrayList<>(UIManager.getDefaults().keySet()))if(UIManager.get(key) instanceof Font)UIManager.put(key,f);
 MontoyaApi api=proxy(MontoyaApi.class,(p,m,a)->def(m));AuthComparePanel panel=new AuthComparePanel(api,java.util.List::of);
 var original=request("http://127.0.0.1:8088/api/accounts/101","GET",true,java.util.List.of(header("Cookie","session=admin"),header("Accept","application/json")));
 var base=response(200,"{\n  \"id\": 101,\n  \"owner\": \"admin\",\n  \"balance\": 50000\n}");
 for(String id:new String[]{"admin","userA","userB"})panel.observe(request(original.url(),"GET",true,java.util.List.of(header("Cookie","session="+id))),base);
 var candidates=new ArrayList<>(panel.credentials.values());for(int i=0;i<3;i++)panel.assign(i,candidates.get(i));
 var run=new AuthComparePanel.Run(1,original,base,panel.context());run.note="완료";for(int i=0;i<3;i++){run.packets[i].request=request(original.url(),"GET",true,java.util.List.of(header("Cookie","session="+new String[]{"admin","userA","userB"}[i]),header("Accept","application/json")));run.packets[i].response=i==1?response(403,"{\n  \"error\": \"Access denied\"\n}"):base;run.packets[i].note=i==1?"접근 거부 응답":"원본과 유사 · 검토 필요";}
 panel.runs.add(run);panel.historyModel.addRow(new Object[]{1,original.url(),panel.summary(run)});panel.history.setRowSelectionInterval(0,0);panel.show(run);panel.addNotify();panel.setSize(1600,980);for(int pass=0;pass<3;pass++)layout(panel);BufferedImage image=new BufferedImage(1600,980,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();panel.printAll(g);g.dispose();ImageIO.write(image,"png",new java.io.File(args[0]));panel.close();
 }catch(Exception e){throw new RuntimeException(e);}});}
}
