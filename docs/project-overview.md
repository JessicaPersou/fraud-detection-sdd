# Project Overview — Fraud Detection System

**Versão:** 1.0 — proposta inicial  
**Arquitetura:** Microsserviços + Arquitetura Hexagonal  
**Stack proposta:** Java 21, Spring Boot, Apache Kafka e MySQL.

## 1. Objetivo

Desenvolver um motor capaz de analisar transações financeiras em tempo real, identificar comportamentos potencialmente suspeitos e gerar alertas para sistemas internos e externos.

## 2. Requisitos funcionais

- Receber e validar transações.
- Processar eventos de forma assíncrona.
- Aplicar regras de detecção de fraude.
- Classificar o risco da transação.
- Gerar alertas quando houver comportamento suspeito.
- Publicar alertas para os sistemas consumidores.
- Manter histórico das análises realizadas.

## 3. Requisitos não funcionais

| Requisito | Meta inicial |
|---|---:|
| Volume médio | 8.000 TPS |
| Volume de pico | 25.000 TPS |
| Latência de geração do alerta | No máximo 500 ms, do recebimento da transação à publicação do alerta |
| Escalabilidade | Horizontal |
| Observabilidade | Logs, métricas e rastreamento |

### Definição provisória do SLA

A medição de 500 ms começa quando o Transaction Service recebe a transação e termina quando o evento de alerta é publicado no tópico `fraud-alerts`. A meta vale para transações que geram alerta. A latência da análise sem alerta é medida à parte.

A meta deve ser validada por testes de carga, incluindo p95 e p99.

A entrega da notificação ao destinatário tem SLA próprio, condicionado ao processamento dos provedores externos, e não entra nessa meta.

## 4. Arquitetura

Três microsserviços independentes:

1. Transaction Service.
2. Fraud Detection Service.
3. Notification Service.

Os serviços comunicam-se assincronamente por Apache Kafka e seguem Arquitetura Hexagonal (Ports and Adapters).

## 5. Stack tecnológica

- Java 21.
- Spring Boot.
- Apache Kafka.
- MySQL.
- Docker.
- Redis: não incluído inicialmente; poderá ser avaliado se houver necessidade de contadores temporários de baixa latência.

## 6. Escopo da primeira versão

- Recepção e validação de transações.
- Detecção baseada em regras iniciais.
- Classificação LOW, MEDIUM e HIGH.
- Publicação de alertas via Kafka.
- Consumo e encaminhamento de notificações.
- Persistência do histórico.
- Tratamento básico de falhas e duplicidades.
- Testes unitários, integração e carga.

## 7. Fora do escopo inicial

- Bloqueio automático de transações.
- Machine Learning.
- Modelos preditivos.
- Painel administrativo.
- Múltiplos provedores de notificação.
- Risk Scoring avançado.
- CDC (Change Data Capture). O Transactional Outbox entra na primeira versão (ADR 0001).

## 8. Critérios de sucesso

1. Receber e validar uma transação.
2. Publicar o evento no Kafka.
3. Consumir e analisar o evento.
4. Gerar alerta quando regras forem acionadas.
5. Disponibilizar o alerta ao Notification Service.
6. Persistir os resultados necessários.
7. Validar o fluxo com testes automatizados.
8. Medir throughput e latência em testes de carga.

## 9. Decisões pendentes

- Limites definitivos das regras de fraude.
- Precisão e janela temporal da regra de frequência.
- Critérios de localização suspeita e disponibilidade dos dados geográficos.
- Hospedagem de produção na AWS: ADR 0008 (EKS, RDS for MySQL e Confluent Cloud). Região: sa-east-1 (São Paulo). Confluent Dedicated com PrivateLink. Pendentes: sistema chamador com mTLS, validação do OIDC com o Confluent, limites e custo do Dedicated e residência de dados.
- Consistência banco × Kafka resolvida pelo Transactional Outbox com publicação por polling (ADR 0001 e ADR 0003).
- Provedor e SLA de notificação externa.
- Requisitos de segurança, privacidade e LGPD.
