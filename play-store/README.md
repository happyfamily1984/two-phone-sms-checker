# Play 스토어 출시 자료

이 폴더에 Play Console에 올릴 파일과 입력할 내용이 모두 들어 있습니다.

| 파일 | 용도 |
|---|---|
| `twophone-sms-checker-1.4.aab` | **앱 번들** (문자 발신 기능 없는 Play 버전) — Play Console "프로덕션/테스트 → 새 버전 만들기"에 업로드 |
| `graphics/icon_512.png` | 앱 아이콘 (512×512) |
| `graphics/feature_graphic.png` | 그래픽 이미지 (1024×500) |
| `graphics/phone_1~5.png` | 휴대전화 스크린샷 (1080×1920, Play 버전 화면) |
| [`listing.md`](listing.md) | 스토어 등록정보 문구 (앱 이름·간단한 설명·자세한 설명) |
| [`console-answers.md`](console-answers.md) | 앱 콘텐츠 설문 답변 (데이터 보안, 권한 선언, 콘텐츠 등급 등) |
| [`checklist.md`](checklist.md) | 출시 순서 체크리스트 |
| [`../PRIVACY.md`](../PRIVACY.md) | 개인정보처리방침 (URL을 Play Console에 입력) |

## 서명 키

- AAB는 **업로드 키**(`upload.jks`)로 서명되어 있습니다. 이 키는 저장소에 넣지 않았고 따로 전달했습니다.
- Play Console에서 **"Google Play 앱 서명"을 사용**(기본값)하면, 실제 배포 서명은 Google이 관리합니다.
- 업로드 키를 잃어버려도 Play Console에서 재설정을 요청할 수 있지만, 안전한 곳(클라우드·USB 두 곳 이상)에 백업해 두세요.
- 다음 버전을 빌드할 때는 저장소 최상위에 `keystore.properties`를 두면 `./gradlew bundlePlayRelease`가 업로드 키로 서명합니다.

```
storeFile=release-keys/upload.jks
storePassword=(전달받은 비밀번호)
keyAlias=upload
keyPassword=(전달받은 비밀번호)
```

## Play 버전과 개인용 버전

| | Play 버전 | 개인용 버전 |
|---|---|---|
| 앱 이름 | 투폰문자체커 | 투폰문자체커 개인용 |
| 패키지 | `com.happyfamily.twophone` | `com.happyfamily.twophone.personal` |
| 문자 발신 | 없음 (SMS 권한 없음) | 있음 |
| 설치 | Play 스토어 | 저장소의 APK 직접 설치 |

두 버전은 서로 다른 앱이라 한 폰에 같이 설치할 수 있고, 같은 연결 코드로 서로 연결됩니다.
(문자 발신은 받는 폰·보내는 폰 모두 개인용일 때만 됩니다.)
