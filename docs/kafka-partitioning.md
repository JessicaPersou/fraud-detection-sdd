# Kafka Partitioning Strategy

## 1. Objetivo

Definir uma estratégia inicial para suportar alto throughput e preservar a ordem dos eventos de um mesmo cliente.

## 2. Chave de particionamento

A chave inicial será `customerId`.

Com a mesma chave e a mesma configuração de particionamento, eventos do mesmo cliente são direcionados à mesma partição. A ordem é garantida dentro da partição, não globalmente entre todas as partições.

## 3. Consumer Group

O Fraud Detection Service utilizará um Consumer Group. Instâncias adicionais podem consumir partições em paralelo. Em um grupo, uma partição é atribuída a no máximo um consumidor por vez; consumidores adicionais podem ficar ociosos se houver menos partições que instâncias.

## 4. Dimensionamento

A quantidade inicial de partições será definida por testes, considerando:

- 8.000 TPS médios e pico de 25.000 TPS.
- Capacidade de processamento por consumidor.
- Latência p95 e p99.
- Consumer lag.
- CPU, memória, rede e I/O.
- Distribuição de chaves entre partições.

Não definir quantidade de partições apenas a partir do TPS total; medir o custo real de processamento e a capacidade do cluster.

## 5. Hot partitions

Um cliente com volume desproporcional pode concentrar eventos em uma partição. Monitorar taxa de mensagens e lag por partição.

Aumentar consumidores não resolve necessariamente uma partição quente. Estratégias alternativas devem preservar a capacidade de calcular frequência por cliente e serão avaliadas apenas se métricas demonstrarem necessidade.

## 6. Alteração de partições

Aumentar a quantidade de partições pode mudar o mapeamento de chaves e afetar a ordenação histórica por cliente. Mudanças devem ser planejadas e testadas.

## 7. Monitoramento

- Mensagens por segundo por partição.
- Consumer lag por partição.
- Latência de processamento.
- Rebalances e falhas de consumidores.
- Distribuição de chaves.

## 8. Decisão inicial

Usar `customerId` como chave inicial, sujeita à validação por testes de carga e distribuição.
