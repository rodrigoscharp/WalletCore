package com.walletcore.account.repository;

import com.walletcore.account.entity.Account;

import java.util.UUID;

/**
 * Projeção de posse e situação de uma conta, sem materializar a entidade {@link Account}.
 *
 * <p>Existe por um motivo específico: qualquer {@code SELECT} que traga um {@code Account} para o
 * contexto de persistência <em>antes</em> do {@code SELECT ... FOR UPDATE} envenena a leitura
 * travada. O Hibernate garante repeatable read dentro do contexto — encontrando a instância já
 * gerenciada, ele devolve o objeto em cache e <strong>não</strong> re-hidrata o estado vindo do
 * resultado da query travada. O lock é real, o estado sob ele é velho, e duas transações
 * concorrentes podem ler o mesmo saldo e sobrescrever uma à outra (lost update).
 *
 * <p>Por isso a checagem de posse que roda antes dos locks usa esta projeção escalar: colunas
 * soltas não entram no contexto de persistência.
 */
public record AccountOwnership(UUID userId, Account.AccountStatus status) {}
