# BigBangExpeditions

Mod Minecraft Forge **1.20.1** — harness de validação de exploração renovável para **Lost Cities**, com diagnósticos read-only para segurança de reset de setores.

## Sobre

BigBangExpeditions fornece infraestrutura para explorar dimensões do Lost Cities em "expedições" por setores, com:

- **Setores** — registro, bounds, estado e topologia de setores (`sector/`)
- **Reset seguro** — planos de reset com manifest e confinamento de caminhos (`reset/`)
- **Diagnósticos read-only** — relatórios `doctor` antes de qualquer operação destrutiva (`diagnostics/`)
- **Integrações** — adaptadores para Lost Cities e Open Parties and Claims (OPAC) (`integration/`)
- **Loot** — políticas de loot configuráveis (`loot/`)
- **Segurança e validação** — camadas de validação para operações de reset (`safety/`, `validation/`)

## Comandos

| Comando | Descrição |
|---|---|
| `/expedition` | Gerenciamento geral de expedições |
| `/expedition teleport` | Teleporte entre setores/dimensão |
| `/dimension status` | Status da dimensão |
| `/sector` | Inspeção e gerenciamento de setores |
| `/opac selftest` | Auto-teste da integração OPAC |

## Requisitos

- Java 17
- Minecraft 1.20.1
- Forge 47.4.0+
- (Opcional) Lost Cities / OPAC para integrações

## Build

```bash
./gradlew build
```

Jar gerado em `build/libs/`.

### Rodar em dev

```bash
./gradlew runClient   # cliente
./gradlew runServer   # servidor dedicado
./gradlew runData     # datagen
```

### Testes

```bash
./gradlew test
```

## Instalação

1. Instale o Forge 47.4.0+ para Minecraft 1.20.1.
2. Copie o jar de `build/libs/` para a pasta `mods/`.
3. Inicie o jogo/servidor.

## Estrutura

```
src/main/java/com/bigbangcraft/expeditions/
├── command/       # comandos (/expedition, /sector, ...)
├── diagnostics/   # DoctorService, DoctorReport
├── integration/   # lostcities/, opac/
├── loot/          # LootPolicy
├── reset/         # ResetPlanService, ResetPlanManifest
├── safety/        # verificações de segurança
├── sector/        # SectorRegistry, SectorTopology, ...
├── teleport/      # teleporte de expedição
└── validation/    # validações
```

## Licença

MIT — ver [LICENSE](LICENSE).
