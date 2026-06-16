package com.walletcore.account.service;

import com.walletcore.account.dto.AccountResponse;
import com.walletcore.account.dto.BalanceResponse;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.account.entity.Account;
import com.walletcore.account.repository.AccountRepository;
import com.walletcore.config.error.ApiException;
import com.walletcore.user.entity.User;
import com.walletcore.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    @CacheEvict(value = "accounts", key = "#result.userId()")
    public AccountResponse createAccount(CreateAccountRequest request) {
        var user = currentUser();
        var account = new Account(user, request.name(), request.currency());
        accountRepository.save(account);

        log.info("Account created: {} for user: {}", account.getId(), user.getEmail());
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "accounts", key = "#root.target.currentUser().id")
    public List<AccountResponse> listAccounts() {
        var user = currentUser();
        return accountRepository.findAllByUserOrderByCreatedAtAsc(user)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID accountId) {
        var user = currentUser();
        var account = findAccountOwnedBy(accountId, user);
        return new BalanceResponse(account.getId(), account.getBalance(),
                account.getCurrency(), Instant.now());
    }

    public Account findAccountOwnedBy(UUID accountId, User user) {
        var account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found"));

        if (!account.getUser().getId().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Account does not belong to the current user");
        }

        if (account.getStatus() != Account.AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Account is not active");
        }

        return account;
    }

    public User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
