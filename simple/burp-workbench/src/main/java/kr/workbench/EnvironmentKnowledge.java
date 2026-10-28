package kr.workbench;
import java.util.*;
/** Independently curated path facts. References explain relevance, not vulnerability. */
final class EnvironmentKnowledge {
 record Entry(String technology,String path,String reference,String purpose){}
 static final String SECLISTS="https://github.com/danielmiessler/SecLists/tree/master/Discovery/Web-Content";
 static final String ASSETNOTE="https://github.com/assetnote/wordlists";
 static final String SPRING="https://docs.spring.io/spring-boot/reference/actuator/endpoints.html";
 static final String ACTUATOR_CASE="https://www.wiz.io/blog/spring-boot-actuator-misconfigurations";
 static final String SWAGGER="https://swagger.io/docs/specification/v3_0/api-host-and-base-path/";
 static final List<Entry> ENTRIES=new ArrayList<>();
 static void paths(String tech,String ref,String purpose,String... paths){for(String p:paths)ENTRIES.add(new Entry(tech,p,ref,purpose));}
 static {
  paths("Spring Boot / Actuator",SPRING,"운영·내부 설정 노출 검토","/actuator/startup","/actuator/sbom","/actuator/integrationgraph");
  paths("Swagger / OpenAPI",SECLISTS,"API 명세 노출·인증 요구 검토","/api/swagger.json","/api/openapi.json","/api/swagger.yaml","/api-docs/swagger.json","/api/spec/swagger.json","/swagger/v2/swagger.json","/swagger-resources","/swagger-resources/configuration/ui");
  paths("PHP",SECLISTS,"배포 파일·설정 정보 노출 검토","/composer.json","/composer.lock","/.env","/.git/HEAD");
  paths("Laravel",SECLISTS,"배포·디버그 정보 노출 검토","/.env","/composer.json","/composer.lock","/horizon","/horizon/api/stats","/vendor/composer/installed.json");
  paths("Express",SECLISTS,"Node 배포 정보 노출 검토","/package.json","/package-lock.json","/.env","/.git/HEAD");
  paths("Next.js",SECLISTS,"빌드·배포 정보 노출 검토","/package.json","/.git/HEAD");
  paths("Tomcat","https://tomcat.apache.org/tomcat-10.1-doc/manager-howto.html","Java 배포·관리 정보 노출 검토","/WEB-INF/web.xml","/META-INF/MANIFEST.MF");
  paths("FastAPI","https://fastapi.tiangolo.com/tutorial/first-steps/","API 명세·인증 요구 검토","/docs","/redoc","/openapi.json");
  paths("Symfony","https://symfony.com/doc/current/profiler.html","프로파일러·배포 정보 노출 검토","/_profiler/","/_wdt/","/composer.json","/.env");
  paths("Ruby on Rails","https://guides.rubyonrails.org/configuring.html","상태·개발 정보 노출 검토","/up","/rails/info/routes","/rails/info/properties");
  paths("Adobe Experience Manager",ASSETNOTE,"콘텐츠·인증 요구 검토","/system/console/","/crx/de/","/crx/packmgr/index.jsp","/libs/granite/core/content/login.html","/content.json");
 }
 static boolean manualOnly(String url){return url.matches("(?i).*(?:/(?:heapdump|threaddump|shutdown|restart)|\\.(?:hprof|zip|tar|gz|sql))(?:$|[?#/]).*");}
}
