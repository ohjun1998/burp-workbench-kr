FROM tomcat:10.1.60-jdk17-temurin
RUN rm -rf /usr/local/tomcat/webapps/*
COPY dist/ROOT.war /usr/local/tomcat/webapps/ROOT.war
COPY infra/HealthCheck.java /opt/HealthCheck.java
EXPOSE 8080
