package io.hashmatrix.controlplane.provisioning.stub;

import static org.assertj.core.api.Assertions.assertThat;

import io.hashmatrix.controlplane.tenant.member.OrgMember;
import io.hashmatrix.test.fixtures.MockData;
import org.junit.jupiter.api.Test;

/**
 * 内存成员目录 stub 单测 —— 守护与真实适配器一致的行为契约：加入幂等、移除幂等、按 orgId 分桶隔离。
 */
class InMemoryOrgMemberDirectoryTest {

    private final InMemoryOrgMemberDirectory directory = new InMemoryOrgMemberDirectory();

    @Test
    void addThenListReturnsMember() {
        String email = MockData.email("alice");
        OrgMember added = directory.addExistingUser("org-a", email);

        assertThat(added.email()).isEqualTo(email);
        assertThat(directory.list("org-a")).singleElement().isEqualTo(added);
    }

    @Test
    void addIsIdempotent() {
        String email = MockData.email("bob");
        directory.addExistingUser("org-a", email);
        directory.addExistingUser("org-a", email);

        assertThat(directory.list("org-a")).hasSize(1);
    }

    @Test
    void removeIsIdempotent() {
        OrgMember m = directory.addExistingUser("org-a", MockData.email("carol"));

        directory.remove("org-a", m.id());
        directory.remove("org-a", m.id()); // 再移一次不报错

        assertThat(directory.list("org-a")).isEmpty();
    }

    @Test
    void bucketsAreIsolatedByOrg() {
        directory.addExistingUser("org-a", MockData.email("alice"));
        directory.addExistingUser("org-b", MockData.email("bob"));

        assertThat(directory.list("org-a")).hasSize(1);
        assertThat(directory.list("org-b")).hasSize(1);
    }
}
