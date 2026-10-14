# PokeLocation

App Android de **localização simulada** (fake GPS). Ele substitui a posição do aparelho por uma escolhida por você e
permite se mover com um **joystick flutuante**, seguir **rotas**, teleportar para **favoritos** e automatizar tudo por
**deep links** e **intents**.

Feito em Java puro, sem bibliotecas pesadas (sem Compose, RxJava, Firebase, Retrofit etc.), com foco em pouco uso de
RAM e bateria. O mapa usa OpenStreetMap (osmdroid), sem chave de API.

- Android 7.0+ (minSdk 24, targetSdk 36)
- Idiomas: português (Brasil) e inglês
- Tema claro e escuro, paleta azul (Material 3)

---

## Sumário

- [Instalação](#instalação)
- [Primeira execução](#primeira-execução)
- [Como usar](#como-usar)
  - [Tela principal](#tela-principal)
  - [Joystick flutuante](#joystick-flutuante)
  - [Rotas](#rotas)
  - [Favoritos](#favoritos)
  - [Marcadores](#marcadores)
  - [Busca e links externos](#busca-e-links-externos)
  - [Importar e exportar GPX/KML](#importar-e-exportar-gpxkml)
  - [Widget](#widget)
  - [Configurações](#configurações)
- [Automação](#automação)
- [Compilar o projeto](#compilar-o-projeto)
- [Estrutura do código](#estrutura-do-código)

---

## Instalação

**[⬇️ Baixar o APK mais recente](https://github.com/ayranoliveira1/pokelocation/releases/latest)** (também em
[`apks/`](apks/pokelocation-v1.0-release.apk)).

```bash
adb install -r apks/pokelocation-v1.0-release.apk
```

Ou copie o APK para o aparelho e abra pelo gerenciador de arquivos (permita "instalar apps desconhecidos").

## Primeira execução

Na primeira abertura, um **assistente de configuração** mostra só os passos que ainda faltam:

1. **Notificações:** o joystick roda como serviço em primeiro plano e precisa de notificação (Android 13+).
2. **Localização:** usada para ler sua posição real ("Obter localização atual").
3. **Sobreposição (exibir sobre outros apps):** necessária para o joystick flutuante.
4. **Opções do desenvolvedor:** ative em *Configurações → Sobre o telefone → toque 7× em "Número da versão"*.
5. **App de localização fictícia:** em *Opções do desenvolvedor → Selecionar app de local fictício → PokeLocation*.

Ao voltar das configurações do sistema, o passo é verificado sozinho. O assistente pode ser reaberto depois em
**Menu → Configuração e ajuda**, que mostra o status de cada item com um botão "Corrigir".

> No Android 13+, se o APK não veio da Play Store, o sistema pode bloquear a permissão de sobreposição
> ("configurações restritas"). O app mostra os passos para liberar e um comando ADB pronto para copiar.

Pelo ADB, tudo pode ser liberado de uma vez:

```bash
PKG=com.pokelocation.app
adb shell appops set $PKG android:mock_location allow
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
```

---

## Como usar

### Tela principal

A tela principal tem dois modos, alternados pelo ícone da toolbar (o modo escolhido fica salvo):

**Modo formulário:** escolha de onde o joystick começa e toque em **Iniciar**.

| Origem                       | O que faz                                                                    |
| ---------------------------- | ---------------------------------------------------------------------------- |
| Usar última localização      | Começa na última posição simulada.                                           |
| Obter localização atual      | Lê a posição real do GPS uma vez e começa nela.                              |
| Definir localização          | Digite ou cole `lat, lng` e a altitude. "Formatar" extrai a coordenada de um texto colado. |
| Definir rota                 | Escolhe uma rota salva, o **modo da rota** e o **modo de início** (veja [Rotas](#rotas)). |
| Gravar rota                  | Inicia o joystick já gravando os pontos por onde você passa.                 |

**Modo mapa:** toque no mapa para mover o marcador de início (a altitude é buscada sozinha) e toque em **Iniciar**.
Pelos botões do mapa dá para desenhar uma rota tocando ponto a ponto, gerar uma rota aleatória ou em círculo,
carregar, salvar ou limpar uma rota, e ligar "Mostrar marcadores" para ver favoritos e marcadores da área.

Outros itens da tela:

- **Iniciar / Parar:** sobe ou encerra o joystick. Ao parar, a última posição é salva e o GPS real volta.
- **Opções rápidas:** atalhos para ocultar o joystick/overlay e ver a tabela de **cooldown**.
- **Linha de distância:** `Distância · Cooldown · Decorrido` desde o último teleporte; fica verde quando o tempo de
  cooldown já passou.
- **Corrigir localização GPS:** modos alternativos de injeção para aparelhos em que a posição "pula".

### Joystick flutuante

Depois de **Iniciar**, o joystick aparece sobre qualquer app:

- **Direcional 360°:** para cima é norte. Quanto mais longe do centro, mais rápido.
- **Velocidades:** andar, trotar e correr (10 / 40 / 75 km/h por padrão, ajustáveis nas configurações).
- **Trava ("piloto automático"):** solte o dedo e continue andando na mesma direção.
- **Arrastar:** o joystick e a gaveta podem ser movidos; a posição fica salva.
- **Teleporte:** cole uma coordenada, escolha um favorito ou volte para a posição anterior. Com o *modo caminhada*,
  em vez de teleportar ele anda até o destino.
- **Salvar a posição** como favorito, ponto de uma rota ou marcador. **Compartilhar** envia `lat,lng`.
- **Mini-mapa flutuante:** abre um mapa sobre o app atual para escolher destino, ver ou editar a rota.
- **Controles de rota:** play/pausa, parar, próximo, anterior e progresso `i/n`.

A **notificação** do serviço também tem Parar, Ocultar/Mostrar, Compartilhar, Mapa, Salvar e, durante uma rota,
os controles dela.

### Rotas

Abra em **Menu → Rotas**.

- **Criar:** botão `+` → desenhe no mapa tocando ponto a ponto → Salvar (nome e pasta).
- **Editar:** no modo **Mapa** (arrastar, copiar ou apagar pontos) ou **Avançado** (lista de lat/lng/alt).
- **Organizar:** pastas aninhadas, busca, reordenar arrastando, copiar, renomear, mover e excluir (inclusive em lote).
- **Usar:** toque na rota. Ela é selecionada na tela principal e, se o mapa estiver aberto, aparece nele. Depois é
  só tocar em **Iniciar**.

Opções de reprodução (ficam salvas e valem para o formulário e para o mapa):

| Modo da rota      | Comportamento                              |
| ----------------- | ------------------------------------------ |
| Parar no fim      | Percorre uma vez e para no último ponto.   |
| Voltar ao início  | Repete em loop.                            |
| Inverter          | Vai e volta: A, B, C, D, C, B, A, B…       |

| Modo de início       | Comportamento                                   |
| -------------------- | ----------------------------------------------- |
| Teleportar ao início | Pula direto para o primeiro ponto.              |
| Andar até o início   | Caminha da posição atual até o primeiro ponto.  |

**Gravar uma rota:** escolha "Gravar rota" e toque em Iniciar. Ande com o joystick e use `+` para marcar pontos.
Ao final, salve com um nome (mínimo de 2 pontos) ou descarte.

### Favoritos

**Menu → Favoritos** lista os lugares salvos com a distância e o cooldown até cada um.

- Toque no favorito para usá-lo como posição na tela principal.
- Crie pelo `+` (escolhendo no mapa), pelo joystick ou depois de uma busca ("Adicionar aos favoritos").
- Pastas aninhadas, busca por nome ou endereço, edição, cópia, movimentação e exclusão em lote.

### Marcadores

**Menu → Marcadores** gerencia **tipos de marcador** (ex.: "Ponto de referência") e os pontos de cada tipo num
editor de mapa: toque para criar, mova, apague ou copie a coordenada. Os marcadores aparecem no mapa principal com
"Mostrar marcadores".

### Busca e links externos

- **Busca** (lupa na toolbar): digite um endereço ou lugar e escolha um dos resultados.
- **Links de mapas:** compartilhe para o PokeLocation um link de Google Maps, Apple Maps, OpenStreetMap, Yandex,
  Baidu, Amap, Here, QQ ou um `geo:`. A coordenada é extraída e preenchida. Se o joystick estiver rodando, ele
  teleporta na hora.
- **Arquivos `.gpx` / `.kml`** abertos de outro app vão direto para a importação.

### Importar e exportar GPX/KML

**Menu → Importar / Exportar GPX**

- **Importar:** GPX (waypoints, rotas, tracks) e KML (Point, LineString, MultiGeometry, pastas). Cada item pode
  virar favorito, marcador ou rota, um por um ou em lote, recriando a hierarquia de pastas.
- **Exportar:** favoritos, rotas e marcadores de um tipo, em GPX ou KML (`pokelocation_<data>.gpx/.kml`).

### Widget

Adicione pelo **Menu → Widget da tela inicial** (ou pelo launcher). O widget mostra se a simulação está ativa e uma
lista de favoritos e rotas: um toque teleporta ou inicia a rota, mesmo com o app fechado. Os itens, a ordem, o tema e
o agrupamento são escolhidos nas telas de configuração do widget.

### Configurações

**Menu → Configurações**

- **Geral:** idioma (troca na hora), tema, estilo do mapa, busca de altitude, linha de distância, bloquear capturas
  de tela, modo suspenso com suspensão automática, injeção indireta (só rede), reset de A-GPS, Modo Sistema,
  Modo Privacidade, salvar última localização, iniciar o joystick automaticamente ao escolher favorito ou rota, abrir
  outro app ao iniciar e fechar o PokeLocation em seguida.
- **JoyStick:** tipo (gaveta ou clássico), tamanho, opacidade e as três velocidades.
- **Backup:** exporta o banco (`pokelocation_<data>.db`) e restaura, reiniciando o app.
- **Avançado:** intervalos de processamento, atualização e repouso, offsets (rumo, velocidade, altitude) e faixas de
  precisão reportada parado e em movimento.

---

## Automação

Tudo o que o joystick faz pode ser disparado de fora: por **deep link**, **intent** ou **ADB**. Se o joystick
estiver parado, ele é iniciado e a ação é executada em seguida.

### Deep links `pokelocation://`

| Link                                                       | Ação                                   |
| ---------------------------------------------------------- | -------------------------------------- |
| `pokelocation://teleport?lat=-23.5505&lng=-46.6333&alt=760` | Teleporta.                             |
| `pokelocation://walk?waypoints=lat,lng;lat,lng&teleport=false&speed=10` | Caminha pelos pontos (`lat,lng[,alt]` separados por `;`). |
| `pokelocation://route?name=Ibira`                          | Inicia a rota salva com esse nome.     |
| `pokelocation://route-pause` · `route-resume` · `route-next` · `route-prev` | Controla a rota.       |
| `pokelocation://speed?speed=40`                            | Muda a velocidade (km/h).              |
| `pokelocation://favorites?name=Casa`                       | Teleporta para o favorito.             |
| `pokelocation://record-start` · `record-add` · `record-stop?name=Nova` | Grava uma rota.             |
| `pokelocation://generate?mode=0&marker_count=10&offset=50` | Gera uma rota (`mode` 0 = aleatória, 1 = círculo). |
| `pokelocation://hide` · `show` · `stop`                    | Oculta, mostra ou para o joystick.     |

```bash
adb shell am start -a android.intent.action.VIEW -d "pokelocation://teleport?lat=-23.5505&lng=-46.6333"
```

### Intents e broadcasts

As mesmas ações existem como `pokelocation.action.<NOME>` (TELEPORT, ROUTE, WALK, STOP, ROUTE_PAUSE, ROUTE_RESUME,
ROUTE_NEXT, ROUTE_PREV, HIDE, SHOW, SPEED, FAVORITES, RECORD_START, RECORD_ADD, RECORD_STOP, GENERATE, STATUS),
enviadas ao `OverlayService`. Os extras usam os mesmos nomes dos parâmetros dos deep links (`lat`, `lng`, `alt`,
`name`, `speed`…).

```bash
adb shell am start-foreground-service -n com.pokelocation.app/.overlay.OverlayService \
  -a pokelocation.action.TELEPORT --ed lat -23.5505 --ed lng -46.6333
```

O app responde com broadcasts: `STATUS_RESPONSE` (posição, velocidade, rumo, estado da rota e da gravação),
`ERROR` (`source_action`, `error`, `message`) e `RECORD_SAVED` (`name`, `point_count`).

---

## Compilar o projeto

Requisitos: Android SDK 36 e JDK (o caminho usado pelo Gradle fica em `gradle.properties`, em `org.gradle.java.home`;
ajuste para o JDK da sua máquina).

```bash
./gradlew assembleDebug        # APK de debug
./gradlew installDebug         # compila e instala no aparelho conectado
./gradlew assembleRelease      # release com R8 e shrink de recursos
./gradlew assembleQa           # igual ao release, assinado com a chave debug (para testes de RAM)
./gradlew testDebugUnitTest    # testes unitários (JVM)
```

`tools/gbuild.sh <tarefas>` roda o Gradle com uma trava, para vários builds em paralelo não se atropelarem.

**Assinatura do release:** as credenciais ficam fora do repositório, em `~/.gradle/gradle.properties`:

```properties
POKELOCATION_STORE_FILE=/caminho/para/keystore.jks
POKELOCATION_STORE_PASSWORD=...
POKELOCATION_KEY_ALIAS=...
POKELOCATION_KEY_PASSWORD=...
```

Sem elas, o release é gerado sem assinatura.

> `assembleDebug` só gera o APK, não instala. Para testar uma mudança no aparelho, use `installDebug`.
> Debug e release têm assinaturas diferentes: trocar de um para o outro exige desinstalar (e apaga os dados do app).

---

## Estrutura do código

Pacote base: `app/src/main/java/com/pokelocation/app/`

| Pacote          | Conteúdo                                                                                   |
| --------------- | ------------------------------------------------------------------------------------------ |
| `engine/`       | Motor de simulação: injeção nos providers (`LocationInjector`), movimento, reprodução de rotas. |
| `overlay/`      | Serviço em primeiro plano (processo `:overlay`), joystick, notificação.                    |
| `mapoverlay/`   | Mini-mapa flutuante (processo `:map`, encerrado ao fechar).                                |
| `ui/main/`      | Tela principal: formulário, modo mapa, checagens antes de iniciar, localização atual.      |
| `ui/map/`       | `RouteMapView`, o mapa reutilizado na principal, na edição de rotas e na importação.       |
| `ui/routes/`, `ui/favorites/`, `ui/markers/`, `ui/search/`, `ui/gpx/`, `ui/settings/`, `ui/setup/` | Telas de cada funcionalidade. |
| `ui/base/`, `ui/panel/`, `ui/common/` | Activity base, painéis/diálogos e componentes compartilhados.        |
| `data/`         | SQLite (`Db`), DAOs de favoritos, rotas, pastas e marcadores, e `Prefs`.                   |
| `model/`        | Objetos de dados (`Coord`, `Route`, `Favorite`, `Folder`, `Marker`…).                      |
| `gpx/`          | Leitura e escrita de GPX/KML em streaming.                                                 |
| `geo/`, `net/`  | Cálculos geográficos, parser de coordenadas, cooldown e busca de elevação.                 |
| `ipc/`          | Intents públicas, broadcasts e comunicação entre os processos.                             |
| `widget/`       | Widget de ações rápidas.                                                                   |

O joystick e o mini-mapa rodam em processos separados (`:overlay` e `:map`) para que a tela principal possa ser
fechada sem interromper a simulação e para manter o consumo de memória baixo.

---

Use com responsabilidade: simular localização pode violar os termos de uso de alguns apps e jogos.

## Licença

Distribuído sob a [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0-only), exigida pela
[libsignal](https://github.com/signalapp/libsignal) usada no chat com criptografia ponta a ponta. Quem distribuir uma
versão modificada do app, ou oferecê-la por rede, precisa disponibilizar o código-fonte sob a mesma licença.
As versões até a 1.0 foram publicadas sob a licença MIT.
