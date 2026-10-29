# StarLight Ktor

다른 StarLight 플러그인이 함께 쓰는 [Ktor](https://ktor.io) 런타임(CIO 서버)입니다. 이 플러그인 자체는 아무 일도 하지 않습니다.
Ktor가 쓰는 SLF4J 로그를 StarLight 로거(`Ktor/<이름>` 태그)로 보내는 provider도 함께 들어 있습니다.

## 사용하는 플러그인에서

StarLight는 플러그인에 없는 클래스를 다른 플러그인의 클래스 로더에서 찾습니다. 그래서 Ktor를 직접 포함하지 않고 이 플러그인에 의존하면 됩니다.

`starlight.json`:

```json
"dependency": ["ktor@3.6.0"]
```

`app/build.gradle`:

```groovy
compileOnly "io.ktor:ktor-server-cio:3.6.0"
testImplementation "io.ktor:ktor-server-cio:3.6.0"
```

- Ktor를 `implementation`으로 직접 포함하지 마세요. 자기 dex의 클래스가 먼저 로드되므로,
  일부 클래스만 이 플러그인 것을 쓰게 되면 같은 이름의 클래스가 두 로더에 생겨 `LinkageError`/`ClassCastException`이 날 수 있습니다.
- SLF4J provider는 이 플러그인에 있으므로 사용하는 플러그인에 `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`를 두지 않습니다.
  단위 테스트에서는 `slf4j.provider` 시스템 속성을 `org.slf4j.helpers.NOP_FallbackServiceProvider`로 지정하세요.

## 버전

플러그인 버전의 major.minor는 포함된 Ktor 버전과 같습니다. Ktor를 올리면 이 플러그인의 `versionName`/`starlight.json`의 `version`과,
의존하는 플러그인들의 `compileOnly` 버전과 `dependency`를 함께 올리세요.

patch는 Ktor와 다를 수 있습니다. 플러그인만 바뀐 릴리즈(3.6.1: 메타데이터에 `category` 추가)는 같은 Ktor(3.6.0)를 담은 채 patch만 올립니다.
StarLight는 의존성 버전의 major.minor만 비교하므로 `ktor@3.6.0`에 의존하는 플러그인은 그대로 동작합니다.

## 빌드

```sh
./gradlew :app:assembleReleaseSlp   # app/build/outputs/starlight/release/ktor-release.slp
./gradlew :app:deployReleaseSlp -Pstarlight.device=<serial>
```
