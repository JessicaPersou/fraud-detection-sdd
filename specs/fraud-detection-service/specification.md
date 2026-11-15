# Fraud Detection Service — Specification

## 1. Responsabilidade

Consumir eventos de transação, executar regras antifraude, classificar o risco, persistir o resultado e publicar alertas quando aplicável.

## 2. Entrada

Evento `TransactionCreated` do tópico `transaction-events`.

## 3. Casos de uso

- AnalyzeTransaction
- EvaluateTransactionRules
- ClassifyTransactionRisk
- PersistFraudAnalysis
- PublishFraudAlert

## 4. Ports

### Input Ports

- `AnalyzeTransactionUseCase`

### Output Ports

- `FraudAnalysisRepository`
- `TransactionFrequencyPort`
- `CustomerLocationHistoryPort` (somente se a regra de localização for implementada)
- `FraudAlertPublisher`
- `InboxRepository` (controle de processamento duplicado, ADR 0001, decisão 6)

## 5. Adapters

### Entrada

- Kafka Consumer.

### Saída

- MySQL Repository.
- Kafka Producer.

Redis não faz parte da primeira versão. A frequência (BR-01) será implementada inicialmente com MySQL. Um *spike test* de carga é obrigatório na Fase 3b, antes da Fase 5. Se não atender à latência, a estratégia de armazenamento será reavaliada (ADR 0001, decisão 4).

## 6. Regras

- Frequência de transações.
- Valor elevado.
- Localização suspeita, se os dados necessários estiverem disponíveis.
- Combinação contextual de sinais.

Os limites e a política de classificação são provisórios e devem ser validados com a área de negócio.

## 7. Processamento

Configuração: `enable-auto-commit: false`. O offset é confirmado somente após a conclusão das etapas 3 a 8 (ADR 0001, decisão 8). Semântica *at-least-once* com processamento idempotente.

1. Consumir evento.
2. Validar contrato e identificadores.
3. Verificar a tabela inbox pelo `eventId`; se já processado, pular a análise e confirmar o offset.
4. Recuperar os dados necessários.
5. Executar regras.
6. Classificar risco.
7. Persistir resultado e registro na inbox na mesma transação de banco.
8. Publicar alerta quando aplicável. Se a publicação falhar após a persistência, a etapa deve ser retentada com o mesmo `eventId`.
9. Confirmar offset.

## 7.1 Explicabilidade

Cada regra acionada registra `ruleCode`, `description` e `reason`, conforme o contrato `FraudDetected` (ADR 0001, decisão 7).

## 7.2 Configuração de regras

Limites e pesos são lidos de configuração externa (ADR 0001, decisão 10). Não são definidos no código.

## 8. Resiliência

- Retry limitado com backoff para falhas transitórias.
- DLQ para mensagens não processáveis após tentativas.
- Idempotência para evitar efeitos duplicados.
- Monitoramento de lag e latência.

## 9. Critérios de aceite

- Consumir eventos válidos.
- Aplicar regras documentadas.
- Registrar resultado da análise.
- Publicar alertas quando aplicável.
- Tratar duplicidade sem duplicar efeitos.
- Atender à meta de latência em testes representativos.
- Possuir testes unitários, integração e carga.
