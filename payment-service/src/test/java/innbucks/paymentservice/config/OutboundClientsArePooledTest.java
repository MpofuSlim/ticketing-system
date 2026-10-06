package innbucks.paymentservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import innbucks.paymentservice.client.BookingServiceClient;
import innbucks.paymentservice.client.EcocashEipClient;
import innbucks.paymentservice.client.EcocashProperties;
import innbucks.paymentservice.client.EventServiceClient;
import innbucks.paymentservice.client.InnbucksApiClient;
import innbucks.paymentservice.client.InnbucksApiProperties;
import innbucks.paymentservice.client.LoyaltyServiceClient;
import innbucks.paymentservice.client.LoyaltyVoucherOrderClient;
import innbucks.paymentservice.client.MarketplaceOrderClient;
import innbucks.paymentservice.client.ZimswitchCopyPayClient;
import innbucks.paymentservice.client.ZimswitchProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.apache.hc.client5.http.config.RequestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outbound client in payment-service rides the module's ONE pooled
 * transport ({@link PooledHttpClient}) with its own timeouts, and the
 * partner clients keep the User-Agent each partner has always seen. Built the
 * way the Spring context builds them. CLAUDE.md, "Outbound HTTP clients are pooled".
 */
class OutboundClientsArePooledTest {

    private static final PooledHttpClient POOL = new PooledHttpClient(new PooledHttpClientProperties());
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterAll
    static void close() {
        POOL.close();
    }

    @SuppressWarnings("deprecation") // RequestConfig#getConnectTimeout
    static void assertPooled(RestClient client, long connectMs, long readMs) {
        Object factory = ReflectionTestUtils.getField(client, "clientRequestFactory");
        assertThat(factory).isInstanceOf(PooledHttpClient.PooledRequestFactory.class);
        PooledHttpClient.PooledRequestFactory pooled = (PooledHttpClient.PooledRequestFactory) factory;
        assertThat(pooled.getHttpClient()).isSameAs(POOL.httpClient());
        RequestConfig config = pooled.requestConfig();
        assertThat(config.getConnectTimeout().toMilliseconds()).isEqualTo(connectMs);
        assertThat(config.getResponseTimeout().toMilliseconds()).isEqualTo(readMs);
        assertThat(config.isRedirectsEnabled()).isFalse();
    }

    static RestClient restClientOf(Object client) {
        return (RestClient) ReflectionTestUtils.getField(client, "restClient");
    }

    static String userAgentOf(RestClient client) {
        HttpHeaders headers = (HttpHeaders) ReflectionTestUtils.getField(client, "defaultHeaders");
        return headers == null ? null : headers.getFirst(HttpHeaders.USER_AGENT);
    }

    static RestClient.Builder lb() {
        return new LoadBalancedRestClientConfig().loadBalancedRestClientBuilder(POOL);
    }

    @Test
    @DisplayName("InnBucks Merchant API: pooled, its own 3s/20s, the JDK User-Agent it always sent")
    void innbucksMerchantApi() {
        InnbucksApiProperties props = new InnbucksApiProperties();
        props.setBaseUrl("https://innbucks.example");
        RestClient rc = restClientOf(new InnbucksApiClient(props, JSON, RetryRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(), POOL));

        assertPooled(rc, 3000, 20000);
        assertThat(userAgentOf(rc)).isEqualTo("Java-http-client/" + System.getProperty("java.version"));
    }

    @Test
    @DisplayName("ZimSwitch COPYandPAY: pooled, its own 3s/15s, the JDK User-Agent it always sent")
    void zimswitch() {
        ZimswitchProperties props = new ZimswitchProperties();
        props.setBaseUrl("https://zimswitch.example");
        RestClient rc = restClientOf(new ZimswitchCopyPayClient(props, JSON, RetryRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(), POOL));

        assertPooled(rc, 3000, 15000);
        assertThat(userAgentOf(rc)).isEqualTo(PooledHttpClient.JDK_HTTP_CLIENT_USER_AGENT);
    }

    @Test
    @DisplayName("EcoCash EIP: pooled, its own 3s/15s, and STILL its honest User-Agent, exactly")
    void ecocash() {
        EcocashProperties props = new EcocashProperties();
        props.setBaseUrl("https://ecocash.example");
        RestClient rc = restClientOf(new EcocashEipClient(props, JSON, RetryRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(), POOL));

        assertPooled(rc, 3000, 15000);
        assertThat(userAgentOf(rc)).isEqualTo("Ticketize-Payments/1.0");
    }

    @Test
    @DisplayName("notification API + WhatsApp: pooled with their own timeouts, the User-Agent they always sent")
    void notificationClients() {
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        notify.setBaseUrl("https://notify.example");
        RestClient n = new InnbucksNotifyClientConfig().innbucksNotifyRestClient(notify, POOL);
        assertPooled(n, notify.getConnectTimeoutMs(), notify.getReadTimeoutMs());
        assertThat(userAgentOf(n)).isEqualTo(PooledHttpClient.URL_CONNECTION_USER_AGENT);

        WhatsAppProperties wa = new WhatsAppProperties();
        wa.setBaseUrl("https://wa.example");
        RestClient w = new WhatsAppClientConfig().whatsAppRestClient(wa, POOL);
        assertPooled(w, wa.getConnectTimeoutMs(), wa.getReadTimeoutMs());
        assertThat(userAgentOf(w)).isEqualTo(PooledHttpClient.URL_CONNECTION_USER_AGENT);
    }

    @Test
    @DisplayName("in-cluster siblings (load-balanced): pooled, each with its own timeouts")
    void siblings() {
        assertPooled(restClientOf(new BookingServiceClient(lb(), "http://booking-service", 2001, 5001, "t", JSON, POOL)), 2001, 5001);
        assertPooled(restClientOf(new EventServiceClient(lb(), "http://event-service", 2002, 3002, "t", JSON, POOL)), 2002, 3002);
        assertPooled(restClientOf(new LoyaltyServiceClient(lb(), "http://loyalty-service", 2003, 5003, "t", JSON, POOL)), 2003, 5003);
        assertPooled(restClientOf(new LoyaltyVoucherOrderClient(lb(), "http://loyalty-service", 2004, 5004, "t", JSON, POOL)), 2004, 5004);
        assertPooled(restClientOf(new MarketplaceOrderClient(lb(), "http://marketplace-service", 2005, 5005, "t", JSON, POOL)), 2005, 5005);
    }

    @Test
    @DisplayName("both RestClient builders start on the pool with the defaults, never Spring's default factory")
    void buildersStartPooled() {
        LoadBalancedRestClientConfig config = new LoadBalancedRestClientConfig();
        assertPooled(config.restClientBuilder(POOL).build(), 2000, 10000);
        assertPooled(config.loadBalancedRestClientBuilder(POOL).build(), 2000, 10000);
    }
}
