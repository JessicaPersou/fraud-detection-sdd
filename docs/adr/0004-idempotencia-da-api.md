# ADR 0004 — Idempotência da API `POST /transactions`

**Status:** Aceita  
**Data:** 2026-11-15  
**Resolve:** ADR 0001 §5, item 3  
**Relaciona-se com:** ADR 0001, decisão 1

## Contexto

O cliente envia `Idempotency-Key` para tornar reenvios seguros. Faltava definir: o que acontece quando a mesma chave chega com payload diferente, quando duas requisições idênticas chegam ao mesmo tempo e por quanto tempo a chave é guardada.

## Decisão

Tabela `idempotency_key` com chave única por `(idempotency_key)` e os campos: `request_hash` (SHA-256 do corpo normalizado), `status` (`IN_PROGRESS`, `COMPLETED`), `transaction_id`, `response_body`, `created_at`, `expires_at`.

| Situação | Resposta |
|---|---|
| Chave nova | Processa, grava transação e chave na mesma transação de banco. Retorna `201`. |
| Mesma chave, mesmo `request_hash`, `COMPLETED` | Retorna a resposta armazenada (`200`), sem efeito colateral. |
| Mesma chave, mesmo `request_hash`, `IN_PROGRESS` | Retorna `409 Conflict` com indicação de tentar novamente. |
| Mesma chave, `request_hash` diferente | Retorna `422 Unprocessable Content`. |
| Chave ausente | Retorna `400 Bad Request`. |

- Concorrência: a restrição de unicidade da chave no banco é a barreira final. A segunda inserção concorrente falha e é tratada como `409` ou como replay.
- Retenção: 24 horas (`expires_at`). Após expirar, a mesma chave pode ser reutilizada.
- Não expor detalhes internos nas mensagens de erro.

## Justificativa

- Hash do payload separa "reenvio legítimo" de "reutilização indevida de chave".
- Usar o banco como fonte de verdade evita dependência de Redis na v1 (ADR 0001, arquitetura).
- A janela de 24 horas cobre retries de clientes sem acumular tabela indefinidamente.

## Riscos

| Risco | Como medir | Mitigação |
|---|---|---|
| Crescimento da tabela | Número de linhas e tamanho em disco | Job de limpeza por `expires_at` |
| Contenção em chaves quentes | Latência de inserção e deadlocks | Chaves são únicas por requisição; monitorar erros de duplicidade |
| Estado `IN_PROGRESS` abandonado (crash entre inserções) | Idade de registros `IN_PROGRESS` | Gravar transação e chave na mesma transação; job marca como falha as pendentes acima de um limite |

## Evolução

Mover a verificação rápida para Redis (`SET NX` com TTL) quando a tabela de idempotência se tornar gargalo medido no spike test. O banco permanece como fonte de verdade para as transações.
