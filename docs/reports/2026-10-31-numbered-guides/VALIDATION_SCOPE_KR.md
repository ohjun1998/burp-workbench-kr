# 검증 시점 구분

전체6964개 검사(155개 Java그룹6652+payloadHTTP136+Mapper147+Node29)를 통과했습니다. 이후 최종 실제 창 검사에서 가이드 내부 화면 선택 시 창 제목이 이전 도구로 남는 표시 오류를 발견했습니다.

창 제목 동기화와 이후 실제 어두운 테마 검사에서 발견한 설명 글자색만 수정했고, 이미지/선택/저장/전송/기존도구 로직은 변경하지 않았습니다. 이 후속 수정은 SimpleVisualGuideTest·FeatureGuideWorkflowTest·SimpleScreenCleanupTest 집중 검사와 실제 창의 제목 일치 검증으로 확인합니다. 최종 native/capture/Katana 결과는 최종 JAR 체크섬에 연결합니다. 전체 엔진 검사와 창 제목·어두운 설명 글자색 후속 검증의 시점을 구분합니다.
