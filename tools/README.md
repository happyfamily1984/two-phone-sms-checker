# 디자인·스토어 이미지 도구

## icon-source/
- `original_icon.png`: 처음 받은 앱 아이콘 그림
- `mockup_receiver.png`, `mockup_compose.png`: 처음 받은 디자인 시안
- `full_bleed.png`: 아이콘의 검은 모서리를 채워 정사각형으로 만든 것 (Play 512 아이콘의 원본)
- `adaptive_1080.png`: 적응형 아이콘 전경 (108dp 캔버스에 여백 포함). `app/src/main/res/mipmap-*/ic_launcher_foreground.webp`는 이걸 108/162/216/324/432px로 줄인 것

## store-graphics/ — Play 스크린샷 다시 만들기
1. **화면 렌더링 (Paparazzi)**: 에뮬레이터 없이 실제 레이아웃을 그림으로 그린다.
   - 임시 사본에서 하는 것을 권장 (빌드 플러그인 버전을 올려야 함)
   - 루트 `build.gradle.kts`에 `id("app.cash.paparazzi") version "2.0.0-alpha05" apply false`, Kotlin 플러그인을 `2.3.0`으로
   - `app/build.gradle.kts`에 `id("app.cash.paparazzi")` 추가, `kotlinOptions` 대신 `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }`
   - `ScreensTest.kt.txt`를 `app/src/test/java/com/happyfamily/twophone/ScreensTest.kt`로 복사
   - `./gradlew recordPaparazziPlayRelease --tests '*ScreensTest*'`
   - 결과(`app/src/test/snapshots/images/*_a.png`, `*_b.png`)는 화면 위·아래 절반. 세로로 이어 붙이면 900×2000 화면 이미지
2. **스토어 이미지 합성**: 이어 붙인 화면을 `screens/1_receiver.png` 처럼 이름 붙여 `store.html` 옆 `screens/` 폴더에 두고,
   Play 아이콘도 `screens/play_icon_512.png`로 둔 뒤 `node shoot.js <store.html이 있는 폴더 절대경로>` (Playwright 필요)
   → `out/phone_1~5.png` (1080×1920), `out/feature_graphic.png` (1024×500)
