package com.innbucks.bookingservice.service;

import com.innbucks.bookingservice.config.InvoiceProperties;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig;
import com.innbucks.bookingservice.entity.OrganizerBillingConfig.BillingCycle;
import com.innbucks.bookingservice.exception.BadRequestException;
import com.innbucks.bookingservice.repository.OrganizerBillingConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Resolves and manages each organizer's billing terms. An organizer without a
 * persisted override is billed at the deployment defaults
 * ({@link InvoiceProperties}); {@link #resolve} always returns usable terms
 * (persisted override or a transient default), so callers never branch on null.
 */
@Service
@Slf4j
public class BillingConfigService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OrganizerBillingConfigRepository repository;
    private final InvoiceProperties properties;
    private final String cellCurrency;

    public BillingConfigService(OrganizerBillingConfigRepository repository,
                                InvoiceProperties properties,
                                @Value("${innbucks.currency:USD}") String cellCurrency) {
        this.repository = repository;
        this.properties = properties;
        this.cellCurrency = (cellCurrency == null || cellCurrency.isBlank()) ? "USD" : cellCurrency.trim();
    }

    /** Effective terms for an organizer — the persisted override, or a transient default. */
    @Transactional(readOnly = true)
    public OrganizerBillingConfig resolve(UUID organizerUuid) {
        return repository.findById(organizerUuid).orElseGet(() -> defaultFor(organizerUuid));
    }

    @Transactional(readOnly = true)
    public boolean hasOverride(UUID organizerUuid) {
        return repository.existsById(organizerUuid);
    }

    @Transactional(readOnly = true)
    public List<OrganizerBillingConfig> listOverrides() {
        return repository.findAll();
    }

    /** Create or update an organizer's override. */
    @Transactional
    public OrganizerBillingConfig upsert(UUID organizerUuid, BigDecimal commissionRate,
                                         BillingCycle billingCycle, String currency) {
        if (commissionRate == null || commissionRate.signum() < 0 || commissionRate.compareTo(HUNDRED) > 0) {
            throw new BadRequestException("commissionRate must be between 0 and 100.");
        }
        OrganizerBillingConfig config = repository.findById(organizerUuid).orElseGet(() -> {
            OrganizerBillingConfig fresh = new OrganizerBillingConfig();
            fresh.setOrganizerUuid(organizerUuid);
            return fresh;
        });
        config.setCommissionRate(commissionRate);
        config.setBillingCycle(billingCycle != null ? billingCycle : properties.getDefaultBillingCycle());
        config.setCurrency(currency != null && !currency.isBlank() ? currency.trim() : cellCurrency);
        OrganizerBillingConfig saved = repository.save(config);
        log.info("Upserted billing config organizer={} rate={} cycle={} currency={}",
                organizerUuid, saved.getCommissionRate(), saved.getBillingCycle(), saved.getCurrency());
        return saved;
    }

    /** Transient (un-persisted) default-terms config for an organizer with no override. */
    public OrganizerBillingConfig defaultFor(UUID organizerUuid) {
        OrganizerBillingConfig config = new OrganizerBillingConfig();
        config.setOrganizerUuid(organizerUuid);
        config.setCommissionRate(properties.getDefaultCommissionRate());
        config.setBillingCycle(properties.getDefaultBillingCycle());
        config.setCurrency(cellCurrency);
        return config;
    }
}
