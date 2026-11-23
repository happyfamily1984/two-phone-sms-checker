# Play 스토어 출시 자료

이 폴더에 Play Console에 올릴 파일과 입력할 내용이 모두 들어 있습니다.

| 파일 | 용도 |
|---|---|
| `twophone-sms-checker-1.3.aab` | **앱 번들** — Play Console "프로덕션/테스트 → 새 버전 만들기"에 업로드 |
| `graphics/icon_512.png` | 앱 아이콘 (512×512) |
| `graphics/feature_graphic.png` | 그래픽 이미지 (1024×500) |
| `graphics/phone_1~5.png` | 휴대전화 스크린샷 (1080×1920) |
| [`listing.md`](listing.md) | 스토어 등록정보 문구 (앱 이름·간단한 설명·자세한 설명) |
| [`console-answers.md`](console-answers.md) | 앱 콘텐츠 설문 답변 (데이터 보안, 권한 선언, 콘텐츠 등급 등) |
| [`checklist.md`](checklist.md) | 출시 순서 체크리스트 |
| [`../PRIVACY.md`](../PRIVACY.md) | 개인정보처리방침 (URL을 Play Console에 입력) |

## 서명 키

- AAB는 **업로드 키**(`upload.jks`)로 서명되어 있습니다. 이 키는 저장소에 넣지 않았고 따로 전달했습니다.
- Play Console에서 **"Google Play 앱 서명"을 사용**(기본값)하면, 실제 배포 서명은 Google이 관리합니다.
- 업로드 키를 잃어버려도 Play Console에서 재설정을 요청할 수 있지만, 안전한 곳(클라우드·USB 두 곳 이상)에 백업해 두세요.
- 다음 버전을 빌드할 때는 저장소 최상위에 `keystore.properties`를 두면 `./gradlew bundleRelease`가 업로드 키로 서명합니다.

```
storeFile=release-keys/upload.jks
storePassword=(전달받은 비밀번호)
keyAlias=upload
keyPassword=(전달받은 비밀번호)
```

## 직접 설치(APK)와의 관계

Play에서 받은 앱은 Google 서명이라, 지금 링크로 직접 설치한 앱 위에 덮어 설치되지 않습니다.
Play 버전으로 옮길 때는 기존 앱을 **삭제한 뒤** Play에서 설치하고 연결 코드를 다시 입력하세요.
