package innbucks.paymentservice.service;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each rail's DEFAULT payable window — from "now" to the instrument's last
 * payable instant — read from where the rails read it: the TTL's default in
 * {@code application.yaml} (what a cell runs unless it sets the env var) plus
 * the rail's own grace constant. A test that sizes a hold from these does not
 * restate the numbers it checks. Lives in this package because the graces
 * are package-private.
 */
public final class RailPayableWindows {

    private static final Pattern ENV_DEFAULT = Pattern.compile("\\$\\{[A-Z0-9_]+:([^}]+)}");
    private static final Properties YAML = load();

    private RailPayableWindows() {
    }

    /** InnBucks code: payable until its TTL ({@code PAYMENTS_CODE_TTL}). */
    public static Duration innbucksCode() {
        return yamlDefault("payments.innbucks.code.ttl");
    }

    /** EcoCash prompt: its TTL ({@code ECOCASH_CHARGE_TTL}) + the approval grace. */
    public static Duration ecocash() {
        return yamlDefault("ecocash.charge-ttl").plus(EcocashPaymentService.PROMPT_EXPIRY_GRACE);
    }

    /** Card checkout: its local TTL ({@code ZIMSWITCH_CHECKOUT_TTL}) + the grace, i.e. the gateway's ceiling. */
    public static Duration card() {
        return yamlDefault("zimswitch.checkout-ttl").plus(ZimswitchCardPaymentService.CHECKOUT_EXPIRY_GRACE);
    }

    private static Duration yamlDefault(String key) {
        String raw = YAML.getProperty(key);
        if (raw == null) {
            throw new IllegalStateException(key + " is not in application.yaml");
        }
        Matcher m = ENV_DEFAULT.matcher(raw.trim());
        return Duration.parse(m.matches() ? m.group(1) : raw.trim());
    }

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        return yaml.getObject();
    }
}
