package com.walletcore.batch.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class BatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);

    private final JobLauncher jobLauncher;
    private final Job dailyBalanceReportJob;
    private final Job monthlyStatementJob;

    public BatchScheduler(JobLauncher jobLauncher,
                          @Qualifier("dailyBalanceReportJob") Job dailyBalanceReportJob,
                          @Qualifier("monthlyStatementJob") Job monthlyStatementJob) {
        this.jobLauncher = jobLauncher;
        this.dailyBalanceReportJob = dailyBalanceReportJob;
        this.monthlyStatementJob = monthlyStatementJob;
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void runDailyBalanceReport() {
        var params = new JobParametersBuilder()
                .addString("date", LocalDate.now().toString())
                .toJobParameters();
        try {
            jobLauncher.run(dailyBalanceReportJob, params);
            log.info("Daily balance report job launched for {}", LocalDate.now());
        } catch (Exception e) {
            log.error("Failed to launch daily balance report job", e);
        }
    }

    @Scheduled(cron = "0 0 2 1 * *")
    public void runMonthlyStatement() {
        var params = new JobParametersBuilder()
                .addString("month", LocalDate.now().withDayOfMonth(1).toString())
                .toJobParameters();
        try {
            jobLauncher.run(monthlyStatementJob, params);
            log.info("Monthly statement job launched for {}", LocalDate.now().withDayOfMonth(1));
        } catch (Exception e) {
            log.error("Failed to launch monthly statement job", e);
        }
    }
}
