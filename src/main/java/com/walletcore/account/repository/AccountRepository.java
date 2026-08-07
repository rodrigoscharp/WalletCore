package com.walletcore.account.repository;

import com.walletcore.account.entity.Account;
import com.walletcore.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByUserOrderByCreatedAtAsc(User user);

    Optional<Account> findByCurrencyAndIsSystemTrue(String currency);

    /**
     * Lê a conta com {@code SELECT ... FOR UPDATE}.
     *
     * <p><strong>Nenhum {@code Account} pode entrar no contexto de persistência antes desta
     * chamada.</strong> O lock de linha é adquirido de verdade, mas se a entidade já estiver
     * gerenciada o Hibernate devolve a instância em cache sem re-hidratá-la a partir do resultado
     * da query — e {@code Account} não tem {@code @Version}, então a verificação de versão do
     * {@code upgradeLockMode} também não dispara. O resultado é um lock legítimo sobre um estado
     * velho: duas transações concorrentes leem o mesmo saldo e a segunda sobrescreve a primeira.
     *
     * <p>Tudo que precisa ser sabido sobre uma conta antes do lock deve ser lido por projeção
     * escalar ({@link #findCurrencyById}, {@link #findIdByCurrencyAndIsSystemTrue},
     * {@link #findOwnershipById}), nunca por {@code findById}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(UUID id);

    /** Moeda da conta, sem trazer a entidade para o contexto de persistência. */
    @Query("SELECT a.currency FROM Account a WHERE a.id = :id")
    Optional<String> findCurrencyById(UUID id);

    /** Id da conta de compensação da moeda, sem trazer a entidade para o contexto de persistência. */
    @Query("SELECT a.id FROM Account a WHERE a.currency = :currency AND a.isSystem = TRUE")
    Optional<UUID> findIdByCurrencyAndIsSystemTrue(String currency);

    /** Dono e situação da conta, sem trazer a entidade para o contexto de persistência. */
    @Query("""
            SELECT new com.walletcore.account.repository.AccountOwnership(a.user.id, a.status)
            FROM Account a
            WHERE a.id = :id
            """)
    Optional<AccountOwnership> findOwnershipById(UUID id);
}
