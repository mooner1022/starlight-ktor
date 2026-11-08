# StarLight Ktor

다른 StarLight 플러그인이 함께 쓰는 [Ktor](https://ktor.io) 런타임(CIO 서버)입니다.
- Ktor 클래스를 담고 있어, 플러그인마다 Ktor를 따로 포함하지 않아도 됩니다.
- 서버 시작을 맡는 `KtorServers`가 있습니다. 앱 크래시 방지, 플러그인 사이의 포트 충돌 확인, 재시작 직후의 포트 대기를 여기서 한 번에 처리합니다.
- Ktor가 쓰는 SLF4J 로그를 StarLight 로거(`Ktor/<이름>` 태그)로 보내는 provider가 들어 있습니다.

## 사용하는 플러그인에서

StarLight는 플러그인에 없는 클래스를 다른 플러그인의 클래스 로더에서 찾습니다. 그래서 Ktor를 직접 포함하지 않고 이 플러그인에 의존하면 됩니다.

`starlight.json`:

```json
"dependency": ["ktor@3.6.0"]
```

`app/build.gradle`(API jar는 이 저장소의 `./gradlew :app:apiJar` 결과물 `app/build/outputs/api/ktor-api-<버전>.jar`):

```groovy
compileOnly "io.ktor:ktor-server-cio:3.6.0"
compileOnly files("libs/ktor-api-3.6.2.jar")
testImplementation "io.ktor:ktor-server-cio:3.6.0"
testImplementation files("libs/ktor-api-3.6.2.jar")
```

- Ktor를 `implementation`으로 직접 포함하지 마세요. 자기 dex의 클래스가 먼저 로드되므로,
  일부 클래스만 이 플러그인 것을 쓰게 되면 같은 이름의 클래스가 두 로더에 생겨 `LinkageError`/`ClassCastException`이 날 수 있습니다.
- SLF4J provider는 이 플러그인에 있으므로 사용하는 플러그인에 `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`를 두지 않습니다.
  단위 테스트에서는 `slf4j.provider` 시스템 속성을 `org.slf4j.helpers.NOP_FallbackServiceProvider`로 지정하세요.

### 서버 시작

`embeddedServer`를 직접 쓰지 말고 `KtorServers.start`로 서버를 엽니다. 호출은 메인 스레드가 아닌 곳에서 하세요.
포트가 사용 중이면 재시도하느라 최대 약 2초 동안 멈출 수 있습니다.

```kotlin
val server = KtorServers.start(owner = info.name, host = host, port = port, onError = { e -> LOG.error { "$e" } }) {
    intercept(ApplicationCallPipeline.Call) {
        handler.handle(call)
        finish()
    }
}
// ...
server.stop()
```

`start`가 던지는 예외:
- `PortInUseException`(`BindException` 하위 클래스): 이 앱 안의 다른 서버가 그 포트를 쓰고 있습니다. `owner`로 어느 플러그인인지 알 수 있고, 재시도하지 않고 바로 실패합니다.
- `BindException`: 다른 프로세스가 그 포트를 쓰고 있고, 재시도해도 비지 않았습니다.
- 그 밖의 실제 원인. Ktor가 원인을 감싸서 던지는 `JobCancellationException`은 벗겨서 던집니다.

`onError`는 서버가 도는 중에 생긴 오류를 받습니다. 클라이언트가 연결을 끊어서 생기는 I/O 오류는 넘기지 않습니다.

### API 레벨

이 플러그인의 major.minor는 Ktor를 따르므로, API는 patch 릴리즈로 추가됩니다. 그런데 StarLight는 의존성의 major.minor만 확인합니다.
그래서 사용하는 플러그인은 `onEnable`에서 설치된 API 레벨을 직접 확인해야 합니다.
`KtorServers`가 처음 들어간 3.6.2보다 오래된 Ktor 플러그인에는 클래스 자체가 없습니다. 이때 `apiLevel`을 읽으면 `NoClassDefFoundError`가 나므로, 반드시 `LinkageError`를 잡으세요.

```kotlin
val ktorApiLevel = try { KtorServers.apiLevel } catch (e: LinkageError) { 0 }
if (ktorApiLevel < KtorServers.API_LEVEL) { /* "Ktor 플러그인을 업데이트하세요" 알림 후 서버를 열지 않음 */ }
```

API를 바꿀 때는 이미 설치된 플러그인이 계속 동작하도록 기존 시그니처를 유지합니다. 기능은 오버로드나 새 멤버로 추가하고, 그때마다 `API_LEVEL`을 올리세요.

### 중앙에서 맡는 일과 맡지 않는 일

StarLight에서 서버를 여는 플러그인은 모두 한 프로세스 안에서, 이 플러그인의 클래스 로더를 함께 씁니다. 이 점을 이용해 다음 일을 맡습니다.

| 상황 | 처리 |
|---|---|
| 바인드 실패가 Ktor의 독립 코루틴에서 처리되지 않은 예외가 되어 앱이 종료됨 | 서버 코루틴마다 예외 처리기를 붙입니다. 시작할 때의 실패는 `start`가 던집니다. |
| 두 플러그인에 같은 포트를 설정함 | 프로세스 안의 포트 예약표에서 바로 확인해 `PortInUseException`을 던집니다(0.0.0.0은 모든 주소와 겹치는 것으로 봅니다). |
| 재시작 직후 이전 프로세스가 아직 포트를 잡고 있음 | `BindException`이면 500ms 간격으로 3번 다시 시도합니다. 재시작은 ProcessPhoenix(`Runtime.exit`)나 크래시 처리(`exitProcess`)로 일어나는데, 이전 프로세스가 끝나기 전에 새 프로세스가 뜰 수 있습니다. |
| 이전 프로세스의 연결이 TIME_WAIT로 남아 있음 | `reuseAddress`를 켭니다. 실제로 대기 중인 소켓과 포트를 나눠 쓰게 되지는 않습니다. |

다음 일은 맡지 않습니다.
- **종료할 때 서버를 정리하는 일.** 재시작 경로는 `Session.shutdown()`을 부르지 않습니다. 크래시 경로는 부르기는 하지만, 플러그인의 비동기 `onDisable`이 끝나기 전에 프로세스가 종료됩니다.
  그래서 종료 훅에서 소켓을 닫더라도, 프로세스가 끝날 때 커널이 하는 일과 다르지 않습니다. 남는 시간 차는 재시도가 메웁니다.

## 버전

플러그인 버전의 major.minor는 포함된 Ktor 버전과 같습니다. Ktor를 올리면 이 플러그인의 `pluginVersion`(`app/build.gradle`)/`starlight.json`의 `version`과,
의존하는 플러그인들의 `compileOnly` 버전과 `dependency`를 함께 올리세요.

patch는 Ktor와 다를 수 있습니다. 플러그인만 바뀐 릴리즈는 같은 Ktor(3.6.0)를 담은 채 patch만 올립니다(3.6.1: 메타데이터에 `category` 추가, 3.6.2: `KtorServers`).
StarLight는 의존성 버전의 major.minor만 비교하므로 `ktor@3.6.0`에 의존하는 플러그인은 그대로 동작합니다.

## 빌드

```sh
./gradlew :app:assembleReleaseSlp   # app/build/outputs/starlight/release/ktor-release.slp
./gradlew :app:apiJar               # app/build/outputs/api/ktor-api-3.6.2.jar
./gradlew :app:testReleaseUnitTest
./gradlew :app:deployReleaseSlp -Pstarlight.device=<serial>
```
