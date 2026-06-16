package com.walletcore.batch.config;

import com.walletcore.account.entity.Account;
import com.walletcore.account.repository.AccountRepository;
import com.walletcore.transaction.entity.Transaction;
import com.walletcore.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

@Configuration
public class BatchConfig {

    private static final Logger log = LoggerFactory.getLogger(BatchConfig.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public BatchConfig(JobRepository jobRepository,
                       PlatformTransactionManager transactionManager,
                       AccountRepository accountRepository,
                       TransactionRepository transactionRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    // ── Daily Balance Report ──────────────────────────────────────────────────

    @Bean
    Job dailyBalanceReportJob(Step dailyBalanceStep) {
        return new JobBuilder("dailyBalanceReportJob", jobRepository)
                .start(dailyBalanceStep)
                .build();
    }

    @Bean
    Step dailyBalanceStep() {
        return new StepBuilder("dailyBalanceStep", jobRepository)
                .<Account, String>chunk(50, transactionManager)
                .reader(accountReader())
                .processor(accountBalanceProcessor())
                .writer(balanceReportWriter())
                .build();
    }

    @Bean
    RepositoryItemReader<Account> accountReader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name("accountReader")
                .repository(accountRepository)
                .methodName("findAll")
                .sorts(Map.of("createdAt", Sort.Direction.ASC))
                .pageSize(50)
                .build();
    }

    @Bean
    ItemProcessor<Account, String> accountBalanceProcessor() {
        return account -> String.format("Account[%s] User[%s] Balance=%s %s",
                account.getId(), account.getUser().getId(),
                account.getBalance(), account.getCurrency());
    }

    @Bean
    ItemWriter<String> balanceReportWriter() {
        return items -> items.forEach(line -> log.info("[BALANCE-REPORT] {}", line));
    }

    // ── Monthly Statement ─────────────────────────────────────────────────────

    @Bean
    Job monthlyStatementJob(Step monthlyStatementStep) {
        return new JobBuilder("monthlyStatementJob", jobRepository)
                .start(monthlyStatementStep)
                .build();
    }

    @Bean
    Step monthlyStatementStep() {
        return new StepBuilder("monthlyStatementStep", jobRepository)
                .<Transaction, String>chunk(100, transactionManager)
                .reader(transactionReader())
                .processor(transactionStatementProcessor())
                .writer(statementWriter())
                .build();
    }

    @Bean
    RepositoryItemReader<Transaction> transactionReader() {
        return new RepositoryItemReaderBuilder<Transaction>()
                .name("transactionReader")
                .repository(transactionRepository)
                .methodName("findAll")
                .sorts(Map.of("createdAt", Sort.Direction.ASC))
                .pageSize(100)
                .build();
    }

    @Bean
    ItemProcessor<Transaction, String> transactionStatementProcessor() {
        return tx -> String.format("TX[%s] %s %s->%s amount=%s status=%s date=%s",
                tx.getId(), tx.getType(),
                tx.getSourceAccount().getId(), tx.getTargetAccount().getId(),
                tx.getAmount(), tx.getStatus(), tx.getCreatedAt());
    }

    @Bean
    ItemWriter<String> statementWriter() {
        return items -> items.forEach(line -> log.info("[MONTHLY-STATEMENT] {}", line));
    }
}
