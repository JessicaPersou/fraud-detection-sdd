# ADR 0005 — Consumidores Kafka: grupos, tópicos, retry, DLQ e inbox

**Status:** Aceita  
**Data:** 2026-11-15  
**Resolve:** ADR 0001 §5, itens 4 e 6 (parâmetros operacionais, decisão 9)  
**Relaciona-se com:** `docs/kafka-partitioning.md`, `docs/event-contracts.md`

## 1. Nomes e parâmetros

| Item | Valor |
|---|---|
| Tópico de transações | `transaction-events` |
| Tópico de alertas | `fraud-alerts` |
| DLQ de transações | `transaction-events.DLT` |
| DLQ de alertas | `fraud-alerts.DLT` |
| Consumer group do Fraud Detection | `fraud-detection-service` |
| Consumer group do Notification | `notification-service` |
| Partições iniciais | `12` (hipótese, validada no spike test) |
| Fator de replicação | `1` na execução local (um broker); `3` na arquitetura alvo (ADR 0008) |
| Retenção dos tópicos principais | 7 dias (`retention.ms=604800000`) |

## 2. Por que 12 partições

- Permite até 12 instâncias consumidoras ativas no mesmo grupo sem partições ociosas.
- Número com vários divisores, o que facilita redistribuição.
- É uma **hipótese**. A capacidade real depende do custo medido por evento. Com 25.000 TPS de pico, cada partição precisaria processar cerca de 2.100 eventos por segundo. Esse valor provavelmente exigirá mais partições ou mais instâncias, o que o spike test deve confirmar.
- Alterar o número de partições muda o mapeamento de chaves (`kafka-partitioning.md` §6). Por isso a escolha deve ser feita antes da carga real.

## 3. Retry e DLQ

- Retry com backoff exponencial: 3 tentativas, intervalos de 1 s, 2 s e 4 s.
- Erros de validação de contrato (payload inválido) vão direto para a DLQ, sem retry.
- Erros transitórios (banco indisponível, timeout) seguem o retry.
- Após esgotar as tentativas, a mensagem vai para a DLQ com cabeçalhos de origem e motivo.

## 4. Inbox (idempotência do consumidor)

- Tabela `inbox_event` com chave primária em `event_id`.
- Gravação na mesma transação de banco da análise e da persistência do resultado.
- Retenção: 8 dias, maior que a retenção do tópico (7 dias), com margem para replay.
- Remoção por job periódico de registros mais antigos que a retenção.

## Riscos

| Risco | Como medir | Mitigação |
|---|---|---|
| Partição quente (cliente com volume alto) | Mensagens por partição e lag por partição | Monitorar; estratégia alternativa só se as métricas justificarem (`kafka-partitioning.md` §5) |
| Lag crescendo acima da capacidade | Consumer lag por grupo | Aumentar instâncias até o limite de partições |
| Rebalance frequente | Eventos de rebalance e tempo de processamento por lote | Ajustar `max.poll.interval.ms` e tamanho do lote |
| DLQ acumulando | Taxa de mensagens na DLQ | Alerta e procedimento de reprocessamento |

## Evolução

- Aumentar partições quando o spike test mostrar que o limite por partição é atingido. Planejar a mudança de chave antes de executar.
- Substituir a inbox por controle baseado em Redis apenas se a tabela se tornar gargalo medido.
