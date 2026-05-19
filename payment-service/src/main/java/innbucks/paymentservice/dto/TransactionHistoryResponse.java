package innbucks.paymentservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Page envelope for GET /payments/transactions. Flat fields (rather than
 * Spring Data's full {@code Page} serialisation) so the wire shape stays
 * stable if the underlying repository switches between Page / Slice /
 * cursor pagination later.
 */
@Schema(name = "TransactionHistoryResponse",
        description = "Paginated customer transaction history.")
public record TransactionHistoryResponse(
        List<TransactionView> transactions,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static TransactionHistoryResponse from(Page<TransactionView> page) {
        return new TransactionHistoryResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
