# 현재 상태와 할 일 (2026-11-27 기준)

## 버전
| | Play용 (`play`) | 개인용 (`personal`) |
|---|---|---|
| 앱 이름 / 패키지 | 투폰문자체커 / `com.happyfamily.twophone` | 투폰문자체커 개인용 / `com.happyfamily.twophone.personal` |
| 문자 발신 | 없음 (SEND_SMS 권한 없음) | 있음 |
| 서명 | Play 업로드 키 (`keystore.properties`) | 저장소 공용 키 `signing/app.keystore` |
| 빌드 | `./gradlew bundlePlayRelease` | `./gradlew assemblePersonalRelease` |
| 배포 파일 | `play-store/twophone-sms-checker-1.4.aab` | `apk/twophone-sms-checker-personal.apk` |

- 현재 버전: 1.4 (versionCode 5). 다음 Play 업로드는 versionCode 6 이상.
- 대상 SDK 36 (Android 16), 최소 SDK 26.
- 두 버전은 같은 연결 코드로 서로 연결됨 (문자 발신은 양쪽 모두 개인용일 때만).
- `apk/twophone-sms-checker-play-test.apk`: 시연 영상 촬영용 Play 버전 (공용 키 서명). 정식 출시 후엔 필요 없음.

## Play 업로드 키 (가장 중요)
- 저장소에 없음 (`release-keys/`, `keystore.properties`는 .gitignore). 사용자가 `upload.jks`와 비밀번호 파일을 따로 보관.
- 다음 빌드 때: `release-keys/upload.jks`에 키를 두고, 최상위에 `keystore.properties` 작성
  ```
  storeFile=release-keys/upload.jks
  storePassword=(보관한 비밀번호)
  keyAlias=upload
  keyPassword=(보관한 비밀번호)
  ```
- 업로드 키 SHA-256: `0E:2B:93:E8:D5:C2:BD:32:C8:3D:51:5E:64:19:F2:0B:80:B0:CF:EA:63:72:34:16:B1:3C:13:BF:72:3E:DE:78`
- 키를 완전히 잃으면 Play Console → 앱 서명에서 업로드 키 재설정 요청 가능 (Google Play 앱 서명 사용 중이므로).

## Play 출시 진행
- [x] AAB 1.4, 스토어 그래픽, 문구, 콘솔 답변 초안 준비 (`play-store/`)
- [x] 포그라운드 서비스 시연 영상 촬영 (Play 버전, 받는 폰)
- [ ] 유튜브 일부 공개 업로드 → 포그라운드 서비스 선언서에 링크
- [ ] 앱 콘텐츠 / 스토어 등록정보 작성 → 비공개 테스트 → (개인 계정이면 12명·14일) → 프로덕션
- [ ] 개인정보처리방침 URL은 현재 작업 브랜치 기준. main에 병합하면 main 주소로 바꾸기

## 출시 후 할 일
- [ ] **연결 코드 바꾸기**: 시연 영상에 현재 연결 코드가 노출됨. 심사 끝나면 모든 폰에서 연결 해제 → 새 코드로 다시 연결
- [ ] 영상에서 알림창에 내용이 안 보인 건 화면 녹화 중 알림 내용을 숨기는 폰 기능 때문 (다른 앱도 동일). 앱 문제 아님
- [ ] 기본 중계 서버 ntfy.sh는 하루 전송량 제한(IP당 약 250건)이 있음. 사용자가 많아지면 자체 ntfy 서버 고려

## 개발 환경 메모
- Android SDK: platform 36, build-tools 36.0.0 (cmdline-tools 최신판 필요 — 옛 판은 36을 못 찾음)
- AGP 8.13.2, Kotlin 2.0.21, Gradle 8.14.3
- 스토어 이미지·아이콘 재생성 방법: `tools/README.md`
