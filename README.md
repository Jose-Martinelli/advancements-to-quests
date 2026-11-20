# Advancements to Quests (`advquests`)

<p align="center">
  <img src="icon.png" alt="Advancements to Quests" width="128" height="128">
</p>

Mod para **Minecraft Forge 1.20.1** que converte todos os advancements registrados no modpack (vanilla e de mods) em capítulos do **FTB Quests**, para acompanhar o progresso pela interface do FTB Quests.

| | |
|---|---|
| Mod ID | `advquests` |
| Versão | `1.0.0` |
| Loader | Forge 47.x (`1.20.1`) |
| Dependência obrigatória | FTB Quests `2001.4+` |
| Licença | All Rights Reserved (`gradle.properties` / `mods.toml`) |

## Pré-requisitos

**Para jogar / servidor**

- Minecraft **1.20.1**
- Forge **47.x**
- [FTB Quests](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-forge) (e dependências usuais: FTB Library, FTB Teams, Architectury)

**Para desenvolver / build**

- JDK **17**
- Git + Gradle Wrapper (`./gradlew`; não precisa instalar Gradle globalmente)

## Estrutura do projeto

```
advancements-to-quests/
├── icon.png                     # Ícone do repositório / README
├── build.gradle                 # Build ForgeGradle + deps runtime do FTB
├── gradle.properties            # Versões MC/Forge/mod e FTB
├── settings.gradle
├── src/main/java/net/advquests/
│   ├── AdvQuests.java           # Entrada do mod, eventos e comando /advquests
│   ├── client/                  # Redirecionamento da tela de advancements
│   ├── config/                  # Leitura de config/advquests.json
│   ├── generator/               # Geração de capítulos, tasks e recompensas
│   ├── snbt/                    # Leitura/escrita SNBT do FTB Quests
│   └── sync/                    # Sincronização advancement ↔ quest de item
└── src/main/resources/
    ├── icon.png                 # Logo no menu de mods (mods.toml logoFile)
    ├── META-INF/mods.toml
    ├── advquests_default_config.json
    └── assets/advquests/lang/   # en_us, pt_br
```

Não há API HTTP nem variáveis de ambiente: a configuração é o arquivo JSON abaixo.

## Instalação (jogador / servidor)

1. Coloque o jar em `mods/` (cliente e/ou servidor).
2. Garanta que o FTB Quests esteja instalado no mesmo lado em que o mod deve gerar/sincronizar quests.
3. Na primeira execução do servidor, o mod cria `config/advquests.json` e gera `config/ftbquests/quests/` antes do FTB Quests carregar.
4. Opcional no cliente: com `replace_advancements_screen`, o botão/tecla de advancements abre o FTB Quests (vale a config do **cliente** em servidor dedicado).

## Desenvolvimento

```bash
# Dependências e runs de desenvolvimento (Forge MDK)
./gradlew genIntellijRuns   # ou genEclipseRuns

# Cliente / servidor de teste (FTB entra como runtimeOnly)
./gradlew runClient
./gradlew runServer

# Artefato de produção
./gradlew build
# → build/libs/advquests-1.0.0.jar
```

O mod **não compila contra o FTB Quests**: só gera arquivos `.snbt`. As deps FTB no `build.gradle` são `runtimeOnly`, para testar com `runClient` / `runServer`.

Formato SNBT alinhado ao FTB Quests **2001.4.22** (1.20.1).

## Como funciona

- No `ServerStartingEvent`, o mod lê os advancements e escreve em `config/ftbquests/quests/` **antes** do FTB Quests carregar (ele lê no `ServerStartedEvent`).
- **Cada aba** de advancements vira um **capítulo** (título, descrição e ícone do advancement raiz).
- **Cada advancement com display** vira uma **quest** (task `advancement`, ou tasks de item quando aplicável).
  - Títulos/descrições em JSON traduzível (idioma do jogador).
  - Posição e dependências seguem a árvore original.
  - Formato/tamanho por tipo (task, goal, challenge).
  - Ocultos: só aparecem depois de completados (configurável).
- **IDs determinísticos** (hash do ID do advancement + tag `advquests`): regenerar não apaga progresso.
- **Conteúdo manual do FTB Quests não é tocado** (só entradas com a tag do mod).
- Capítulo de mod removido do pack é apagado automaticamente.

## Configuração: `config/advquests.json`

Criado na primeira execução; aceita comentários `//` e `/* */` (JSON leniente do Gson). Modelo embutido: `src/main/resources/advquests_default_config.json`.

Não usa variáveis de ambiente nem `.env` — só este JSON em disco.

O mais importante são os **grupos**: cada aba entra no primeiro grupo cujo `match` casar com o ID do advancement raiz.

```json
"groups": [
  { "id": "magic", "title": "Magic", "icon": "minecraft:enchanted_book",
    "match": ["ars_nouveau", "botania", "irons_spellbooks"] }
]
```

Padrões aceitos (`match` e `exclude`):

- `"botania"` — namespace inteiro (`botania:*`)
- `"minecraft:story/*"` — curinga (`*` / `?`)
- `"create:root"` — aba/advancement específico

Abas sem match vão para `default_group` (`null` = sem grupo).

| Opção | Efeito resumido |
|---|---|
| `enabled` / `regenerate_on_start` | Liga o mod / regenera ao subir o servidor |
| `keep_manual_positions` | Preserva posições editadas no editor FTB |
| `replace_advancements_screen` | Cliente: botão/tecla abrem o FTB Quests |
| `vanilla_screen_with_shift` | Cliente: Shift abre a tela vanilla |
| `progression_mode` | `flexible` / `linear` / `default` |
| `show_rewards` / `max_reward_items` | Exibe recompensas do advancement (sem duplicar entrega) |
| `item_display` / `max_item_tasks` | Tasks de item vs task de advancement |
| `hidden_advancements` | `invisible` / `visible` / `skip` |
| `spacing_x` / `spacing_y` | Escala das posições da árvore de advancements |
| `shapes` / `sizes` | Formato e tamanho por frame (`task` / `goal` / `challenge`) |
| `exclude` | Globs de advancements/abas ignorados |
| `groups` / `default_group` | Organização dos capítulos |

## Recompensas

Com `"show_rewards": true`, a quest mostra XP, itens de loot table e receitas desbloqueadas.

**Não há duplicação de prêmio:** recompensas usam tipo `custom` + `no_toast` (só exibição). Quem entrega o prêmio continua sendo o advancement vanilla.

Loot aleatório lista itens possíveis até `max_reward_items`. Recompensas `function` (comandos) não são listadas.

## Quests de obter/craftar item

Com `"item_display": "requirements"` (padrão), critérios de item viram **tasks de item** do FTB Quests (não consomem; clique abre JEI/REI/EMI).

| Critério | Tasks |
|---|---|
| Um item | 1 obrigatória |
| Todos de vários | 1 obrigatória por item |
| Um entre vários / tags | 1 **opcional** por item (qualquer uma conclui) |
| `recipe_crafted` | Item resultante + `only_from_crafting` |
| `count` | Quantidade da task |

Quando o jogador ganha o advancement, o mod conclui a quest (`/ftbquests change_progress`). Progresso já sincronizado fica em `<mundo>/advquests_synced.json`.

Mantém task de advancement quando não dá para representar com itens (NBT, durabilidade, encantamento, comer/usar, matar/explorar, misturas ALL/ANY, acima de `max_item_tasks`). Com `"item_display": "off"`, tudo usa task de advancement.

## Comandos (permissão ≥ 2)

| Comando | Efeito |
|---|---|
| `/advquests regenerate` | Regenera a partir da config e roda `/ftbquests reload` |
| `/advquests regenerate reset_layout` | Igual, descartando posições manuais |

## Arquivos em runtime (servidor)

| Caminho | Uso |
|---|---|
| `config/advquests.json` | Config do mod |
| `config/ftbquests/quests/` | Capítulos/quests gerados (`.snbt`) |
| `<mundo>/advquests_synced.json` | Quest IDs já sincronizados por jogador |

## Segurança / produção

- Sem chaves de API, tokens ou URLs sensíveis no código.
- Sem `.env`: configuração só via JSON em disco.
- Logs: `info`/`error`/`warn` de ciclo de vida e falhas; `debug` só para loot tables opcionais.
