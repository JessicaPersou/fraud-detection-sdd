# Fraud Detection System — SDD

Sistema de detecção de transações suspeitas em tempo real. Estudo de caso com três microsserviços comunicando-se por Kafka, arquitetura hexagonal e decisões registradas em ADRs.

## Estrutura

- `docs/project-overview.md`: objetivo, escopo e requisitos.
- `docs/architecture.md`: visão arquitetural e arquitetura hexagonal.
- `docs/business-rules.md`: regras de detecção, classificação e implementação atual.
- `docs/event-contracts.md`: contratos dos eventos Kafka e semântica implementada.
- `docs/kafka-partitioning.md`: estratégia de particionamento.
- `docs/implementation-plan.md`: sequência de implementação.
- `docs/adr/`: decisões arquiteturais. ADRs 0001 a 0007 e 0009 e 0010 registram decisões de implementação e operação. O ADR 0008 justifica a arquitetura alvo na AWS.
- `docs/cenarios-teste.md`: cenários de teste e cobertura automatizada.
- `docs/roadmap-execucao.md`: estado das fases, riscos e próximas ações.
- `specs/`: especificações dos três serviços.
- `load-tests/`: script de carga progressiva (k6).

## Arquitetura em resumo

- **Transaction Service** recebe `POST /transactions` com `Idempotency-Key`, grava a transação e o evento na mesma transação de banco (outbox) e publica `TransactionCreated`.
- **Fraud Detection Service** consome `transaction-events`, aplica as regras BR-01 (frequência) e BR-02 (valor), persiste a análise e publica `FraudDetected` para risco MEDIUM ou HIGH.
- **Notification Service** consome `fraud-alerts` e envia a notificação por um provedor (simulado nesta versão).

Justificativas de cada decisão estão nos ADRs. Os principais são ADR 0001 (decisões iniciais), ADR 0003 (outbox), ADR 0004 (idempotência), ADR 0005 (consumidores) e ADR 0008 (arquitetura alvo).

## Subir a aplicação completa

Este passo a passo sobe os três serviços, com Kafka, MySQL e Mailpit, na máquina local. Use a ordem abaixo.

Pré-requisitos: JDK 21, Maven 3.9+ e Docker com Compose v2, com o Docker em execução.

**1. Subir a infraestrutura** (Kafka, MySQL e Mailpit). Na primeira vez, a imagem é baixada e os tópicos e bancos são criados:

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d --wait
```

**2. Gerar os JARs executáveis** dos três serviços:

```bash
mvn -DskipTests package
```

Os JARs ficam em `<serviço>/target/<serviço>-0.1.0-SNAPSHOT-exec.jar`.

**3. Iniciar cada serviço em um terminal separado.** Não é preciso definir variáveis: os serviços sobem com o perfil `local`, que traz valores de demonstração (ver "Variáveis obrigatórias" abaixo).

```bash
java -jar transaction-service/target/transaction-service-0.1.0-SNAPSHOT-exec.jar
java -jar fraud-service/target/fraud-service-0.1.0-SNAPSHOT-exec.jar
java -jar notification-service/target/notification-service-0.1.0-SNAPSHOT-exec.jar
```

Cada serviço fica em primeiro plano e imprime os logs, incluindo os spans de trace. Para encerrar um serviço, use `Ctrl+C` no terminal dele.

**4. Verificar que estão no ar.** Cada serviço responde `UP` em `/actuator/health`:

| Serviço | Porta | Verificação |
|---|---|---|
| Transaction Service | 8081 | `curl -s localhost:8081/actuator/health` |
| Fraud Detection Service | 8082 | `curl -s localhost:8082/actuator/health` |
| Notification Service | 8083 | `curl -s localhost:8083/actuator/health` |

Se algum serviço não subir, a causa mais comum é uma porta ocupada (para trocá-la, defina `SERVER_PORT`) ou o perfil ter sido trocado para algo diferente de `local` sem as variáveis obrigatórias (ver "Variáveis obrigatórias").

**5. Enviar uma transação de teste:**

```bash
curl -s -X POST localhost:8081/transactions -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"customerId":"c-2","amountInCents":250000,"currency":"BRL"}'
```

Esse valor passa do limite de demonstração, então a análise sai como MEDIUM e o alerta é enviado. Para ver o e-mail, abra `http://localhost:8025` (Mailpit). Mais exemplos estão na seção "Demonstração do caminho feliz e das fraudes".

**6. Parar tudo:**

```bash
docker compose -f infrastructure/docker/docker-compose.yml down
```

Esse comando para a infraestrutura e mantém os dados do MySQL. Para apagar também os dados, use `down -v`. Atenção: o script que cria os bancos roda só na primeira vez em que o volume do MySQL é criado. Se você apagar o volume, a próxima subida recria os bancos. Se você não apagar, os bancos continuam como estavam.

## Como rodar os testes

A estratégia (pirâmide, ferramentas, cobertura por garantia e o que ainda não foi medido) está em `docs/estrategia-testes.md`. Esta seção mostra só os comandos.

### Pré-requisitos

- JDK 21 e Maven 3.9+.
- Docker em execução. Os testes de integração, os smoke tests e o ponta a ponta sobem containers reais com Testcontainers: `mysql:8.4` e `apache/kafka:3.9.1`. Na primeira execução, as imagens são baixadas, então ela é mais lenta.
- Os testes unitários não dependem de Docker.
- Os containers de teste usam portas aleatórias. Você pode deixar o `docker compose` da seção "Subir a aplicação completa" rodando ao mesmo tempo, sem conflito.

### Comandos

| Objetivo | Comando |
|---|---|
| Todos os testes e o empacotamento | `mvn verify` |
| Todos os testes, sem empacotar | `mvn test` |
| Um módulo | `mvn -pl transaction-service -am test` |
| Uma classe de teste | `mvn -pl fraud-service -am test -Dtest=AnalyzeTransactionServiceTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Um método de teste | `mvn -pl fraud-service -am test -Dtest=AnalyzeTransactionServiceTest#duplicateEventDoesNotRepeatAnalysisOrAlert -Dsurefire.failIfNoSpecifiedTests=false` |
| Só o ponta a ponta | `mvn -pl e2e-tests -am test -Dtest=AntifraudEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Spike test da BR-01 (fora do build padrão) | `mvn -pl fraud-service -am test -Dgroups=spike -Dfraud.excluded.groups= -Dsurefire.failIfNoSpecifiedTests=false` |

Notas sobre os comandos:

- `-am` (also make) compila também os módulos dos quais o módulo depende, como `event-contracts` e `test-support`. Sem ele, o módulo pode não encontrar as dependências.
- Com `-Dtest`, o Maven roda só o teste pedido no módulo escolhido. Os outros módulos da compilação não têm esse teste, e `-Dsurefire.failIfNoSpecifiedTests=false` evita que o build falhe por isso.
- O projeto não usa Failsafe. Os testes de integração rodam na mesma fase que os unitários, então `mvn test` e `mvn verify` executam a mesma lista de testes. O `verify` ainda empacota os JARs.
- O spike test é excluído do build padrão pelo grupo `spike`, definido no `fraud-service/pom.xml` pela propriedade `fraud.excluded.groups`. O comando acima zera essa propriedade para executá-lo.

### O que cada tipo de teste cobre

| Tipo | Quantidade | Onde está | Docker |
|---|---|---|---|
| Unitários | 35 | `DomainModelTest`, `ReceiveTransactionServiceTest` (transaction); `AnalyzeTransactionServiceTest`, `FraudRuleEvaluatorTest` (fraud); `SmtpNotificationProviderTest`, `ProcessFraudAlertServiceTest` (notification) | Não |
| Integração | 21 | Módulos `*-service`, com MySQL e Kafka reais: `TransactionApiIntegrationTest`, `AtomicityIntegrationTest`, `OutboxPublishingResilienceIntegrationTest`, `RetentionCleanupIntegrationTest`, `FraudPipelineIntegrationTest`, `ConsumerRetryIntegrationTest`, `NotificationPipelineIntegrationTest` | Sim |
| Smoke | 4 | `*SmokeTest` em cada serviço: o contexto sobe e o health responde `UP` | Sim |
| Ponta a ponta | 8 | `e2e-tests/AntifraudEndToEndTest`: os três serviços na mesma JVM | Sim |
| Spike (fora do build) | 1 | `FrequencyRuleSpikeTest`, tag `spike` | Sim |

Os cenários de teste, com os IDs usados nos documentos (F-01, I-07, O-02 e outros), estão em `docs/cenarios-teste.md`, seção 10. Essa seção mostra qual classe cobre cada cenário.

Os containers de MySQL e Kafka são compartilhados dentro de cada execução pela classe `SharedInfrastructure` (módulo `test-support`). Eles são iniciados uma vez por JVM, não a cada teste.

### Relatórios e resultados

- Cada módulo grava os relatórios em `<módulo>/target/surefire-reports/`. Há um `.txt` (resumo) e um `.xml` por classe de teste.
- O Maven mostra o resumo no fim do build: `Tests run: ..., Failures: ..., Errors: ..., Skipped: ...`. Um build bem-sucedido termina com `BUILD SUCCESS`.

### Problemas comuns

- **"Could not find a valid Docker environment"**: o Docker não está rodando. Abra o Docker Desktop (no Windows, com WSL2 ativo) e rode o comando de novo.
- **Primeira execução muito lenta**: as imagens `mysql:8.4` e `apache/kafka:3.9.1` estão sendo baixadas. As próximas execuções são mais rápidas.
- **Falha intermitente por tempo**: em máquinas lentas, os testes assíncronos (Kafka e outbox) podem estourar o tempo de espera. Rode a classe que falhou isoladamente com `-Dtest=` para confirmar se é instabilidade ou defeito.
- **Teste falha após mudar uma migração**: confira `V1` e `V2` em `transaction-service/src/main/resources/db/migration/`. Os testes usam as mesmas migrações da aplicação.

## Como executar

Pré-requisitos: JDK 21, Maven 3.9+ e Docker com Compose v2.

### 1. Build e testes

```bash
mvn verify
```

O build padrão executa 68 testes e o spike test da BR-01 fica fora dele. Comandos por tipo de teste, o que cada um cobre e como resolver problemas estão em "Como rodar os testes", no início desta seção.

### 2. Infraestrutura local

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d --wait
```

Sobe Kafka (KRaft) com os tópicos `transaction-events`, `fraud-alerts` e as DLQs (12 partições, ADR 0005), e MySQL 8.4 com um banco e usuário por serviço. As credenciais são apenas para desenvolvimento.

### 3. Serviços

Cada serviço expõe o health check em `/actuator/health`.

| Serviço | Porta | JAR |
|---|---|---|
| Transaction Service | 8081 | `transaction-service/target/transaction-service-0.1.0-SNAPSHOT-exec.jar` |
| Fraud Detection Service | 8082 | `fraud-service/target/fraud-service-0.1.0-SNAPSHOT-exec.jar` |
| Notification Service | 8083 | `notification-service/target/notification-service-0.1.0-SNAPSHOT-exec.jar` |

Gere os JARs com `mvn -DskipTests package` e inicie cada um com `java -jar`.

#### Variáveis obrigatórias

Limites de negócio e destinatário não têm valor padrão fora do perfil `local` (ADR 0001, decisão 10; ADR 0011). Fora desse perfil, sem as variáveis abaixo Fraud Detection e Notification não sobem:

| Variável | Serviço | Uso |
|---|---|---|
| `FRAUD_AMOUNT_THRESHOLD_CENTS` | Fraud | Limite de valor, em centavos (BR-02) |
| `FRAUD_FREQUENCY_MAX_TRANSACTIONS` | Fraud | Quantidade máxima na janela (BR-01) |
| `FRAUD_FREQUENCY_WINDOW` | Fraud | Janela, em ISO-8601 (ex.: `PT60S`) |
| `NOTIFICATION_CHANNEL` | Notification | Canal de envio (ex.: `EMAIL`) |
| `NOTIFICATION_RECIPIENT` | Notification | Destinatário |

Os valores de negócio ainda aguardam validação (`docs/business-rules.md`, seção 6).

**Execução local (perfil `local`).** Os `application.yml` ativam o perfil `local` por padrão. Ele traz valores de demonstração em `application-local.yml` de cada serviço, então `java -jar` sobe sem exportar nada. Esses valores não são de negócio. O provedor de notificação do perfil `local` é `smtp`, que envia para o Mailpit do docker-compose (ADR 0010).

**Produção ou outro ambiente.** Defina `SPRING_PROFILES_ACTIVE=prod` (ou qualquer valor diferente de `local`). Sem o perfil `local`, as variáveis acima passam a ser obrigatórias e a aplicação não sobe sem elas. Variáveis de ambiente têm precedência sobre o arquivo do perfil, então também servem para trocar um valor de demonstração sem editar arquivos:

```bash
export FRAUD_AMOUNT_THRESHOLD_CENTS=50000   # exemplo: sobrescreve o valor do perfil local
```

Os alertas por e-mail chegam ao Mailpit, que captura as mensagens sem entregá-las a ninguém. Abra `http://localhost:8025` para ver cada e-mail.

Cada serviço imprime os spans no próprio log (ADR 0010). Uma transação aparece com o mesmo `traceId` no Transaction Service, no publicador do outbox, no Fraud Detection e no Notification.

## Demonstração do caminho feliz e das fraudes

Com os três serviços no ar:

```bash
# Transação normal: 201, análise LOW, sem alerta
curl -s -X POST localhost:8081/transactions -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"customerId":"c-1","amountInCents":15000,"currency":"BRL"}'

# Valor acima do limite: 201, análise MEDIUM, alerta publicado e notificação aceita
curl -s -X POST localhost:8081/transactions -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"customerId":"c-2","amountInCents":250000,"currency":"BRL"}'

# Reenvio com a mesma chave e o mesmo corpo: 200, mesma resposta, sem nova transação
KEY=$(uuidgen); BODY='{"customerId":"c-3","amountInCents":15000,"currency":"BRL"}'
curl -s -X POST localhost:8081/transactions -H "Idempotency-Key: $KEY" -H "Content-Type: application/json" -d "$BODY"
curl -s -X POST localhost:8081/transactions -H "Idempotency-Key: $KEY" -H "Content-Type: application/json" -d "$BODY"

# Mesma chave com outro corpo: 422
curl -s -X POST localhost:8081/transactions -H "Idempotency-Key: $KEY" -H "Content-Type: application/json" \
  -d '{"customerId":"c-3","amountInCents":99999,"currency":"BRL"}'
```

Para ver as análises e as notificações, consulte `fraud_db.fraud_analysis` e `notification_db.notification_record` no MySQL local.

Para a regra de frequência, envie mais de cinco transações do mesmo cliente em 60 segundos. A sexta é classificada como MEDIUM pela regra `TRANSACTION_FREQUENCY`.

## Verificação

Nesta versão não há pipeline de CI ativa no repositório: o workflow `.github/workflows/ci.yml` foi removido. A verificação é feita localmente com `mvn verify` (Docker ativo). A intenção de manter GitHub Actions na arquitetura alvo está no ADR 0008, seção 3.10.

## Apresentação e diagramas

- `diagramas/`: arquiteturas (simples e alvo na AWS) e modelos de dados dos três bancos, em formato draw.io, com os DDLs consolidados em `diagramas/ddl/`.
- A apresentação e o roteiro de entrevista não ficam no repositório.

## Decisões de referência

- Arquitetura e decisões: `docs/adr/`.
- Estratégia de testes: `docs/estrategia-testes.md`.
- Limitações e possíveis evoluções, com gatilhos: `docs/limitacoes-e-evolucoes.md`.
- Estado das fases, riscos e próximas ações: `docs/roadmap-execucao.md`.
- Escopo e limites do estudo: `docs/project-overview.md`.
