package io.hashmatrix.controlplane.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

/** 租户状态机转移表单测 —— 守住合法路径与非法回退的边界。 */
class TenantStatusTest {

    @Test
    void canonicalLifecyclePathIsAllowed() {
        assertThat(TenantStatus.REGISTERED.canTransitionTo(TenantStatus.APPROVING)).isTrue();
        assertThat(TenantStatus.APPROVING.canTransitionTo(TenantStatus.PROVISIONING)).isTrue();
        assertThat(TenantStatus.PROVISIONING.canTransitionTo(TenantStatus.ACTIVE)).isTrue();
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.SUSPENDED)).isTrue();
        assertThat(TenantStatus.SUSPENDED.canTransitionTo(TenantStatus.ACTIVE)).isTrue();
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.DELETED)).isTrue();
    }

    @Test
    void failureAndRejectRevertsAreAllowed() {
        // 审批驳回退草稿；开通失败退审批门控。
        assertThat(TenantStatus.APPROVING.canTransitionTo(TenantStatus.REGISTERED)).isTrue();
        assertThat(TenantStatus.PROVISIONING.canTransitionTo(TenantStatus.APPROVING)).isTrue();
    }

    @Test
    void illegalJumpsAreRejected() {
        assertThat(TenantStatus.REGISTERED.canTransitionTo(TenantStatus.ACTIVE)).isFalse();
        assertThat(TenantStatus.REGISTERED.canTransitionTo(TenantStatus.PROVISIONING)).isFalse();
        assertThat(TenantStatus.ACTIVE.canTransitionTo(TenantStatus.PROVISIONING)).isFalse();
        assertThat(TenantStatus.SUSPENDED.canTransitionTo(TenantStatus.PROVISIONING)).isFalse();
    }

    @Test
    void deletedIsTerminal() {
        assertThat(TenantStatus.DELETED.isTerminal()).isTrue();
        assertThat(TenantStatus.DELETED.allowedTargets()).isEmpty();
        for (TenantStatus target : TenantStatus.values()) {
            assertThat(TenantStatus.DELETED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void checkTransitionThrowsOnIllegalAndPassesOnLegal() {
        assertThatExceptionOfType(TenantStatusTransitionException.class)
                .isThrownBy(() -> TenantStatus.REGISTERED.checkTransitionTo(TenantStatus.ACTIVE))
                .satisfies(
                        e -> {
                            assertThat(e.getFrom()).isEqualTo(TenantStatus.REGISTERED);
                            assertThat(e.getTo()).isEqualTo(TenantStatus.ACTIVE);
                        });
        assertThatNoException()
                .isThrownBy(() -> TenantStatus.REGISTERED.checkTransitionTo(TenantStatus.APPROVING));
    }
}
