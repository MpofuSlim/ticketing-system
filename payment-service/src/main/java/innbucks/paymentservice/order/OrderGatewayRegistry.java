package innbucks.paymentservice.order;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the {@link OrderGateway} for an {@link OrderType}. Constructor-
 * injected with every gateway bean in the context, so adding a product is
 * "add an adapter bean" — no registration list to forget, and nothing
 * downstream ever branches on the product ({@code instanceof}-free by
 * construction).
 */
@Component
public class OrderGatewayRegistry {

    private final Map<OrderType, OrderGateway> gateways = new EnumMap<>(OrderType.class);

    public OrderGatewayRegistry(List<OrderGateway> discovered) {
        for (OrderGateway gateway : discovered) {
            OrderGateway previous = gateways.putIfAbsent(gateway.type(), gateway);
            if (previous != null) {
                throw new IllegalStateException("Two OrderGateway beans claim order type "
                        + gateway.type() + ": " + previous.getClass().getName()
                        + " and " + gateway.getClass().getName());
            }
        }
    }

    public OrderGateway forType(OrderType type) {
        OrderGateway gateway = gateways.get(Objects.requireNonNull(type, "orderType"));
        if (gateway == null) {
            throw new IllegalStateException("No OrderGateway registered for order type " + type);
        }
        return gateway;
    }
}
