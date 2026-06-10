package com.innbucks.loyaltyservice.service;

import com.innbucks.loyaltyservice.client.UserServiceClient;
import com.innbucks.loyaltyservice.dto.Dtos;
import com.innbucks.loyaltyservice.entity.Merchant;
import com.innbucks.loyaltyservice.repository.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MerchantService#list(UUID, Pageable, boolean)}.
 *
 * <p>Pins how the unassigned filter routes between the repository's two
 * finders depending on what user-service returns, and that user-service
 * failures propagate (no silent fallback — see the service comment).
 */
class MerchantServiceTest {

    private static Merchant merchant(UUID id, UUID tenantId, String name) {
        Merchant m = new Merchant();
        m.setId(id);
        m.setTenantId(tenantId);
        m.setName(name);
        m.setCategory("Coffee");
        m.setCurrency("USD");
        m.setStatus(Merchant.Status.ACTIVE);
        return m;
    }

    @Test
    void list_defaultUnassignedFalse_skipsUserServiceAndReturnsAll() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(repo.findByTenantId(tenantId, page))
                .thenReturn(new PageImpl<>(List.of(merchant(UUID.randomUUID(), tenantId, "A"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient).list(tenantId, page);

        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByTenantId(tenantId, page);
        verifyNoInteractions(userClient);
    }

    @Test
    void list_unassignedTrue_emptyExclusionSet_fallsThroughToFindByTenantId() {
        // Hibernate refuses to emit `IN ()`; when nobody has an admin yet,
        // the unassigned page IS the unfiltered page.
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds()).thenReturn(Set.of());
        when(repo.findByTenantId(tenantId, page))
                .thenReturn(new PageImpl<>(List.of(merchant(UUID.randomUUID(), tenantId, "Solo"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient).list(tenantId, page, true);

        assertThat(result.getContent()).hasSize(1);
        verify(repo).findByTenantId(tenantId, page);
        verify(repo, never()).findByTenantIdAndIdNotIn(any(), any(), any());
    }

    @Test
    void list_unassignedTrue_nonEmptyExclusion_callsNotInFinderWithThatSet() {
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        UUID claimed = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds()).thenReturn(Set.of(claimed));
        when(repo.findByTenantIdAndIdNotIn(eq(tenantId), eq(Set.of(claimed)), eq(page)))
                .thenReturn(new PageImpl<>(List.of(merchant(free, tenantId, "Up for grabs"))));

        Page<Dtos.MerchantResponse> result =
                new MerchantService(repo, userClient).list(tenantId, page, true);

        assertThat(result.getContent()).extracting(Dtos.MerchantResponse::id).containsExactly(free);
        verify(repo).findByTenantIdAndIdNotIn(tenantId, Set.of(claimed), page);
        verify(repo, never()).findByTenantId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void list_unassignedTrue_userServiceDown_propagatesIllegalStateException() {
        // Silent fallback to "all merchants" would show the picker
        // already-claimed merchants and defeat the whole feature, so the
        // service lets the exception bubble for the controller to map to 503.
        MerchantRepository repo = mock(MerchantRepository.class);
        UserServiceClient userClient = mock(UserServiceClient.class);
        UUID tenantId = UUID.randomUUID();
        Pageable page = PageRequest.of(0, 20);
        when(userClient.assignedMerchantIds())
                .thenThrow(new IllegalStateException("user-service unavailable"));

        assertThatThrownBy(() -> new MerchantService(repo, userClient).list(tenantId, page, true))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repo);
    }
}
