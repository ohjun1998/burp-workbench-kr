# 환경 탐색 추천 경로의 근거

관찰된 응답을 우선하고, 환경별 경로를 독립적으로 선별했습니다. 아래 목록 전체를 배포 파일에 포함하거나 취약점 여부를 단정하지 않습니다.

- SecLists Web-Content: https://github.com/danielmiessler/SecLists/tree/master/Discovery/Web-Content
- Assetnote 기술별 워드리스트: https://wordlists.assetnote.io/
- Spring Boot Actuator: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html
- Wiz의 공개 Actuator 노출 연구: https://www.wiz.io/blog/spring-boot-actuator-misconfigurations
- OpenAPI 서버 / 경로: https://swagger.io/docs/specification/v3_0/api-host-and-base-path/
- FastAPI 문서: https://fastapi.tiangolo.com/tutorial/first-steps/
- Symfony Profiler: https://symfony.com/doc/current/profiler.html
- Rails 설정: https://guides.rubyonrails.org/configuring.html

직접 관찰한 robots.txt, sitemap, OpenAPI GET 경로, Actuator 링크, Swagger 문서 위치를 추천에 반영합니다. 다른 출처, 템플릿 변수, POST 전용 경로는 자동 후보에서 제외합니다. 사용자 워드리스트는 1 MiB까지 가져올 수 있습니다. 후보는 최대 300개 표시합니다. 점수는 내부 정렬 기준이며 취약점 확률이 아닙니다.
