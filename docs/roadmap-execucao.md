# Roadmap de Execução

Este documento organiza a resolução das pendências e a execução das fases do projeto. Ele complementa `implementation-plan.md` com decisões tomadas, critérios de conclusão e o registro de riscos.

**Princípio:** o projeto não resolve todos os problemas de escala antecipadamente. Ele identifica riscos, define como medi-los e documenta quando e por que a solução deve evoluir.

---

## 1. Pendências resolvidas

| Pendência | Decisão | Documento | Evolução prevista |
|---|---|---|---|
| Notificação intermediária (`NotificationRequested`) | Não criar na v1 | ADR 0002 | Quando houver roteamento por canal |
| Publicação do outbox | Polling com `SKIP LOCKED` | ADR 0003 | CDC (Debezium) por gatilho de lag ou carga |
| Idempotência da API | Hash do payload, retenção 24 h, `422` para payload divergente | ADR 0004 | Redis se a tabela virar gargalo |
| Consumidores (grupos, DLQ, retry, inbox) | Parâmetros definidos, 12 partições como hipótese | ADR 0005 | Mais partições por evidência de spike |
| Moeda e precisão | Somente BRL na v1, expoente ISO 4217 | ADR 0006 | Novas moedas mediante validação |
| Stack e ferramentas | Java 21, Spring Boot 4.1.2, Maven, Kafka KRaft, MySQL 8.4 | ADR 0007 | Atualizações em PRs dedicados |

### Ainda dependem de validação de negócio

Estas pendências **não** são resolvidas por decisão técnica:

- Limite de valor (BR-02).
- Janela e limite de frequência (BR-01).
- Distância e intervalo para localização (BR-03).
- Pesos e combinação de sinais (BR-04).
- Provedor e SLA de notificação externa.
- Requisitos de LGPD e segurança.

Os valores ficam em configuração com placeholders. Nenhum valor é inventado (`implementation-plan.md`, uso de IA).

---

## 2. Fase 1 — Fundação

**Objetivo:** ambiente local reproduzível, build automatizado e serviços de pé com health check.

### Tarefas

- [x] Instalar ferramentas: JDK 21 (Temurin) e Maven 3.9.11 (ambiente local).
- [x] Monorepo Maven multi-módulo com `event-contracts`, `transaction-service`, `fraud-service`, `notification-service`.
- [x] Aplicação Spring Boot mínima em cada serviço (Spring Boot 4.1.2), com Actuator e porta própria.
- [x] Teste de smoke por serviço: contexto sobe e `/actuator/health` responde `UP`.
- [x] Docker Compose com Kafka (KRaft 3.9.1), MySQL 8.4 e criação de tópicos e DLQs com 12 partições.
- [x] Script de inicialização do MySQL com um banco e usuário por serviço.
- [x] CI com GitHub Actions executando `mvn verify` (arquivo criado; execução remota ainda não verificada).
- [x] `.gitignore` e README com instruções de execução.

### Critérios de conclusão

1. [x] `mvn verify` passa na raiz: 5 módulos, 3 testes de smoke, `BUILD SUCCESS`.
2. [x] `docker compose up` sobe Kafka e MySQL saudáveis.
3. [x] Os tópicos existem com 12 partições, conforme ADR 0005 (verificado com `kafka-topics --describe`).
4. [x] Os três serviços iniciam como processos e respondem `UP` em `/actuator/health` (Transaction 8081, Fraud 8082 e Notification 8083). Na primeira execução a 8081 estava ocupada por outro stack local; após a remoção desses containers, o Transaction voltou à porta padrão.
5. [ ] A pipeline de CI executa o mesmo build no GitHub. Só será confirmado após o primeiro push.

### Achados da execução

- A porta 8081 estava ocupada por `schema-registry` de outro stack local. A configuração por `SERVER_PORT` resolveu sem alterar o outro projeto. Documentado no README.
- O MySQL não aceita índice parcial. Isso já foi corrigido no ADR 0003.
- Um smoke test estava com campo injetado inútil; foi removido antes do build.

Fora do escopo da Fase 1: entidades, endpoints de negócio, integração com Kafka nos serviços. Isso começa na Fase 2.

---

## 3. Registro de riscos

Cada risco tem uma forma de medir e um gatilho de evolução. O objetivo é mostrar que o risco é conhecido e que a decisão tem critério.

| # | Risco | Como medir | Gatilho de evolução | Ação |
|---|---|---|---|---|
| R1 | Regra de frequência (BR-01) não atinge 500 ms em MySQL | p95/p99 do spike test na Fase 3b | p99 acima do limite | Avaliar Redis ou janela pré-agregada |
| R2 | Capacidade por partição abaixo de 2.100 eventos/s no pico | Throughput por instância em teste de carga | Lag crescendo no pico | Aumentar partições e instâncias (planejar mudança de chave) |
| R3 | Atraso de publicação do outbox | Idade do registro pendente mais antigo | Lag acima do limite definido | CDC (ADR 0003) |
| R4 | Partição quente (cliente com volume alto) | Mensagens e lag por partição | Uma partição muito acima da média | Estratégia alternativa de chave (`kafka-partitioning.md` §5) |
| R5 | Crescimento das tabelas de idempotência e inbox | Número de linhas e tamanho | Crescimento sem limpeza | Jobs de limpeza por expiração |
| R6 | Falha do provedor de notificação | Taxa de erro e DLQ do Notification | Erros acima do limite | Circuit breaker e retry (já previstos) |
| R7 | Consistência de regras entre ambientes | Testes de regra com configuração versionada | Divergência entre ambientes | Configuração centralizada e validada na inicialização |

---

## 4. Próximas fases

- **Fase 2 — Transaction Service:** endpoint com `Idempotency-Key`, outbox, publicador com polling e testes de integração com Testcontainers (ADR 0003, ADR 0004).
- **Fase 3 — Fraud Detection:** 3a BR-02; 3b BR-01 com spike test (R1, R2); 3c explicabilidade, inbox, retry e DLQ (ADR 0005).
- **Fase 4 — Notification:** provedor simulado, status de envio e DLQ (ADR 0002).
- **Fase 5 — Integração e carga:** duplicatas, falhas de provedor, testes progressivos até 25.000 TPS e revisão dos riscos R1 a R7 com métricas reais.

---

## 5. O que não resolvemos agora

Deliberadamente fora do escopo inicial, com justificativa:

- **Escala além do spike test:** sem evidência de carga, sharding ou múltiplas regiões seriam otimização prematura.
- **CDC e Redis:** só entram com gatilho medido (ADR 0003, ADR 0004).
- **Machine Learning e risk scoring avançado:** fora do escopo da v1 (`project-overview.md`).
- **Bloqueio automático de transações:** fora do escopo da v1.
