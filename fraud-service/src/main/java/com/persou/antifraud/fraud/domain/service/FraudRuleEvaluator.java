package com.persou.antifraud.fraud.domain.service;

import java.util.ArrayList;
import java.util.List;

import com.persou.antifraud.fraud.domain.model.RuleFinding;

/**
 * Avalia as regras BR-01 (frequência) e BR-02 (valor).
 *
 * <p>Os limites chegam por construtor, vindos da configuração externa (ADR 0001, decisão 10).
 * Nenhum valor de negócio é definido aqui.
 *
 * <p>BR-03 (localização) não é avaliada: o contrato {@code TransactionCreated} não traz dados
 * de localização. Ausência de dados nunca é tratada como fraude (business-rules.md §5).
 */
public final class FraudRuleEvaluator {

    static final String AMOUNT_RULE = "HIGH_TRANSACTION_AMOUNT";
    static final String FREQUENCY_RULE = "TRANSACTION_FREQUENCY";

    private final long amountThresholdCents;
    private final int maxTransactions;
    private final long windowSeconds;

    public FraudRuleEvaluator(long amountThresholdCents, int maxTransactions, long windowSeconds) {
        this.amountThresholdCents = amountThresholdCents;
        this.maxTransactions = maxTransactions;
        this.windowSeconds = windowSeconds;
    }

    /**
     * @param transactionsInWindow quantidade de transações do cliente na janela, incluindo a atual
     */
    public List<RuleFinding> evaluate(long amountInCents, long transactionsInWindow) {
        List<RuleFinding> findings = new ArrayList<>();

        if (amountInCents > amountThresholdCents) {
            findings.add(new RuleFinding(AMOUNT_RULE,
                    "Valor acima do limite configurado",
                    "Valor de " + amountInCents + " centavos acima do limite configurado de "
                            + amountThresholdCents + " centavos"));
        }

        if (transactionsInWindow > maxTransactions) {
            findings.add(new RuleFinding(FREQUENCY_RULE,
                    "Volume de transações do cliente acima do limite na janela configurada",
                    transactionsInWindow + " transações em " + windowSeconds
                            + " segundos; limite configurado: " + maxTransactions));
        }

        return List.copyOf(findings);
    }
}
