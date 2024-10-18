/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.loanschedule.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.util.Memo;

@ToString(exclude = { "previous" })
@EqualsAndHashCode(exclude = { "previous" })
public class RepaymentPeriod {

    private final RepaymentPeriod previous;
    @Getter
    private final LocalDate fromDate;
    @Getter
    private final LocalDate dueDate;
    @Getter
    private final List<InterestPeriod> interestPeriods;
    @Setter
    @Getter
    private Money emi;
    @Getter
    private Money paidPrincipal;
    @Getter
    private Money paidInterest;

    private Memo<BigDecimal> rateFactorPlus1Calculation;
    private Memo<Money> calculatedDueInterestCalculation;
    private Memo<Money> dueInterestCalculation;
    private Memo<Money> outstandingBalanceCalculation;

    public RepaymentPeriod(RepaymentPeriod previous, LocalDate fromDate, LocalDate dueDate, Money emi) {
        this.previous = previous;
        this.fromDate = fromDate;
        this.dueDate = dueDate;
        this.emi = emi;
        this.interestPeriods = new ArrayList<>();
        // There is always at least 1 interest period, by default with same from-due date as repayment period
        getInterestPeriods().add(new InterestPeriod(this, getFromDate(), getDueDate(), BigDecimal.ZERO, getZero(), getZero(), getZero()));
        this.paidInterest = getZero();
        this.paidPrincipal = getZero();
    }

    public RepaymentPeriod(RepaymentPeriod previous, RepaymentPeriod repaymentPeriod) {
        this.previous = previous;
        this.fromDate = repaymentPeriod.fromDate;
        this.dueDate = repaymentPeriod.dueDate;
        this.emi = repaymentPeriod.emi;
        this.interestPeriods = new ArrayList<>();
        this.paidPrincipal = repaymentPeriod.paidPrincipal;
        this.paidInterest = repaymentPeriod.paidInterest;
        // There is always at least 1 interest period, by default with same from-due date as repayment period
        for (InterestPeriod interestPeriod : repaymentPeriod.interestPeriods) {
            interestPeriods.add(new InterestPeriod(this, interestPeriod));
        }
    }

    public Optional<RepaymentPeriod> getPrevious() {
        return Optional.ofNullable(previous);
    }

    public BigDecimal getRateFactorPlus1() {
        if (rateFactorPlus1Calculation == null) {
            rateFactorPlus1Calculation = Memo.of(this::calculateRateFactorPlus1, () -> this.interestPeriods);
        }
        return rateFactorPlus1Calculation.get();
    }

    private BigDecimal calculateRateFactorPlus1() {
        return interestPeriods.stream().map(InterestPeriod::getRateFactor).reduce(BigDecimal.ONE, BigDecimal::add);
    }

    public Money getCalculatedDueInterest() {
        if (calculatedDueInterestCalculation == null) {
            calculatedDueInterestCalculation = Memo.of(this::calculateCalculatedDueInterest,
                    () -> new Object[] { this.previous, this.interestPeriods });
        }
        return calculatedDueInterestCalculation.get();
    }

    private Money calculateCalculatedDueInterest() {
        Money calculatedDueInterest = getInterestPeriods().stream().map(InterestPeriod::getCalculatedDueInterest).reduce(getZero(),
                Money::plus);
        if (getPrevious().isPresent()) {
            calculatedDueInterest = calculatedDueInterest.add(getPrevious().get().getUnrecognizedInterest());
        }
        return calculatedDueInterest;
    }

    private Money getZero() {
        // EMI is always initiated
        return this.emi.zero();
    }

    public Money getCalculatedDuePrincipal() {
        return getEmi().minus(getCalculatedDueInterest());
    }

    public boolean isFullyPaid() {
        return getEmi().isEqualTo(getPaidPrincipal().plus(getPaidInterest()));
    }

    public Money getDueInterest() {
        if (dueInterestCalculation == null) {
            // Due interest might be the maximum paid if there is pay-off or early repayment
            dueInterestCalculation = Memo.of(() -> MathUtil.max(
                    getPaidPrincipal().isGreaterThan(getCalculatedDuePrincipal()) ? getPaidInterest() : getCalculatedDueInterest(),
                    getPaidInterest(), false), () -> new Object[] { paidPrincipal, paidInterest, interestPeriods });
        }
        return dueInterestCalculation.get();
    }

    public Money getDuePrincipal() {
        // Due principal might be the maximum paid if there is pay-off or early repayment
        return MathUtil.max(getEmi().minus(getDueInterest()), getPaidPrincipal(), false);
    }

    public Money getUnrecognizedInterest() {
        return getCalculatedDueInterest().minus(getDueInterest());
    }

    public Money getOutstandingLoanBalance() {
        if (outstandingBalanceCalculation == null) {
            outstandingBalanceCalculation = Memo.of(() -> {
                InterestPeriod lastInstallmentPeriod = getInterestPeriods().get(getInterestPeriods().size() - 1);
                Money calculatedOutStandingLoanBalance = lastInstallmentPeriod.getOutstandingLoanBalance() //
                        .plus(lastInstallmentPeriod.getBalanceCorrectionAmount()) //
                        .plus(lastInstallmentPeriod.getDisbursementAmount()) //
                        .minus(getDuePrincipal())//
                        .plus(getPaidPrincipal());//
                return MathUtil.negativeToZero(calculatedOutStandingLoanBalance);
            }, () -> new Object[] { paidPrincipal, paidInterest, interestPeriods });
        }
        return outstandingBalanceCalculation.get();
    }

    public void addPaidPrincipalAmount(Money paidPrincipal) {
        this.paidPrincipal = MathUtil.plus(this.paidPrincipal, paidPrincipal);
    }

    public void addPaidInterestAmount(Money paidInterest) {
        this.paidInterest = MathUtil.plus(this.paidInterest, paidInterest);
    }

    public Money getInitialBalanceForEmiRecalculation() {
        Money initialBalance;
        if (getPrevious().isPresent()) {
            initialBalance = getPrevious().get().getOutstandingLoanBalance();
        } else {
            initialBalance = getZero();
        }
        Money totalDisbursedAmount = getInterestPeriods().stream().map(InterestPeriod::getDisbursementAmount).reduce(getZero(),
                Money::plus);
        return initialBalance.add(totalDisbursedAmount);
    }
}