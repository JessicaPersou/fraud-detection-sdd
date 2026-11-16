# Business Rules — Fraud Detection

## 1. Objetivo

Definir as regras iniciais para identificar transações potencialmente suspeitas. Os limites descritos são hipóteses de protótipo e precisam ser validados pela área de negócio e risco.

## 2. Regras iniciais

### BR-01 — Frequência de transações

Identificar volume de transações por cliente em uma janela temporal.

Hipótese inicial para teste: mais de cinco transações em 60 segundos.

A frequência isolada não deve ser considerada prova de fraude. O resultado deve ser avaliado em conjunto com outros sinais e com o contexto disponível.

### BR-02 — Valor elevado

Identificar transações acima de um limite configurado.

O limite ainda precisa ser definido pelo negócio. Uma transação de valor elevado isoladamente será classificada inicialmente como MEDIUM, conforme decisão preliminar do projeto.

### BR-03 — Localização suspeita

Avaliar transações realizadas em locais geograficamente incompatíveis em um intervalo curto, desde que os dados de localização sejam confiáveis e estejam disponíveis.

Os critérios de distância e tempo ainda precisam ser definidos pelo negócio.

### BR-04 — Combinação de sinais

Combinar frequência, valor e localização para produzir uma classificação mais contextual. A combinação exata e os pesos não estão definidos nesta versão.

## 3. Classificação inicial

- **LOW:** nenhum comportamento suspeito relevante identificado.
- **MEDIUM:** um sinal de risco identificado, sujeito às condições da regra.
- **HIGH:** combinação de sinais ou condição considerada crítica, conforme política a validar.

Não utilizar apenas a contagem de regras acionadas como critério universal. As regras devem poder expressar severidade e contexto. A primeira versão utilizará uma política simples, documentada e testável; Risk Scoring mais elaborado fica para evolução futura.

## 4. Resultado da análise

Cada análise deverá registrar:

- transactionId.
- customerId.
- riskLevel.
- triggeredRules.
- analyzedAt.
- identificador do evento de origem.

## 5. Requisitos de implementação

- Limites não devem ser espalhados pelo código; centralizar em configuração.
- Regras devem ser testáveis isoladamente.
- Uma regra deve explicar por que foi acionada.
- Ausência de dados de localização não deve ser tratada automaticamente como fraude.
- O processamento de eventos duplicados não deve gerar decisões ou notificações duplicadas.

## 6. Pendências de negócio

- Limite de valor por perfil/segmento.
- Janela fixa ou deslizante para frequência.
- Tratamento de transações legítimas em períodos de compra intensa.
- Distância mínima e intervalo temporal para localização.
- Severidade e combinação das regras.
- Dados necessários e sua origem.

## 7. Implementação atual (Fase 3)

Esta seção descreve o que o código faz hoje. Não é validação de negócio: os valores e a política continuam provisórios até a aprovação da área de negócio.

- **BR-02 (valor):** a regra aciona quando `amountInCents` é maior que `fraud.rules.amount.threshold-cents`. O limite não tem padrão no código.
- **BR-01 (frequência):** conta as transações do cliente em uma janela deslizante que termina no `occurredAt` do evento, com os dois extremos incluídos. A transação atual entra na contagem. A regra aciona quando a contagem é maior que `fraud.rules.frequency.max-transactions`. A janela vem de `fraud.rules.frequency.window` (ISO-8601).
- **BR-03 (localização):** não implementada. O contrato `TransactionCreated` não traz dados de localização, e a ausência de dados nunca vira fraude.
- **BR-04 (combinação):** política provisória. Nenhum sinal é LOW; um sinal é MEDIUM; dois ou mais sinais são HIGH. Os pesos ainda não existem.
- **Eventos fora de ordem:** a janela é calculada pelo `occurredAt` do evento, não pela hora de chegada. Consumidores de uma mesma partição processam em ordem, mas a janela pode incluir transações que chegaram depois, se o produtor atrasar.
