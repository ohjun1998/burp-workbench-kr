package lab.enterprise;
import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import java.nio.charset.*;import java.io.*;import java.util.zip.*;import java.util.*;
/** Controlled response boundaries: never external redirects or unbounded delays/bodies. */
@RestController public final class BoundaryController {
 @GetMapping("/api/diagnostics/boundary/{kind}") public ResponseEntity<byte[]> boundary(@PathVariable String kind)throws Exception{
  byte[] body="{\"message\":\"한글 + % < >\",\"trainingOnly\":true}".getBytes(StandardCharsets.UTF_8);String type="application/json;charset=UTF-8";var response=ResponseEntity.ok();
  if(kind.equals("gzip")||kind.equals("deflate")){var bytes=new ByteArrayOutputStream();try(OutputStream zipped=kind.equals("gzip")?new GZIPOutputStream(bytes):new DeflaterOutputStream(bytes)){zipped.write(body);}body=bytes.toByteArray();response.header("Content-Encoding",kind);}
  else if(kind.equals("cp949")){body="한글 인코딩 fixture".getBytes(Charset.forName("MS949"));type="text/plain;charset=MS949";}
  else if(kind.equals("empty"))body=new byte[0];
  else if(kind.equals("large")){body=("WB_LIMIT_"+"x".repeat(600000)).getBytes(StandardCharsets.UTF_8);type="text/plain;charset=UTF-8";}
  else if(kind.equals("invalid-json"))body="{invalid".getBytes(StandardCharsets.UTF_8);
  else if(kind.equals("slow")){Thread.sleep(450);type="text/plain";body="delayed fixture".getBytes(StandardCharsets.UTF_8);}
  else if(kind.equals("redirect"))return ResponseEntity.status(302).header("Location","/diagnostics").body("redirect is not followed by fixtures".getBytes(StandardCharsets.UTF_8));
  else if(!kind.equals("utf8"))return ResponseEntity.badRequest().body("unsupported fixture".getBytes(StandardCharsets.UTF_8));
  return response.header("Content-Type",type).body(body);
 }
 @GetMapping("/api/diagnostics/scope/{side}/item") public Map<String,Object> scope(@PathVariable String side){return Map.of("side",side,"trainingOnly",true);}
}
