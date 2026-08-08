package com.walletcore.transaction.repository;

import com.walletcore.transaction.entity.Transaction;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    /**
     * Transações em que a conta aparece como origem ou destino, restritas apenas pelos filtros
     * de fato informados.
     *
     * <p>Os filtros nulos são omitidos do predicado em vez de virarem {@code :param IS NULL OR ...}.
     * Aquele idioma é o que quebrava esta consulta: um parâmetro que só aparece em posição
     * {@code ? IS NULL} não dá ao PostgreSQL nenhum contexto de onde inferir o tipo, e o servidor
     * responde {@code 42P18 could not determine data type of parameter}. Vale para qualquer tipo —
     * o primeiro a estourar aqui era um {@code Instant}, não um enum. Montar o predicado
     * dinamicamente elimina a classe inteira do problema e ainda deixa a consulta indexável.
     */
    public static Specification<Transaction> forAccountWithFilters(
            UUID accountId,
            Instant startDate,
            Instant endDate,
            Transaction.TransactionStatus status,
            Transaction.TransactionType type) {

        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();

            predicates.add(cb.or(
                    cb.equal(root.get("sourceAccount").get("id"), accountId),
                    cb.equal(root.get("targetAccount").get("id"), accountId)));

            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<Instant>get("createdAt"), endDate));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
