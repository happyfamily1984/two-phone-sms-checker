# 출시 체크리스트

## 준비
- [ ] Google Play 개발자 계정 (등록비 25달러, 본인 인증 완료)
- [ ] 업로드 키 `upload.jks`와 비밀번호를 안전한 곳 두 군데 이상에 백업
- [ ] 개인정보처리방침 URL이 열리는지 확인 (`console-answers.md` 1번)
- [ ] SMS 기능 시연 동영상을 유튜브(일부 공개)에 업로드

## Play Console
1. [ ] **앱 만들기**: 앱 이름 "투폰문자체커", 기본 언어 한국어, 앱, 무료
2. [ ] **앱 콘텐츠** 항목 모두 작성 → `console-answers.md`
3. [ ] **기본 스토어 등록정보**: 문구 → `listing.md`, 그래픽 → `graphics/` 폴더
4. [ ] **앱 서명**: Google Play 앱 서명 사용 (기본값 그대로)
5. [ ] **테스트 → 비공개 테스트**: 트랙 만들기 → `twophone-sms-checker-1.3.aab` 업로드 → 테스터 이메일 추가
6. [ ] 권한 선언(SMS·포그라운드 서비스) 양식 제출
7. [ ] 검토를 위해 보내기

## 개인 개발자 계정이라면 (2023년 11월 이후 생성)
- 프로덕션 출시 전에 **테스터 12명 이상이 14일 이상 비공개 테스트**에 참여해야 프로덕션 신청 버튼이 열립니다.
- 14일이 지나면 **프로덕션 → 프로덕션 액세스 신청** → 질문에 답한 뒤 같은 AAB로 프로덕션 출시

## 다음 버전을 낼 때
- `app/build.gradle.kts`의 `versionCode`를 1 올리고 `versionName`을 바꾼 뒤
- `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` 업로드
