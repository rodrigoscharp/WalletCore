package com.walletcore.account.controller;

import com.walletcore.account.dto.AccountResponse;
import com.walletcore.account.dto.BalanceResponse;
import com.walletcore.account.dto.CreateAccountRequest;
import com.walletcore.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
@SecurityRequirement(name = "bearerAuth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new account")
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    @GetMapping
    @Operation(summary = "List accounts of the authenticated user")
    public List<AccountResponse> list() {
        return accountService.listAccounts();
    }

    @GetMapping("/{id}/balance")
    @Operation(summary = "Get account balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        return accountService.getBalance(id);
    }
}
