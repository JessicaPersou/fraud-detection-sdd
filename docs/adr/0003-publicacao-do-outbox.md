# ADR 0003 — Publicação do Transactional Outbox (polling)

**Status:** Aceita  
**Data:** 2026-11-15  
**Resolve:** ADR 0001 §5, item 2  
**Relaciona-se com:** ADR 0001, decisões 2 e 5

## Contexto

O Transaction Service grava a transação e o evento `TransactionCreated` na mesma transação de banco (tabela `outbox_event`). Falta escolher como esses registros chegam ao Kafka:

- **Polling publisher:** um agendador lê os registros pendentes e os publica.
- **CDC (Debezium):** um conector lê o log de transações do MySQL (binlog).

A visão geral exclui CDC da primeira versão.

## Decisão

Polling publisher dentro do próprio Transaction Service:

- Consulta em lotes: `SELECT ... FROM outbox_event WHERE published_at IS NULL ORDER BY id LIMIT 500 FOR UPDATE SKIP LOCKED` (MySQL 8.x).
- `SKIP LOCKED` permite várias instâncias do serviço sem publicar o mesmo evento duas vezes ao mesmo tempo.
- Chave Kafka: `customerId`, para preservar ordem por cliente na partição.
- Produtor com `acks=all` e `enable.idempotence=true`.
- Em falha, o registro permanece pendente e é retentado com o mesmo `eventId`.
- Intervalo de polling configurável (valor inicial: 200 ms).

## Justificativa

- Não exige infraestrutura extra (Debezium, Kafka Connect), o que simplifica a Fase 1 e 2.
- Demonstra o padrão de forma completa, com garantia *at-least-once* no lado do produtor. O consumidor trata duplicatas (ADR 0001, decisão 6).
- A latência adicional do polling é pequena frente ao SLA de 500 ms, que mede a partir do consumo pelo Fraud Detection Service.

## Riscos

| Risco | Como medir | Mitigação |
|---|---|---|
| Carga de polling sobre o MySQL | Tempo de consulta e CPU do banco | Índice em `published_at` (MySQL não suporta índice parcial), lote limitado |
| Atraso de publicação (outbox lag) | Idade do registro pendente mais antigo (métrica) | Alerta quando o lag ultrapassar o limite definido |
| Tabela crescendo | Número de linhas publicadas | Job de limpeza de registros publicados há mais de N dias |

## Evolução

Migrar para CDC (Debezium) quando **um** destes critérios ocorrer:

1. Outbox lag p99 acima do limite mesmo com polling ajustado.
2. Carga de polling afetando a latência de escrita das transações (medido no spike test).
3. Volume de eventos exigir leitura do log sem consultas repetidas ao banco.

A migração preserva o contrato do evento e a tabela outbox; só muda quem publica.
