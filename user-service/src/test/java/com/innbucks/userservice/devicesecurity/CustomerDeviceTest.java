package com.innbucks.userservice.devicesecurity;

import com.innbucks.userservice.devicesecurity.entity.CustomerDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CustomerDevice#possessionProvedThisSignIn()} is what lets one phone per
 * number sign the number's other phones out, so its edges are pinned: only a
 * PENDING_PIN phone whose code was verified no earlier than its move to
 * PENDING_PIN counts. {@code verifyOtp} stamps the proof and the move with one
 * instant; any later move (watch mode's {@code allow()}) re-stamps
 * {@code stateChangedAt} alone, so an old proof always predates it.
 */
class CustomerDeviceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 7, 58, 12);

    private static CustomerDevice device(DeviceState state, LocalDateTime stateChangedAt, LocalDateTime otpVerifiedAt) {
        return CustomerDevice.builder()
                .msisdn("+263771234512")
                .state(state)
                .stateChangedAt(stateChangedAt)
                .otpVerifiedAt(otpVerifiedAt)
                .build();
    }

    @Test
    @DisplayName("a code verified at the very instant the phone moved to PENDING_PIN is proof in this sign-in")
    void sameInstant_isProof() {
        assertThat(device(DeviceState.PENDING_PIN, NOW, NOW).possessionProvedThisSignIn()).isTrue();
    }

    @Test
    @DisplayName("a code verified after the move to PENDING_PIN is proof in this sign-in")
    void proofAfterTheMove_isProof() {
        assertThat(device(DeviceState.PENDING_PIN, NOW, NOW.plusSeconds(1)).possessionProvedThisSignIn()).isTrue();
    }

    @Test
    @DisplayName("a code verified before the move to PENDING_PIN is an old proof, not proof in this sign-in")
    void proofBeforeTheMove_isNotProof() {
        assertThat(device(DeviceState.PENDING_PIN, NOW, NOW.minusNanos(1)).possessionProvedThisSignIn()).isFalse();
        assertThat(device(DeviceState.PENDING_PIN, NOW, NOW.minusDays(40)).possessionProvedThisSignIn()).isFalse();
    }

    @Test
    @DisplayName("a phone that never verified a code has no proof in this sign-in")
    void noCode_isNotProof() {
        CustomerDevice d = device(DeviceState.PENDING_PIN, NOW, null);
        assertThat(d.possessionProvedThisSignIn()).isFalse();
        assertThat(d.possessionVerified()).isFalse();
    }

    @Test
    @DisplayName("a phone with no recorded move has no proof in this sign-in, whatever its code")
    void noStateChange_isNotProof() {
        assertThat(device(DeviceState.PENDING_PIN, null, NOW).possessionProvedThisSignIn()).isFalse();
    }

    @Test
    @DisplayName("only a PENDING_PIN phone can hold proof in this sign-in: TRUSTED, STEP_UP and every other state "
            + "never do, even with a code verified at the move")
    void otherStates_neverHoldProof() {
        assertThat(device(DeviceState.TRUSTED, NOW, NOW).possessionProvedThisSignIn()).isFalse();
        assertThat(device(DeviceState.STEP_UP, NOW, NOW.plusMinutes(1)).possessionProvedThisSignIn()).isFalse();
        for (DeviceState state : DeviceState.values()) {
            if (state == DeviceState.PENDING_PIN) continue;
            CustomerDevice d = device(state, NOW, NOW);
            assertThat(d.possessionProvedThisSignIn()).as(state.name()).isFalse();
            // The sticky proof is still there: the two questions are different.
            assertThat(d.possessionVerified()).as(state.name()).isTrue();
        }
    }

    @Test
    @DisplayName("transition() re-stamps the move, so a later move back to PENDING_PIN (watch mode's allow()) "
            + "turns an earlier code into an old proof")
    void transition_movesStateChangedAt_andInvalidatesAnOldProof() {
        // verifyOtp: one instant for the move and the proof.
        CustomerDevice d = device(DeviceState.NEW, NOW.minusDays(1), null);
        d.transition(DeviceState.PENDING_PIN, null, ActorType.APP.name(), NOW);
        d.setOtpVerifiedAt(NOW);
        assertThat(d.possessionProvedThisSignIn()).isTrue();

        // The PIN binds it; the proof stays on the row but is no longer this sign-in's.
        LocalDateTime bound = NOW.plusMinutes(1);
        d.transition(DeviceState.TRUSTED, null, ActorType.BROKER.name(), bound);
        assertThat(d.getStateChangedAt()).isEqualTo(bound);
        assertThat(d.possessionProvedThisSignIn()).isFalse();

        // Days later, watch mode moves it to PENDING_PIN with no code.
        LocalDateTime allowed = NOW.plusDays(3);
        d.transition(DeviceState.PENDING_PIN, null, ActorType.SYSTEM.name(), allowed);
        assertThat(d.getState()).isEqualTo(DeviceState.PENDING_PIN);
        assertThat(d.getPriorState()).isEqualTo(DeviceState.TRUSTED);
        assertThat(d.getStateChangedAt()).isEqualTo(allowed);
        assertThat(d.getStateChangedBy()).isEqualTo("SYSTEM");
        assertThat(d.getUpdatedAt()).isEqualTo(allowed);
        assertThat(d.getOtpVerifiedAt()).isEqualTo(NOW);
        assertThat(d.possessionVerified()).isTrue();
        assertThat(d.possessionProvedThisSignIn()).isFalse();

        // Even a re-move to the SAME state re-stamps it.
        CustomerDevice fresh = device(DeviceState.PENDING_PIN, NOW, NOW);
        fresh.transition(DeviceState.PENDING_PIN, null, ActorType.SYSTEM.name(), NOW.plusSeconds(5));
        assertThat(fresh.getStateChangedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(fresh.possessionProvedThisSignIn()).isFalse();
    }
}
