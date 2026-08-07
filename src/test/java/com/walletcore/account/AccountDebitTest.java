package com.walletcore.account;

import com.walletcore.account.entity.Account;
import com.walletcore.user.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AccountDebitTest {

    @Test
    void debit_onRegularAccountWithoutBalance_shouldThrow() {
        var account = new Account(new User(), "Conta Comum", "BRL");

        assertThrows(IllegalStateException.class,
                () -> account.debit(new BigDecimal("10.00")));
    }

    @Test
    void isSystem_defaultsToFalse() {
        var account = new Account(new User(), "Conta Comum", "BRL");

        assertFalse(account.isSystem());
    }
}
