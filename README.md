# Fraud Detection System — SDD

Documentação inicial do projeto de detecção de transações suspeitas em tempo real.

## Estrutura

- `docs/project-overview.md`: objetivo, escopo e requisitos.
- `docs/architecture.md`: visão arquitetural e arquitetura hexagonal.
- `docs/business-rules.md`: regras iniciais de detecção e classificação.
- `docs/event-contracts.md`: contratos dos eventos Kafka.
- `docs/kafka-partitioning.md`: estratégia inicial de particionamento.
- `docs/implementation-plan.md`: sequência de implementação.
- `docs/adr/`: decisões arquiteturais registradas (ADR 0001: decisões iniciais).
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

Para trocar a porta, defina `SERVER_PORT`. Exemplo: `SERVER_PORT=8091`.

> **Conflito de porta conhecido:** outros stacks locais (ex.: Confluent Schema Registry) também usam 8081. Se o Transaction Service falhar com "Port 8081 was already in use", use `SERVER_PORT` para mudar a porta.

### Decisões de referência

- Arquitetura e decisões: `docs/adr/`.
- Planejamento e riscos: `docs/roadmap-execucao.md`.