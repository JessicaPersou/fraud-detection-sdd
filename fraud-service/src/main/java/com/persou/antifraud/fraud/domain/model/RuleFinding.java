package com.persou.antifraud.fraud.domain.model;

/**
 * Regra acionada, com o motivo do acionamento (ADR 0001, decisão 7; business-rules.md §5).
 */
public record RuleFinding(String ruleCode, String description, String reason) {
}
