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

## Como executar

Pré-requisitos: JDK 21, Maven 3.9+ e Docker com Compose v2.

### 1. Build e testes

```bash
mvn verify
```

Os testes de integração usam Testcontainers e precisam de Docker ativo. Os 68 testes cobrem unitários, integração com MySQL e Kafka reais, o fluxo ponta a ponta entre os três serviços (`e2e-tests`) e o smoke test de cada serviço.

Para o spike test da regra de frequência (fora do build padrão):

```bash
mvn -pl fraud-service -Dgroups=spike test
```

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

Limites de negócio e destinatário não têm valor padrão no código (ADR 0001, decisão 10). Sem eles, Fraud Detection e Notification não sobem:

| Variável | Serviço | Uso |
|---|---|---|
| `FRAUD_AMOUNT_THRESHOLD_CENTS` | Fraud | Limite de valor, em centavos (BR-02) |
| `FRAUD_FREQUENCY_MAX_TRANSACTIONS` | Fraud | Quantidade máxima na janela (BR-01) |
| `FRAUD_FREQUENCY_WINDOW` | Fraud | Janela, em ISO-8601 (ex.: `PT60S`) |
| `NOTIFICATION_CHANNEL` | Notification | Canal de envio (ex.: `EMAIL`) |
| `NOTIFICATION_RECIPIENT` | Notification | Destinatário |

Os valores de negócio ainda aguardam validação (`docs/business-rules.md`, seção 6). Para demonstração, use os valores abaixo, que são de demonstração e não de negócio:

```bash
export FRAUD_AMOUNT_THRESHOLD_CENTS=100000
export FRAUD_FREQUENCY_MAX_TRANSACTIONS=5
export FRAUD_FREQUENCY_WINDOW=PT60S
export NOTIFICATION_CHANNEL=EMAIL
export NOTIFICATION_RECIPIENT=ops-demo@example.invalid
export NOTIFICATION_PROVIDER=smtp   # envia para o Mailpit do docker-compose (ADR 0010); padrão: simulated
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

## Verificação no GitHub

O workflow `.github/workflows/ci.yml` executa `mvn -B verify` a cada push e pull request na `main`. A CI roda os mesmos testes da execução local, com Docker disponível no runner.

## Decisões de referência

- Arquitetura e decisões: `docs/adr/`.
- Estado das fases, riscos e próximas ações: `docs/roadmap-execucao.md`.
- Escopo e limites do estudo: `docs/project-overview.md`.
