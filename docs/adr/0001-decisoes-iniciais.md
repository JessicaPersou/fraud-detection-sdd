# ADR 0001 — Decisões arquiteturais iniciais

**Status:** Aceita  
**Data:** 2026-11-15  
**Escopo:** Sistema antifraude (Transaction Service, Fraud Detection Service, Notification Service)

Este documento registra as decisões técnicas tomadas na Fase 0 e as lacunas identificadas nas especificações. Cada decisão substitui qualquer formulação anterior conflitante nos documentos de `docs/` e `specs/`.

---

## 1. Decisões

| # | Tema | Decisão | Justificativa |
|---|---|---|---|
| 1 | Geração de `transactionId` | O cliente envia um header `Idempotency-Key`. O servidor gera o `transactionId` definitivo e associa esse ID à chave de idempotência. | Permite reenvios seguros sem duplicar transações. |
| 2 | Estabilidade do `eventId` | O `eventId` é gerado uma única vez e persistido na tabela de outbox. Todas as tentativas de publicação reutilizam o mesmo ID. | Garante que a desduplicação por `eventId` funcione mesmo com retries. |
| 3 | Valores monetários | Valores são representados em menor unidade monetária (`amountInCents`, inteiro). | Evita erros de arredondamento de ponto flutuante em JSON. |
| 4 | Frequência (BR-01) | MySQL na Fase 3, validado por um *spike test* de carga antecipado (Fase 3b). | Confirma a viabilidade antes da Fase 5. Se o spike falhar, a estratégia de armazenamento é reavaliada. |
| 5 | Consistência banco × Kafka | Padrão *Transactional Outbox* desde a Fase 2. A transação e o evento são gravados na mesma transação de banco. | Nenhum evento se perde entre a gravação e a publicação. |
| 6 | Idempotência | Tabela de idempotência na API (controle de `Idempotency-Key`) e tabela *inbox* nos consumidores (controle de processamento duplicado). | Separa a duplicação de entrada da duplicação de processamento. |
| 7 | Explicabilidade | O contrato `FraudDetected` inclui, para cada regra acionada, `ruleCode`, `description` e `reason`. | Atende ao requisito de que cada regra explique por que foi acionada (`business-rules.md` §5). |
| 8 | Commit de offset | `enable-auto-commit: false`. O consumidor confirma o offset somente após concluir a persistência e o processamento. Semântica *at-least-once* combinada com processamento idempotente. | Evita perda de análise em falhas. |
| 9 | Parâmetros operacionais | Nomes de consumer groups, tópicos de DLQ, políticas de retry, backoff e retenção são parâmetros explícitos e configuráveis. | Evita valores implícitos no código. |
| 10 | Limiares de negócio | Limites (BR-02), distância e tempo (BR-03) e pesos (BR-04) são externalizados em configuração. | Permite ajuste sem alterar código, enquanto a área de negócio não valida os valores. |

### 1.1 Exemplo de configuração (regras)

> **Atenção:** este exemplo mostra a intenção de configuração. As regras `geographic` (BR-03) e `risk-score` (BR-04 com pesos) **não estão implementadas**. Na implementação atual, as regras são `amount` e `frequency` (ver `business-rules.md` §7).

```yaml
fraud:
  rules:
    amount:
      enabled: true
      threshold: ${FRAUD_AMOUNT_THRESHOLD}
    geographic:
      enabled: true
      max-distance-km: ${FRAUD_MAX_DISTANCE_KM}
      time-window-minutes: ${FRAUD_TIME_WINDOW_MINUTES}
    risk-score:
      enabled: true
      weights:
        amount: ${FRAUD_WEIGHT_AMOUNT}
        frequency: ${FRAUD_WEIGHT_FREQUENCY}
        geographic: ${FRAUD_WEIGHT_GEOGRAPHIC}
```

Os valores ainda não foram validados pela área de negócio. Nenhum valor padrão deve ser inventado no código.

---

## 2. Estrutura do repositório

- **Monorepo** com **Maven Multi-Module**.
- Serviços independentes para deploy, com execução local simplificada por Docker Compose.

```text
antifraud-system/
├── transaction-service/
├── fraud-service/
├── notification-service/
├── event-contracts/
├── infrastructure/
│   ├── docker/
│   └── kafka/
└── docs/
    ├── adr/
    └── business-rules.md
```

### 2.1 Módulo `event-contracts`

- Contém somente DTOs de eventos publicados ou consumidos.
- Não contém entidades de banco, repositórios nem lógica de negócio.

---

## 3. Idioma

| Elemento | Idioma |
|---|---|
| Classes, métodos, variáveis e atributos Java | Inglês |
| Endpoints e eventos | Inglês |
| Comentários de código e logs técnicos | Inglês |
| README, documentação e ADRs | Português |

---

## 4. Sequência de implementação

- **Fase 0 — Fundamentos:** registro das decisões em `docs/adr/`.
- **Fase 1 — Base:** monorepo Maven, Docker Compose (Kafka em KRaft e MySQL), CI e health checks.
- **Fase 2 — Transaction Service:** endpoint REST, validação, persistência, outbox e publicação de `TransactionCreated`, com arquitetura hexagonal.
- **Fase 3 — Detecção:**
  - 3a: BR-02 (valor), validando o fluxo ponta a ponta.
  - 3b: BR-01 (frequência) com *spike test* de carga.
  - 3c: alertas, idempotência (inbox), retry, DLQ e observabilidade.
- **Fase 4 — Notification Service:** com provedor simulado (*fake provider*).
- **Fase 5 — Integração e carga:** duplicatas, falhas de provedor e testes progressivos até 25.000 TPS.

---

## 5. Pendências identificadas

Os itens abaixo foram resolvidos pelos ADRs 0002 a 0006 e 0009, e permanecem aqui para histórico. Para o estado atual, ver o ADR indicado em cada item.

Os itens abaixo não foram definidos no resumo aprovado. Não devem ser implementados até validação.

1. **Contrato `NotificationRequested`.** O resumo cita este evento no módulo `event-contracts`, mas o fluxo atual usa `FraudDetected` como entrada do Notification Service. Não será criado até que o fluxo seja definido.
2. **Mecanismo de publicação do outbox.** Não foi definido se será um *polling publisher* ou CDC. A visão geral exclui CDC da primeira versão, então o padrão provável é polling. Requer confirmação.
3. **Escopo e retenção do `Idempotency-Key`.** Não foi definido o comportamento para mesma chave com payload diferente (ex.: 409) nem o prazo de retenção.
4. **Retenção da tabela inbox.** Depende do período de replay do Kafka (ver `event-contracts.md` §4).
5. **Precisão monetária por moeda.** `amountInCents` assume duas casas decimais. Moedas com outra precisão exigem regra específica.
6. **Valores operacionais concretos.** Nomes de consumer groups, tópicos DLQ, número de retries, backoff e número inicial de partições ainda precisam ser preenchidos.

---

## 6. Consequências

- Os documentos `project-overview.md`, `architecture.md`, `event-contracts.md`, `implementation-plan.md` e as especificações dos serviços foram atualizados para refletir estas decisões.
- O Outbox deixa de ser "evolução futura" e passa a fazer parte da Fase 2.
- Como o `FraudDetected` ainda não tem consumidores em produção, a mudança de `triggeredRules` (lista de strings) para objetos explicativos é feita na versão 1 do contrato.
