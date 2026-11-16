# Fraud Detection System — SDD

Documentação inicial do projeto de detecção de transações suspeitas em tempo real.

## Estrutura

- `docs/project-overview.md`: objetivo, escopo e requisitos.
- `docs/architecture.md`: visão arquitetural e arquitetura hexagonal.
- `docs/business-rules.md`: regras iniciais de detecção e classificação.
- `docs/event-contracts.md`: contratos dos eventos Kafka.
- `docs/kafka-partitioning.md`: estratégia inicial de particionamento.
- `docs/implementation-plan.md`: sequência de implementação.
- `docs/adr/`: decisões arquiteturais registradas (ADRs 0001 a 0008 aceitas; ADR 0008, arquitetura AWS, com pendências do spike test).
- `docs/cenarios-teste.md`: cenários de teste do caminho feliz e das bordas.
- `specs/transaction-service/specification.md`: especificação do serviço de transações.
- `specs/fraud-detection-service/specification.md`: especificação do motor antifraude.
- `specs/notification-service/specification.md`: especificação do serviço de notificações.

## Status

Documentação inicial para discussão e prototipação. Limites de negócio, capacidade de infraestrutura, requisitos de segurança e SLAs precisam ser validados antes de produção.


## Como Iniciar o Projeto

Pré-requisitos: JDK 21, Maven 3.9+, Docker com Compose v2.

### 1. Build e testes

```bash
mvn verify
```

### 2. Infraestrutura local (Kafka + MySQL)

```bash
docker compose -f infrastructure/docker/docker-compose.yml up -d --wait
```

Sobe Kafka (KRaft), cria os tópicos `transaction-events`, `fraud-alerts` e suas DLQs (12 partições, ADR 0005), e MySQL 8.4 com um banco e usuário por serviço.

As credenciais do ambiente local são apenas para desenvolvimento.

### 3. Serviços

Cada serviço expõe o health check em `/actuator/health`.

| Serviço | Porta padrão | Comando |
|---|---|---|
| Transaction Service | 8081 | `java -jar transaction-service/target/transaction-service-0.1.0-SNAPSHOT.jar` |
| Fraud Detection Service | 8082 | `java -jar fraud-service/target/fraud-service-0.1.0-SNAPSHOT.jar` |
| Notification Service | 8083 | `java -jar notification-service/target/notification-service-0.1.0-SNAPSHOT.jar` |

#### Variáveis obrigatórias

Limites de negócio e destinatário não têm valor padrão no código (ADR 0001, decisão 10). Sem eles, Fraud Detection e Notification não sobem:

| Variável | Serviço | Uso |
|---|---|---|
| `FRAUD_AMOUNT_THRESHOLD_CENTS` | Fraud | Limite de valor, em centavos (BR-02) |
| `FRAUD_FREQUENCY_MAX_TRANSACTIONS` | Fraud | Quantidade máxima na janela (BR-01) |
| `FRAUD_FREQUENCY_WINDOW` | Fraud | Janela, em ISO-8601 (ex.: `PT60S`) |
| `NOTIFICATION_CHANNEL` | Notification | Canal de envio (ex.: `EMAIL`) |
| `NOTIFICATION_RECIPIENT` | Notification | Destinatário |

Os valores ainda aguardam validação de negócio (`docs/business-rules.md`, seção 6). Para testes locais, use valores explícitos e marcados como de teste. Os demais parâmetros têm padrão em `application.yml`.

Exemplo:

```bash
FRAUD_AMOUNT_THRESHOLD_CENTS=100000 FRAUD_FREQUENCY_MAX_TRANSACTIONS=5 FRAUD_FREQUENCY_WINDOW=PT60S \
NOTIFICATION_CHANNEL=EMAIL NOTIFICATION_RECIPIENT=ops-test@example.invalid \
java -jar fraud-service/target/fraud-service-0.1.0-SNAPSHOT.jar
```

### 4. Testes

```bash
mvn verify                 # unitários e integração (exige Docker: Testcontainers com MySQL 8.4 e Kafka)
mvn -pl fraud-service -Dgroups=spike test   # spike test da regra BR-01 (Fase 3b), fora do build padrão
```

O módulo `e2e-tests` sobe os três serviços na mesma JVM e valida o fluxo completo (Fase 5). A latência reportada por ele é informativa e não valida o SLA de 500 ms.

### Decisões de referência

- Arquitetura e decisões: `docs/adr/`.
- Planejamento e riscos: `docs/roadmap-execucao.md`.